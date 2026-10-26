package com.kewu.recserver.routes

import com.kewu.recserver.RecServicesKey
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

// AI 模块路由：把转写喂给 AI 拿总结、基于课堂内容做问答。Controller 只收参数返回，逻辑在 AiService
fun Route.aiRoutes() {
    val services = application.attributes[RecServicesKey]

    // 课堂转写总结：POST /api/ai/summarize  { "transcript": "..." } -> { "summary": "..." }
    post("/api/ai/summarize") {
        val req = call.receive<SummarizeRequest>()
        call.respond(mapOf("summary" to services.ai.summarize(req.transcript ?: "")))
    }

    // 课堂问答：POST /api/ai/answer  { "question": "...", "context": "..." } -> { "answer": "..." }
    post("/api/ai/answer") {
        val req = call.receive<AnswerRequest>()
        call.respond(mapOf("answer" to services.ai.answer(req.question ?: "", req.context ?: "", (req.maxTokens ?: 768).coerceIn(256, 8192))))
    }
}

@Serializable
private data class SummarizeRequest(val transcript: String = "")

@Serializable
private data class AnswerRequest(val question: String = "", val context: String = "", val maxTokens: Int? = null)
