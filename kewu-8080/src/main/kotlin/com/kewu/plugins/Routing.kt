package com.kewu.plugins

import com.kewu.modules.assignment.repository.AssignmentRepository
import com.kewu.modules.assignment.routes.assignmentRoutes
import com.kewu.modules.assignment.service.AssignmentService
import com.kewu.modules.attendance.repository.AttendanceRepository
import com.kewu.modules.attendance.routes.attendanceRoutes
import com.kewu.modules.attendance.service.AttendanceService
import com.kewu.modules.teacher.repository.TeacherRepository
import com.kewu.modules.teacher.routes.teacherRoutes
import com.kewu.modules.teacher.service.TeacherService
import com.kewu.modules.course.repository.CourseRepository
import com.kewu.modules.course.routes.courseRoutes
import com.kewu.modules.course.service.CourseService
import com.kewu.modules.user.repository.UserRepository
import com.kewu.modules.user.routes.userRoutes
import com.kewu.modules.user.service.UserService
import com.kewu.db.AiStatusRedis
import com.kewu.db.RadarRedisClient
import com.kewu.infra.HuaweiMaaSClient
import com.kewu.modules.analytics.repository.QuestionRepository
import com.kewu.modules.analytics.routes.analyticsRoutes
import com.kewu.modules.analytics.service.RadarService
import com.kewu.modules.aistatus.routes.aiStatusRoutes
import com.kewu.modules.aistatus.service.AiStatusService
import com.kewu.modules.questions.routes.questionRoutes
import com.kewu.modules.questions.service.QuestionService
import com.kewu.recserver.recordingModule
import com.kewu.modules.schedule.scheduleRoutes
import com.kewu.modules.roster.rosterRoutes
import com.kewu.modules.upload.routes.uploadRoutes
import com.kewu.modules.admin.routes.adminRoutes
import com.kewu.modules.admin.service.AdminService
import com.kewu.recserver.config.loadRecConfig
import com.kewu.modules.bilibili.BilibiliFetcher
import com.kewu.modules.bilibili.BilibiliNoteService
import com.kewu.modules.bilibili.bilibiliRoutes
import com.kewu.modules.audio.TranscribeFusionService
import com.kewu.modules.audio.audioFusionRoutes
import com.kewu.recserver.services.ai.MaasAiService
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.application.*
import io.ktor.server.response.respond
import kotlinx.coroutines.launch
import io.ktor.server.response.respondText
import io.ktor.server.routing.*

// 把各个模块的路由集中装进来
fun Application.configureRouting() {
    val courseRepository = CourseRepository()
    // 用户仓储只建一个实例, 各模块共用, 否则教师端查不到刚注册的用户
    val userRepository = UserRepository()
    val userService = UserService(userRepository, courseRepository)
    val courseService = CourseService(courseRepository)
    // 所有仓储都只建一个实例给各模块共用, 不然教师端读不到别的模块写进去的数据
    val assignmentRepository = AssignmentRepository()
    val attendanceRepository = AttendanceRepository()
    // 班级仓储也只建一个: 作业模块要按老师查他教的课, 教师端也要用它, 两边必须同一份数据
    val teacherRepository = TeacherRepository()
    // 总控端（管理后台，2026-10-28 移植 admin）：复用同一套仓储 + OBS/Redis 配置，零新增部署物
    val recConfig = loadRecConfig()
    val adminService = AdminService(
        userRepository, recConfig.obs, courseRepository,
        assignmentRepository, attendanceRepository, teacherRepository,
        recConfig.redis
    )
    // 附件上传记录：作业提交按 id 回查附件，和上传接口共用一份
    val uploadRepository = com.kewu.modules.upload.repository.UploadRepository()
    val assignmentService = AssignmentService(
        assignmentRepository, courseRepository, teacherRepository, userRepository, uploadRepository
    )
    val attendanceService = AttendanceService(attendanceRepository, courseRepository)
    val teacherService = TeacherService(
        teacherRepository, courseRepository, userRepository,
        assignmentRepository, attendanceRepository
    )
    // 学情雷达：Redis 缓存/锁 + 提问仓储 + 华为云 MaaS 客户端，三者都只建一个实例
    val radarRedis = RadarRedisClient()
    val questionRepository = QuestionRepository()
    val maasClient = HuaweiMaaSClient()
    val radarService = RadarService(radarRedis, questionRepository, maasClient)
    // 课堂提问：复用同一个 Questions 仓储（写），雷达（读）共用一份数据
    val questionService = QuestionService(questionRepository)
    // AI 纪要生成状态轮询：独立 Redis 客户端（要区分“没 key”和“Redis 挂了”两种降级）
    val aiStatusRedis = AiStatusRedis()
    val aiStatusService = AiStatusService(aiStatusRedis)
    // 下课收尾（2026-10-26）：手动下课 → 择优选出本节最佳录音，等待语音转文字
    // 2026-11-04（任务 #14）：删掉「开课满 90 分钟自动下课」循环——用户要求「只要教师端不点击结束课程就一直开启录音」
    val classEnd = com.kewu.recserver.ClassEndService(attributes[com.kewu.recserver.RecServicesKey])
    // 2026-11-04 分段上传拼接服务（教师 finalize 时把段拼回整轨）
    val segmentService = com.kewu.recserver.SegmentService(attributes[com.kewu.recserver.RecServicesKey])
    val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    teacherService.onClassEnded = { st, reason ->
        bg.launch {
            // 下课时各端正在自动停止并上传录音：先按现有录音择优一次，2 分钟后再择优一次（把晚到的上传算进去）
            runCatching { classEnd.onClassEnded(st.courseId, st.startedAt, reason) }.onFailure { println("CLASS-END-ERROR: $it") }
            kotlinx.coroutines.delay(120_000)
            runCatching { classEnd.onClassEnded(st.courseId, st.startedAt, reason) }
            // 第二轮择优（晚到的上传已算进去）后，用主录音走 SIS 转写 + AI 课堂纪要
            val sid = st.courseId + "-" + (st.startedAt?.take(10) ?: java.time.LocalDate.now().toString()).replace("-", "")
            runCatching { classEnd.transcribe(sid) }.onFailure { println("ASR-ERROR: $it") }
        }
    }
    com.kewu.recserver.ClassEndService.onUpload = { sid ->
        bg.launch { runCatching { classEnd.refreshIfDecided(sid) } }
    }
    // 转写卡住自救（2026-11-02 夜）：启动 2 分钟后每 10 分钟扫一次，把卡在 PENDING_ASR /
    // TRANSCRIBING 超 5 分钟的节次重新转写（一次网络抖动不再让整节课永远 10%）
    bg.launch {
        kotlinx.coroutines.delay(120_000)
        while (true) {
            runCatching { classEnd.sweepStuck() }.onFailure { println("SWEEP-ERROR: $it") }
            kotlinx.coroutines.delay(600_000)
        }
    }
    // B站视频一键转课堂笔记（2026-11-01）：复用现有 SIS 转写 / 盘古 MaaS / OBS / Redis，零新增部署物
    val recServices = attributes[com.kewu.recserver.RecServicesKey]
    val bilibiliFetcher = BilibiliFetcher()
    val bilibiliService = BilibiliNoteService(
        fetcher = bilibiliFetcher,
        transcription = recServices.transcription,
        storage = recServices.storage,
        redis = recServices.redis,
        maas = recServices.ai as? MaasAiService ?: MaasAiService()
    )
    // 多路录音并发转写 + 文本大融合（2026-11-03）：复用现有 SIS 转写 / DeepSeek MaaS / Redis，零新增部署物
    val audioFusionService = TranscribeFusionService(
        transcription = recServices.transcription,
        redis = recServices.redis,
        maas = recServices.ai as? MaasAiService ?: MaasAiService()
    )
    routing {
        get("/") {
            call.respondText("kewu backend is running")
        }
        get("/api/health") {
            call.respondText("ok")
        }
        userRoutes(userService)
        courseRoutes(courseService)
        assignmentRoutes(assignmentService)
        attendanceRoutes(attendanceService)
        teacherRoutes(teacherService, assignmentService)
        analyticsRoutes(radarService)
        questionRoutes(questionService)
        aiStatusRoutes(aiStatusService)
        // 原 8081：/health、/api/recordings/*、/api/notes、/api/checkin、/api/sessions、/api/ai/*、
        // /api/teacher/recording/devices*（2026-10-26 合并，路径与 8080 原有接口无重叠）
        recordingModule()
        // 作业附件 /api/uploads + 课前资料 /api/courses/{id}/materials（2026-10-26）
        uploadRoutes(uploadRepository)
        // 课表导入 / 我的课表 / 崩溃上报（2026-10-26）
        scheduleRoutes()
        // 班级通讯录 / 移出学生（2026-10-26）
        rosterRoutes()
        // 某门课历次下课选出的最佳录音（转写状态 PENDING_ASR / DONE…）
        // 手动重新转写（转写失败 / 后来补了更好的录音时用）：POST /api/class-recordings/{sessionId}/transcribe
        post("/api/class-recordings/{sessionId}/transcribe") {
            val sid = call.parameters["sessionId"]!!
            bg.launch { runCatching { classEnd.transcribe(sid, force = true) } }
            call.respond(com.kewu.models.ApiResponse(0, "已开始转写，约几分钟后完成", true))
        }
        // 教师融合录音（2026-11-04，任务 #16，文档六十六节）：
        // 封口会话（晚到的段上传 409 SESSION_SEALED，客户端丢弃本地剩余段）→ 段拼回整轨 → 下课 → 择优 + 立即转写。
        // 用户口径：「点击融合录音按钮就将目前已有的所有录音转文本然后融合交给 AI 助教；没传完的不用再传，这节课算结束」。
        authenticate(com.kewu.security.JwtConfig.NAME) {
            post("/api/class-recordings/{sessionId}/finalize") {
                val uid = call.principal<JWTPrincipal>()?.payload
                    ?.getClaim(com.kewu.security.JwtConfig.CLAIM_USER_ID)?.asString()
                val role = com.kewu.recserver.ClassEndService.roleOf(uid)
                if (role == null || role.equals("student", ignoreCase = true)) {
                    call.respond(com.kewu.models.ApiResponse<String>(1, "仅教师可融合录音"))
                    return@post
                }
                val sid = call.parameters["sessionId"]!!
                val courseId = sid.substringBeforeLast('-')
                val dateStr = sid.substringAfterLast('-').let {
                    if (it.length == 8) "${it.take(4)}-${it.substring(4, 6)}-${it.takeLast(2)}" else null
                }
                // 1. 先封口：晚到的段上传立刻被拒，学生端收到 SESSION_SEALED 后丢弃本地剩余段
                recServices.redis.markSessionSealed(sid)
                // 2. 段拼回整轨并登记（合并失败不阻断：已有整轨录音仍可融合转写）
                val merged = runCatching { segmentService.mergeSession(sid) }
                    .onFailure { println("FINALIZE-MERGE-ERROR $sid: " + it.stackTraceToString()) }
                    .getOrNull()
                // 3. 下课（还没下的话；已下课会返回失败，忽略）——各端 checkClassEnded 自动停录
                runCatching {
                    teacherService.setClassState(
                        com.kewu.modules.teacher.models.ClassStateRequest(courseId = courseId, action = "END", teacherId = uid.orEmpty())
                    )
                }.onFailure { println("FINALIZE-END-WARN $sid: ${it.message}") }
                // 4. 择优 + 立即转写（END 回调 2 分钟后还会再择优一次，兜住这期间晚到的整轨上传）
                bg.launch {
                    runCatching { classEnd.onClassEnded(courseId, dateStr, "TEACHER_FINALIZE") }
                        .onFailure { println("FINALIZE-CLASS-END-ERROR $sid: $it") }
                    runCatching { classEnd.transcribe(sid) }.onFailure { println("FINALIZE-ASR-ERROR $sid: $it") }
                }
                call.respond(
                    com.kewu.models.ApiResponse(
                        0,
                        "已融合：拼回 ${merged?.merged ?: 0} 份录音（${merged?.segments ?: 0} 段），正在转写生成课堂纪要",
                        merged?.merged ?: 0
                    )
                )
            }
        }
        get("/api/courses/{courseId}/class-recordings") {
            call.respond(com.kewu.models.ApiResponse(0, "ok", classEnd.list(call.parameters["courseId"]!!)))
        }
        // 总控端（管理后台）路由组：/api/admin/**（2026-10-28 移植 admin）
        adminRoutes(adminService)
        // B站：视频转笔记 + 按学科视频搜索列表（2026-11-01）
        bilibiliRoutes(bilibiliService, bilibiliFetcher, bg)
        // 多路录音并发转写 + 文本大融合 → DeepSeek 终极笔记（2026-11-03）
        audioFusionRoutes(audioFusionService, bg)
    }
}
