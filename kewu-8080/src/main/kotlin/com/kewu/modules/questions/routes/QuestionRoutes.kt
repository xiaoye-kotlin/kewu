package com.kewu.modules.questions.routes

import com.kewu.models.ApiResponse
import com.kewu.modules.analytics.repository.QuestionRepository
import com.kewu.modules.questions.models.AskQuestionRequest
import com.kewu.modules.questions.service.QuestionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

// 课堂提问接口：学生端提问写入（学情雷达的数据源）+ 提问列表（老师/前端展示）。
// 注意：这个模块和“学情雷达(analytics)”共享 questions 表，但职责分开——
// questions 负责“采集与展示”，analytics 的雷达负责“分析”。
fun Routing.questionRoutes(service: QuestionService) {
    route("/api/questions") {

        // 学生提问：POST /api/questions
        // body: { "courseId":"c101", "content":"可空类型什么时候用?", "studentId":"2023001", "studentName":"张三" }
        post {
            val req = call.receive<AskQuestionRequest>()
            service.ask(req).fold(
                onSuccess = { call.respond(ApiResponse(0, "提问已记录", mapOf("id" to it))) },
                onFailure = { call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(1, it.message ?: "提问失败")) }
            )
        }

        // 提问列表：GET /api/questions?courseId=c101&limit=50（不传 courseId 看全部）
        get {
            val courseId = call.request.queryParameters["courseId"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            service.list(courseId, limit).fold(
                onSuccess = { call.respond(ApiResponse(0, "ok", it)) },
                onFailure = { call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(1, it.message ?: "查询失败")) }
            )
        }
    }
}
