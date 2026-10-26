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
import io.ktor.server.application.*
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
    val assignmentService = AssignmentService(
        assignmentRepository, courseRepository, teacherRepository, userRepository
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
    }
}
