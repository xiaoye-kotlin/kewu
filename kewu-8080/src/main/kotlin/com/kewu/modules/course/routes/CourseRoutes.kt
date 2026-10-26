package com.kewu.modules.course.routes

import com.kewu.models.ApiResponse
import com.kewu.modules.course.service.CourseService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.route

// 课程模块的接口: 课程列表、课程详情、课堂纪要查询
fun Routing.courseRoutes(courseService: CourseService) {
    route("/api/courses") {
        // 课程列表
        get {
            call.respond(ApiResponse(0, "ok", courseService.listCourses()))
        }

        // 单门课程详情
        get("{courseId}") {
            val courseId = call.parameters["courseId"]!!
            courseService.getCourse(courseId).fold(
                onSuccess = { call.respond(ApiResponse(0, "ok", it)) },
                onFailure = { call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, it.message ?: "课程不存在")) }
            )
        }

        // 某门课的课堂纪要列表
        get("{courseId}/notes") {
            val courseId = call.parameters["courseId"]!!
            courseService.listNotes(courseId).fold(
                onSuccess = { call.respond(ApiResponse(0, "ok", it)) },
                onFailure = { call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, it.message ?: "查询失败")) }
            )
        }

        // 某门课的单条课堂纪要
        get("{courseId}/notes/{noteId}") {
            val courseId = call.parameters["courseId"]!!
            val noteId = call.parameters["noteId"]!!
            courseService.getNote(courseId, noteId).fold(
                onSuccess = { call.respond(ApiResponse(0, "ok", it)) },
                onFailure = { call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, it.message ?: "纪要不存在")) }
            )
        }
    }
}
