package com.kewu.modules.admin.routes

import com.kewu.models.ApiResponse
import com.kewu.modules.admin.models.AdminClassStudentRequest
import com.kewu.modules.admin.models.AdminCreateUserRequest
import com.kewu.modules.admin.models.AdminResetPasswordRequest
import com.kewu.modules.admin.models.AdminUpdateClassRequest
import com.kewu.modules.admin.models.AdminUpdateCourseRequest
import com.kewu.modules.admin.models.AdminUpdateUserRequest
import com.kewu.modules.admin.models.PagedData
import com.kewu.modules.admin.service.AdminService
import com.kewu.modules.assignment.models.ReviewRequest
import com.kewu.modules.teacher.models.CreateClassRequest
import com.kewu.modules.teacher.models.CreateCourseRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.io.File
import java.lang.Runtime
import java.time.Instant

// 鉴权拦截：未过鉴权直接 401（本函数内部已 respond），不影响 8080 其它接口。
// 调用方用 `if (call.checkAdmin(service)) { ... }` 包裹，鉴权失败时不进业务分支即可。
private suspend fun ApplicationCall.checkAdmin(service: AdminService): Boolean {
    if (service.isAdmin(this)) return true
    respond(HttpStatusCode.Unauthorized, ApiResponse<Unit>(401, "未授权：需要管理员凭证（Bearer 管理员Token 或 X-Admin-Key）"))
    return false
}

// 总控端路由组：挂载在 /api/admin 下。所有写操作经 checkAdmin 双保险鉴权。
fun Routing.adminRoutes(service: AdminService) {
    route("/api/admin") {

        // ---------- 概览 / 系统（§5.1） ----------
        get("/overview") {
            if (call.checkAdmin(service)) {
                call.respond(ApiResponse(0, "ok", service.overview()))
            }
        }

        get("/system/health") {
            if (call.checkAdmin(service)) {
                // 整个探测用 runCatching 兜底：任何异常都降级为 down + 暴露 error，绝不 500
                val health = runCatching { service.systemHealth() }.getOrElse { e ->
                    mapOf(
                        "backend" to "up",
                        "redis" to "down",
                        "obs" to "down",
                        "error" to (e.message ?: e.javaClass.simpleName),
                        "time" to Instant.now().toString()
                    )
                }
                call.respond(ApiResponse(0, "ok", health))
            }
        }

        // ---------- 用户管理（§5.3） ----------
        get("/users") {
            if (call.checkAdmin(service)) {
                val qp = call.request.queryParameters
                val page = qp["page"]?.toIntOrNull() ?: 1
                val size = qp["size"]?.toIntOrNull() ?: 20
                val (items, total) = service.listUsers(page, size, qp["role"], qp["keyword"], qp["status"])
                call.respond(ApiResponse(0, "ok", PagedData(items, total, page, size)))
            }
        }

        get("/users/{id}") {
            if (call.checkAdmin(service)) {
                val id = call.parameters["id"] ?: ""
                val u = service.getUser(id)
                if (u == null) call.respond(HttpStatusCode.NotFound, ApiResponse<Unit>(404, "用户不存在"))
                else call.respond(ApiResponse(0, "ok", u))
            }
        }

        post("/users") {
            if (call.checkAdmin(service)) {
                val req = call.receive<AdminCreateUserRequest>()
                runCatching { service.createUser(req) }.fold(
                    onSuccess = { call.respond(HttpStatusCode.Created, ApiResponse(0, "ok", it)) },
                    onFailure = { call.respond(HttpStatusCode.BadRequest, ApiResponse<Unit>(400, it.message ?: "创建失败")) }
                )
            }
        }

        put("/users/{id}") {
            if (call.checkAdmin(service)) {
                val id = call.parameters["id"] ?: ""
                val req = call.receive<AdminUpdateUserRequest>()
                if (service.updateUser(id, req)) call.respond(ApiResponse(0, "ok", null))
                else call.respond(HttpStatusCode.NotFound, ApiResponse<Unit>(404, "用户不存在"))
            }
        }

        post("/users/{id}/reset-password") {
            if (call.checkAdmin(service)) {
                val id = call.parameters["id"] ?: ""
                val req = call.receive<AdminResetPasswordRequest>()
                runCatching { service.resetPassword(id, req.newPassword) }.fold(
                    onSuccess = { ok ->
                        if (ok) call.respond(ApiResponse(0, "ok", null))
                        else call.respond(HttpStatusCode.NotFound, ApiResponse<Unit>(404, "用户不存在"))
                    },
                    onFailure = { call.respond(HttpStatusCode.BadRequest, ApiResponse<Unit>(400, it.message ?: "重置失败")) }
                )
            }
        }

        post("/users/{id}/disable") {
            if (call.checkAdmin(service)) {
                val id = call.parameters["id"] ?: ""
                if (service.setStatus(id, "disabled")) call.respond(ApiResponse(0, "ok", null))
                else call.respond(HttpStatusCode.NotFound, ApiResponse<Unit>(404, "用户不存在"))
            }
        }

        post("/users/{id}/enable") {
            if (call.checkAdmin(service)) {
                val id = call.parameters["id"] ?: ""
                if (service.setStatus(id, "active")) call.respond(ApiResponse(0, "ok", null))
                else call.respond(HttpStatusCode.NotFound, ApiResponse<Unit>(404, "用户不存在"))
            }
        }

        delete("/users/{id}") {
            if (call.checkAdmin(service)) {
                val id = call.parameters["id"] ?: ""
                if (service.deleteUser(id)) call.respond(ApiResponse(0, "ok", null))
                else call.respond(HttpStatusCode.NotFound, ApiResponse<Unit>(404, "用户不存在"))
            }
        }

        // ---------- 课程与班级（§5.4） ----------
        get("/courses") {
            if (call.checkAdmin(service)) {
                call.respond(ApiResponse(0, "ok", service.listCourses()))
            }
        }

        post("/courses") {
            if (call.checkAdmin(service)) {
                val req = call.receive<CreateCourseRequest>()
                runCatching { service.createCourse(req) }.fold(
                    onSuccess = { call.respond(HttpStatusCode.Created, ApiResponse(0, "ok", it)) },
                    onFailure = { call.respond(HttpStatusCode.BadRequest, ApiResponse<Unit>(400, it.message ?: "创建失败")) }
                )
            }
        }

        put("/courses/{id}") {
            if (call.checkAdmin(service)) {
                val id = call.parameters["id"] ?: ""
                val req = call.receive<AdminUpdateCourseRequest>()
                if (service.updateCourse(id, req)) call.respond(ApiResponse(0, "ok", null))
                else call.respond(HttpStatusCode.NotFound, ApiResponse<Unit>(404, "课程不存在"))
            }
        }

        delete("/courses/{id}") {
            if (call.checkAdmin(service)) {
                val id = call.parameters["id"] ?: ""
                if (service.deleteCourse(id)) call.respond(ApiResponse(0, "ok", null))
                else call.respond(HttpStatusCode.NotFound, ApiResponse<Unit>(404, "课程不存在"))
            }
        }

        get("/courses/{id}/classes") {
            if (call.checkAdmin(service)) {
                val id = call.parameters["id"] ?: ""
                call.respond(ApiResponse(0, "ok", service.listClasses(id)))
            }
        }

        post("/classes") {
            if (call.checkAdmin(service)) {
                val req = call.receive<CreateClassRequest>()
                runCatching { service.createClass(req) }.fold(
                    onSuccess = { call.respond(HttpStatusCode.Created, ApiResponse(0, "ok", it)) },
                    onFailure = { call.respond(HttpStatusCode.BadRequest, ApiResponse<Unit>(400, it.message ?: "创建失败")) }
                )
            }
        }

        put("/classes/{id}") {
            if (call.checkAdmin(service)) {
                val id = call.parameters["id"] ?: ""
                val req = call.receive<AdminUpdateClassRequest>()
                if (service.updateClass(id, req)) call.respond(ApiResponse(0, "ok", null))
                else call.respond(HttpStatusCode.NotFound, ApiResponse<Unit>(404, "班级不存在"))
            }
        }

        delete("/classes/{id}") {
            if (call.checkAdmin(service)) {
                val id = call.parameters["id"] ?: ""
                if (service.deleteClass(id)) call.respond(ApiResponse(0, "ok", null))
                else call.respond(HttpStatusCode.NotFound, ApiResponse<Unit>(404, "班级不存在"))
            }
        }

        get("/classes/{id}/students") {
            if (call.checkAdmin(service)) {
                val id = call.parameters["id"] ?: ""
                call.respond(ApiResponse(0, "ok", service.listClassStudents(id)))
            }
        }

        post("/classes/{id}/students") {
            if (call.checkAdmin(service)) {
                val id = call.parameters["id"] ?: ""
                val req = call.receive<AdminClassStudentRequest>()
                if (service.addStudentToClass(id, req.studentId)) call.respond(ApiResponse(0, "ok", null))
                else call.respond(HttpStatusCode.BadRequest, ApiResponse<Unit>(400, "班级不存在或学生不存在"))
            }
        }

        delete("/classes/{id}/students/{studentId}") {
            if (call.checkAdmin(service)) {
                val id = call.parameters["id"] ?: ""
                val sid = call.parameters["studentId"] ?: ""
                if (service.removeStudentFromClass(id, sid)) call.respond(ApiResponse(0, "ok", null))
                else call.respond(HttpStatusCode.NotFound, ApiResponse<Unit>(404, "记录不存在"))
            }
        }

        // ---------- 作业与批阅（§5.5） ----------
        get("/assignments") {
            if (call.checkAdmin(service)) {
                val qp = call.request.queryParameters
                call.respond(ApiResponse(0, "ok", service.listAssignments(qp["courseId"], qp["from"], qp["to"], qp["keyword"])))
            }
        }

        get("/assignments/{id}") {
            if (call.checkAdmin(service)) {
                val id = call.parameters["id"] ?: ""
                val d = service.getAssignment(id)
                if (d == null) call.respond(HttpStatusCode.NotFound, ApiResponse<Unit>(404, "作业不存在"))
                else call.respond(ApiResponse(0, "ok", d))
            }
        }

        delete("/assignments/{id}") {
            if (call.checkAdmin(service)) {
                val id = call.parameters["id"] ?: ""
                if (service.deleteAssignment(id)) call.respond(ApiResponse(0, "ok", null))
                else call.respond(HttpStatusCode.NotFound, ApiResponse<Unit>(404, "作业不存在"))
            }
        }

        get("/assignments/{id}/submissions") {
            if (call.checkAdmin(service)) {
                val id = call.parameters["id"] ?: ""
                call.respond(ApiResponse(0, "ok", service.listSubmissions(id)))
            }
        }

        put("/submissions/{id}/review") {
            if (call.checkAdmin(service)) {
                val id = call.parameters["id"] ?: ""
                val req = call.receive<ReviewRequest>()
                if (service.reviewSubmission(id, req)) call.respond(ApiResponse(0, "ok", null))
                else call.respond(HttpStatusCode.NotFound, ApiResponse<Unit>(404, "提交不存在"))
            }
        }

        // ---------- 签到（§5.6） ----------
        get("/attendance") {
            if (call.checkAdmin(service)) {
                val qp = call.request.queryParameters
                call.respond(ApiResponse(0, "ok", service.listAttendanceSessions(qp["courseId"], qp["from"], qp["to"])))
            }
        }

        get("/attendance/{id}") {
            if (call.checkAdmin(service)) {
                val id = call.parameters["id"] ?: ""
                val d = service.getAttendanceSession(id)
                if (d == null) call.respond(HttpStatusCode.NotFound, ApiResponse<Unit>(404, "签到会话不存在"))
                else call.respond(ApiResponse(0, "ok", d))
            }
        }

        get("/attendance/{id}/export") {
            if (call.checkAdmin(service)) {
                val id = call.parameters["id"] ?: ""
                val csv = service.attendanceCsv(id)
                if (csv.isBlank()) call.respond(HttpStatusCode.NotFound, ApiResponse<Unit>(404, "签到会话不存在"))
                else call.respondText(csv, contentType = ContentType.Text.CSV, status = HttpStatusCode.OK)
            }
        }

        // ---------- 录音管理（§5.2） ----------
        get("/recordings") {
            if (call.checkAdmin(service)) {
                val qp = call.request.queryParameters
                val page = qp["page"]?.toIntOrNull() ?: 1
                val size = qp["size"]?.toIntOrNull() ?: 20
                val (items, total) = service.listRecordings(
                    page, size, qp["classId"], qp["courseId"], qp["studentId"],
                    qp["role"], qp["from"], qp["to"], qp["keyword"]
                )
                call.respond(ApiResponse(0, "ok", PagedData(items, total, page, size)))
            }
        }

        get("/recordings/{id}") {
            if (call.checkAdmin(service)) {
                val id = call.parameters["id"] ?: ""
                val d = service.getRecording(id)
                if (d == null) call.respond(HttpStatusCode.NotFound, ApiResponse<Unit>(404, "录音不存在"))
                else call.respond(ApiResponse(0, "ok", d))
            }
        }

        get("/recordings/{id}/download") {
            if (call.checkAdmin(service)) {
                val id = call.parameters["id"] ?: ""
                val d = service.getRecording(id)
                if (d == null) call.respond(HttpStatusCode.NotFound, ApiResponse<Unit>(404, "录音不存在"))
                else call.respond(ApiResponse(0, "ok", mapOf("url" to d.downloadUrl)))
            }
        }

        delete("/recordings/{id}") {
            if (call.checkAdmin(service)) {
                val id = call.parameters["id"] ?: ""
                if (service.deleteRecording(id)) call.respond(ApiResponse(0, "ok", null))
                else call.respond(HttpStatusCode.NotFound, ApiResponse<Unit>(404, "录音不存在"))
            }
        }

        // 融合会话列表/详情：融合接口尚未把会话落 RecordingSessions 表，目前返回空（表结构已就绪，落库后自动有数据）
        get("/sessions") {
            if (call.checkAdmin(service)) {
                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 20
                call.respond(ApiResponse(0, "ok", PagedData(emptyList<Map<String, String>>(), 0, page, size)))
            }
        }

        get("/sessions/{id}") {
            if (call.checkAdmin(service)) {
                call.respond(HttpStatusCode.NotFound, ApiResponse<Unit>(404, "融合会话尚未落库"))
            }
        }
    }
}
