package com.kewu.modules.teacher.routes

import com.kewu.models.ApiResponse
import com.kewu.modules.assignment.service.AssignmentService
import com.kewu.modules.teacher.models.CreateClassRequest
import com.kewu.modules.teacher.models.CreateCourseRequest
import com.kewu.modules.teacher.models.ClassStateRequest
import com.kewu.modules.teacher.models.JoinClassRequest
import com.kewu.modules.teacher.service.TeacherService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

// 教师端接口: 建课 / 建班(带邀请码) / 我教的班 / 学生加班 / 班级名单 / 跨课程待批阅
fun Routing.teacherRoutes(service: TeacherService, assignmentService: AssignmentService) {

    route("/api/teacher") {

        // 教师端视角的待批阅: 跨自己所有课程聚合, 按优先级排好序
        get("pending-review") {
            val teacherId = call.request.queryParameters["teacherId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(1, "缺少 teacherId"))
            assignmentService.pendingReviewOverview(teacherId).fold(
                onSuccess = { call.respond(ApiResponse(0, "ok", it)) },
                onFailure = { call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, it.message ?: "查询失败")) }
            )
        }

        // 控制台聚合: 今日课程 + 待批阅数(红点) + 今日签到率, 一次全给
        get("console") {
            val teacherId = call.request.queryParameters["teacherId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(1, "缺少 teacherId"))
            service.console(teacherId).fold(
                onSuccess = { call.respond(ApiResponse(0, "ok", it)) },
                onFailure = { call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, it.message ?: "老师不存在")) }
            )
        }

        // 开课 / 下课: 把课堂状态写进去
        post("class-state") {
            val req = call.receive<ClassStateRequest>()
            service.setClassState(req).fold(
                onSuccess = {
                    val msg = if (it.state == "ON") "已开课：${it.courseName}" else "已下课：${it.courseName}"
                    call.respond(ApiResponse(0, msg, it))
                },
                onFailure = { call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(1, it.message ?: "操作失败")) }
            )
        }

        // 查某门课当前在不在上课: /api/teacher/class-state?courseId=c101
        get("class-state") {
            val courseId = call.request.queryParameters["courseId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(1, "缺少 courseId"))
            service.getClassState(courseId).fold(
                onSuccess = { call.respond(ApiResponse(0, "ok", it)) },
                onFailure = { call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, it.message ?: "课程不存在")) }
            )
        }

        // 老师新建一门课
        post("courses") {
            val req = call.receive<CreateCourseRequest>()
            service.createCourse(req).fold(
                onSuccess = { call.respond(ApiResponse(0, "课程创建成功", it)) },
                onFailure = { call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(1, it.message ?: "创建失败")) }
            )
        }

        // 老师开一个班, 返回里带邀请码
        post("classes") {
            val req = call.receive<CreateClassRequest>()
            service.createClass(req).fold(
                onSuccess = { call.respond(ApiResponse(0, "班级创建成功, 邀请码 ${it.inviteCode}", it)) },
                onFailure = { call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(1, it.message ?: "创建失败")) }
            )
        }

        // 我教了哪些班哪些课: /api/teacher/classes?teacherId=xxx
        get("classes") {
            val teacherId = call.request.queryParameters["teacherId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(1, "缺少 teacherId"))
            service.overview(teacherId).fold(
                onSuccess = { call.respond(ApiResponse(0, "ok", it)) },
                onFailure = { call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, it.message ?: "老师不存在")) }
            )
        }

        // 某个班的学生名单
        get("classes/{classId}/students") {
            val classId = call.parameters["classId"]!!
            service.classStudents(classId).fold(
                onSuccess = { call.respond(ApiResponse(0, "ok", it)) },
                onFailure = { call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, it.message ?: "班级不存在")) }
            )
        }
    }

    route("/api/classes") {

        // 学生用邀请码加入班级
        post("join") {
            val req = call.receive<JoinClassRequest>()
            service.joinClass(req).fold(
                onSuccess = { call.respond(ApiResponse(0, "已加入《${it.name}》", it)) },
                onFailure = { call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(1, it.message ?: "加入失败")) }
            )
        }
    }
}
