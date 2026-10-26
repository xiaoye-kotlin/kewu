package com.kewu.modules.assignment.routes

import com.kewu.models.ApiResponse
import com.kewu.modules.assignment.models.CreateAssignmentRequest
import com.kewu.modules.assignment.models.ReviewRequest
import com.kewu.modules.assignment.models.SubmitRequest
import com.kewu.modules.assignment.service.AssignmentService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

// 作业模块的接口: 发布 -> 提交 -> 批阅, 外加 AI 预批阅 / 查重 / 智能批阅队列 / 学情画像
fun Routing.assignmentRoutes(service: AssignmentService) {

    route("/api/assignments") {

        // 发布作业(老师)
        post {
            val req = call.receive<CreateAssignmentRequest>()
            service.publish(req).fold(
                onSuccess = { call.respond(ApiResponse(0, "发布成功", it)) },
                onFailure = { call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(1, it.message ?: "发布失败")) }
            )
        }

        // 作业列表, 可按课程过滤: /api/assignments?courseId=c101
        get {
            val courseId = call.request.queryParameters["courseId"]
            call.respond(ApiResponse(0, "ok", service.listAssignments(courseId)))
        }

        // 跨课程待批阅聚合: /api/assignments/pending-review?teacherId=tea001(不传看全部课程)
        get("pending-review") {
            val teacherId = call.request.queryParameters["teacherId"]
            service.pendingReviewOverview(teacherId).fold(
                onSuccess = { call.respond(ApiResponse(0, "ok", it)) },
                onFailure = { call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, it.message ?: "查询失败")) }
            )
        }

        // 作业详情(带统计)
        get("{id}") {
            val id = call.parameters["id"]!!
            service.getDetail(id).fold(
                onSuccess = { call.respond(ApiResponse(0, "ok", it)) },
                onFailure = { call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, it.message ?: "作业不存在")) }
            )
        }

        // 单道作业的统计看板
        get("{id}/stats") {
            val id = call.parameters["id"]!!
            service.stats(id).fold(
                onSuccess = { call.respond(ApiResponse(0, "ok", it)) },
                onFailure = { call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, it.message ?: "作业不存在")) }
            )
        }

        // 提交作业(学生, 可以反复提交, 每次留一个版本)
        post("{id}/submissions") {
            val id = call.parameters["id"]!!
            val req = call.receive<SubmitRequest>()
            service.submit(id, req).fold(
                onSuccess = { call.respond(ApiResponse(0, "提交成功, 当前第 ${it.version} 版", it)) },
                onFailure = { call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(1, it.message ?: "提交失败")) }
            )
        }

        // 某道作业的全部提交(老师看)
        get("{id}/submissions") {
            val id = call.parameters["id"]!!
            service.listSubmissions(id).fold(
                onSuccess = { call.respond(ApiResponse(0, "ok", it)) },
                onFailure = { call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, it.message ?: "作业不存在")) }
            )
        }

        // 学生看自己交的版本轨迹: /api/assignments/{id}/my?studentId=2023001
        get("{id}/my") {
            val id = call.parameters["id"]!!
            val studentId = call.request.queryParameters["studentId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(1, "缺少 studentId"))
            service.mySubmission(id, studentId).fold(
                onSuccess = { call.respond(ApiResponse(0, "ok", it)) },
                onFailure = { call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, it.message ?: "没找到提交记录")) }
            )
        }

        // 相似片段检测(查重)
        get("{id}/similarity") {
            val id = call.parameters["id"]!!
            service.similarityReport(id).fold(
                onSuccess = { call.respond(ApiResponse(0, "ok", it)) },
                onFailure = { call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, it.message ?: "作业不存在")) }
            )
        }

        // 智能批阅队列: 按优先级排出"该先改谁的"
        get("{id}/review-queue") {
            val id = call.parameters["id"]!!
            service.reviewQueue(id).fold(
                onSuccess = { call.respond(ApiResponse(0, "ok", it)) },
                onFailure = { call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, it.message ?: "作业不存在")) }
            )
        }
    }

    route("/api/submissions") {

        // 提交详情(含历史版本)
        get("{id}") {
            val id = call.parameters["id"]!!
            service.getSubmission(id).fold(
                onSuccess = { call.respond(ApiResponse(0, "ok", it)) },
                onFailure = { call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, it.message ?: "提交记录不存在")) }
            )
        }

        // 批阅打分: action 传 RETURN 就是打回重做
        post("{id}/review") {
            val id = call.parameters["id"]!!
            val req = call.receive<ReviewRequest>()
            service.review(id, req).fold(
                onSuccess = {
                    val msg = if (it.status == "RETURNED") "已打回, 等学生修改后重新提交" else "批阅完成"
                    call.respond(ApiResponse(0, msg, it))
                },
                onFailure = { call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(1, it.message ?: "批阅失败")) }
            )
        }

        // AI 预批阅: 先让机器给个建议分和理由, 老师再定
        post("{id}/ai-prereview") {
            val id = call.parameters["id"]!!
            service.aiPreReview(id).fold(
                onSuccess = { call.respond(ApiResponse(0, "AI 预批阅完成", it)) },
                onFailure = { call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, it.message ?: "预批阅失败")) }
            )
        }
    }

    route("/api/analytics") {

        // 一门课的知识点学情画像
        get("courses/{courseId}") {
            val courseId = call.parameters["courseId"]!!
            service.courseAnalytics(courseId).fold(
                onSuccess = { call.respond(ApiResponse(0, "ok", it)) },
                onFailure = { call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, it.message ?: "课程不存在")) }
            )
        }
    }
}
