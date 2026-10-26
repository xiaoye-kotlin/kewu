package com.kewu.modules.attendance.routes

import com.kewu.models.ApiResponse
import com.kewu.modules.attendance.models.CheckInRequest
import com.kewu.modules.attendance.models.CreateAttendanceRequest
import com.kewu.modules.attendance.service.AttendanceService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

// 签到模块的接口: 发起签到 / 动态码 / 学生签到 / 记录 / 统计 / 风控 / 实时看板
fun Routing.attendanceRoutes(service: AttendanceService) {

    route("/api/attendance") {

        // 发起签到(老师)
        post {
            val req = call.receive<CreateAttendanceRequest>()
            service.create(req).fold(
                onSuccess = { call.respond(ApiResponse(0, "签到已发起", it)) },
                onFailure = { call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(1, it.message ?: "发起失败")) }
            )
        }

        // 签到列表, 可按课程过滤: /api/attendance?courseId=c101
        get {
            val courseId = call.request.queryParameters["courseId"]
            call.respond(ApiResponse(0, "ok", service.listSessions(courseId)))
        }

        // 当前有效的动态签到码(前端拿它生成二维码)
        get("{id}/code") {
            val id = call.parameters["id"]!!
            service.currentCode(id).fold(
                onSuccess = { call.respond(ApiResponse(0, "ok", it)) },
                onFailure = { call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(1, it.message ?: "取码失败")) }
            )
        }

        // 学生签到
        post("{id}/check-in") {
            val id = call.parameters["id"]!!
            val req = call.receive<CheckInRequest>()
            service.checkIn(id, req).fold(
                onSuccess = {
                    val extra = if (it.status == "LATE") " (迟到)" else ""
                    call.respond(ApiResponse(0, "签到成功$extra", it))
                },
                onFailure = { call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(1, it.message ?: "签到失败")) }
            )
        }

        // 签到记录(老师看)
        get("{id}/records") {
            val id = call.parameters["id"]!!
            service.records(id).fold(
                onSuccess = { call.respond(ApiResponse(0, "ok", it)) },
                onFailure = { call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, it.message ?: "签到不存在")) }
            )
        }

        // 学生看自己: /api/attendance/{id}/my?studentId=2023001
        get("{id}/my") {
            val id = call.parameters["id"]!!
            val studentId = call.request.queryParameters["studentId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(1, "缺少 studentId"))
            service.my(id, studentId).fold(
                onSuccess = { call.respond(ApiResponse(0, "ok", it)) },
                onFailure = { call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, it.message ?: "没签到记录")) }
            )
        }

        // 结束签到
        post("{id}/close") {
            val id = call.parameters["id"]!!
            service.close(id).fold(
                onSuccess = { call.respond(ApiResponse(0, "签到已结束", it)) },
                onFailure = { call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, it.message ?: "签到不存在")) }
            )
        }

        // 统计看板
        get("{id}/stats") {
            val id = call.parameters["id"]!!
            service.stats(id).fold(
                onSuccess = { call.respond(ApiResponse(0, "ok", it)) },
                onFailure = { call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, it.message ?: "签到不存在")) }
            )
        }

        // 疑似代签名单
        get("{id}/risk") {
            val id = call.parameters["id"]!!
            service.riskList(id).fold(
                onSuccess = { call.respond(ApiResponse(0, "ok", it)) },
                onFailure = { call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, it.message ?: "签到不存在")) }
            )
        }

        // 实时看板
        get("{id}/realtime") {
            val id = call.parameters["id"]!!
            service.realtime(id).fold(
                onSuccess = { call.respond(ApiResponse(0, "ok", it)) },
                onFailure = { call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, it.message ?: "签到不存在")) }
            )
        }

        // 场次详情(含统计)
        get("{id}") {
            val id = call.parameters["id"]!!
            service.detail(id).fold(
                onSuccess = { call.respond(ApiResponse(0, "ok", it)) },
                onFailure = { call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, it.message ?: "签到不存在")) }
            )
        }
    }
}
