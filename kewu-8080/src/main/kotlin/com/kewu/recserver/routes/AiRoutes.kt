package com.kewu.recserver.routes

import com.kewu.recserver.RecServicesKey
import io.ktor.http.ContentType
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respondTextWriter
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

// AI 模块路由：把转写喂给 AI 拿总结、基于课堂内容做问答。Controller 只收参数返回，逻辑在 AiService
fun Route.aiRoutes() {
    val services = application.attributes[RecServicesKey]

    // 流式对话：POST /api/ai/stream  { "messages":[{role,content}], "maxTokens":4096 }
    // -> text/event-stream：data: {"d":"片段"} ... 结束 data: [DONE]；失败 data: {"error":"原因"}
    post("/api/ai/stream") {
        val req = call.receive<StreamRequest>()
        val maas = services.ai as? com.kewu.recserver.services.ai.MaasAiService
        call.response.header("X-Accel-Buffering", "no")
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            if (maas == null || req.messages.isEmpty()) {
                write("data: {\"error\":\"AI 未配置\"}\n\n"); flush(); return@respondTextWriter
            }
            val err = maas.stream(
                req.messages.map { it.role to it.content },
                (req.maxTokens ?: 4096).coerceIn(256, 8192),
            ) { piece ->
                write("data: " + kotlinx.serialization.json.buildJsonObject {
                    put("d", kotlinx.serialization.json.JsonPrimitive(piece))
                }.toString() + "\n\n")
                flush()
            }
            if (err != null) {
                write("data: " + kotlinx.serialization.json.buildJsonObject {
                    put("error", kotlinx.serialization.json.JsonPrimitive(err))
                }.toString() + "\n\n")
            }
            write("data: [DONE]\n\n")
            flush()
        }
    }

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
private data class StreamMessage(val role: String = "user", val content: String = "")

@Serializable
private data class StreamRequest(val messages: List<StreamMessage> = emptyList(), val maxTokens: Int? = null)

@Serializable
private data class AnswerRequest(val question: String = "", val context: String = "", val maxTokens: Int? = null)
