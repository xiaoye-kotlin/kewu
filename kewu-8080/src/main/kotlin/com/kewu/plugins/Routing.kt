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
    // 下课收尾（2026-10-26）：手动下课 / 开课满 90 分钟自动下课 → 择优选出本节最佳录音，等待语音转文字
    val classEnd = com.kewu.recserver.ClassEndService(attributes[com.kewu.recserver.RecServicesKey])
    val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    teacherService.onClassEnded = { st, reason ->
        bg.launch {
            // 下课时各端正在自动停止并上传录音：先按现有录音择优一次，2 分钟后再择优一次（把晚到的上传算进去）
            runCatching { classEnd.onClassEnded(st.courseId, st.startedAt, reason) }.onFailure { println("CLASS-END-ERROR: $it") }
            kotlinx.coroutines.delay(120_000)
            runCatching { classEnd.onClassEnded(st.courseId, st.startedAt, reason) }
        }
    }
    com.kewu.recserver.ClassEndService.onUpload = { sid ->
        bg.launch { runCatching { classEnd.refreshIfDecided(sid) } }
    }
    bg.launch {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            runCatching { teacherService.autoEndOverdue(90) }
                .onSuccess { if (it > 0) println("AUTO-END: $it class(es) ended after 90 min") }
                .onFailure { println("AUTO-END-ERROR: $it") }
        }
    }
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
        get("/api/courses/{courseId}/class-recordings") {
            call.respond(com.kewu.models.ApiResponse(0, "ok", classEnd.list(call.parameters["courseId"]!!)))
        }
    }
}
