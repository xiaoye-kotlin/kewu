package com.kewu.recserver.routes

import com.kewu.recserver.RecServicesKey
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.close
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
        // 2026-11-03 助教发图：消息里的 OBS 图片链接由服务端取回转成 base64 data URL 交给视觉模型
        //（模型侧不一定能访问外链；只取自家 OBS / data URL，防止被当成任意抓取代理）
        val images = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            req.messages.withIndex().filter { it.value.images.isNotEmpty() }.associate { (i, m) ->
                i to m.images.take(MAX_IMAGES_PER_MSG).mapNotNull { ChatMedia.toDataUrl(it) }
            }.filterValues { it.isNotEmpty() }
        }
        val lostImages = req.messages.sumOf { it.images.take(MAX_IMAGES_PER_MSG).size } - images.values.sumOf { it.size }
        call.response.header("X-Accel-Buffering", "no")
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            if (maas == null || req.messages.isEmpty()) {
                write("data: {\"error\":\"AI 未配置\"}\n\n"); flush(); return@respondTextWriter
            }
            if (lostImages > 0) {
                write("data: {\"error\":\"图片读取失败，请重新发送\"}\n\n"); write("data: [DONE]\n\n"); flush(); return@respondTextWriter
            }
            val err = maas.stream(
                req.messages.map { it.role to it.content },
                (req.maxTokens ?: 4096).coerceIn(256, 8192),
                req.thinking,
                images,
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

    // 录音转文本（华为云 SIS 录音文件识别，队友交付 2026-10-26）：
    // POST /api/ai/transcribe {"audioUrl":"OBS 音频 URL","audioFormat":"wav"} -> {"text":"转写全文"}
    // 异步长任务：提交 job → 每 10s 轮询，最长约 10 分钟才返回；失败返回 502 + {"error"}
    post("/api/ai/transcribe") {
        val req = call.receive<TranscribeRequest>()
        if (req.audioUrl.isBlank()) {
            call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "audioUrl 必填（传 OBS 上的录音文件 URL）"))
            return@post
        }
        try {
            call.respond(mapOf("text" to services.transcription.transcribe(req.audioUrl, req.audioFormat.ifBlank { "wav" })))
        } catch (e: com.kewu.recserver.services.TranscriptionException) {
            call.respond(io.ktor.http.HttpStatusCode.BadGateway, mapOf("error" to (e.message ?: "转写失败，请重试")))
        }
    }

    // 助教发图（2026-11-03）：multipart「file」→ 服务端缩到长边 1600 的 JPEG → OBS chat/img/… → {"url"}
    post("/api/ai/chat-image") {
        val file = ChatMedia.receiveFile(call, "file", MAX_IMAGE_BYTES)
        if (file == null) {
            call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "没有收到图片或图片超过 15MB"))
            return@post
        }
        val jpeg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { ChatMedia.normalizeImage(file.second) }.getOrNull()
        }
        if (jpeg == null) {
            call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "图片格式不支持，请换 JPG / PNG"))
            return@post
        }
        try {
            val url = services.storage.saveObject("chat/img/${ChatMedia.day()}/${java.util.UUID.randomUUID()}.${jpeg.second}", jpeg.first, "image/${jpeg.second.replace("jpg", "jpeg")}")
            call.respond(mapOf("url" to url))
        } catch (t: Throwable) {
            println("CHAT-IMAGE-ERROR: $t")
            call.respond(io.ktor.http.HttpStatusCode.BadGateway, mapOf("error" to "图片上传失败，请重试"))
        }
    }

    // 助教发语音（2026-11-03）：multipart「file」(WAV) → 存 OBS chat/voice/… → 华为 SIS 识别 → {"url","text"}
    // 识别失败时 url 照常返回、text 为空并带 error，客户端据此提示「没听清」
    post("/api/ai/voice") {
        val file = ChatMedia.receiveFile(call, "file", MAX_VOICE_BYTES)
        if (file == null || file.second.size < 1_000) {
            call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "没有收到语音或语音太长"))
            return@post
        }
        val url = try {
            services.storage.saveObject("chat/voice/${ChatMedia.day()}/${java.util.UUID.randomUUID()}.wav", file.second, "audio/wav")
        } catch (t: Throwable) {
            println("CHAT-VOICE-OBS-ERROR: $t")
            call.respond(io.ktor.http.HttpStatusCode.BadGateway, mapOf("error" to "语音上传失败，请重试"))
            return@post
        }
        try {
            val text = services.transcription.transcribeChatVoice(url, file.second)
            call.respond(mapOf("url" to url, "text" to text))
        } catch (t: Throwable) {
            println("CHAT-VOICE-SIS-ERROR: ${t.message}")
            call.respond(mapOf("url" to url, "text" to "", "error" to "语音识别失败"))
        }
    }

    // 语音输入实时识别（2026-11-03）：WebSocket，App 发 PCM16/16k 单声道二进制帧、说完发 "END"，
    // 服务端中转华为云 SIS 实时识别，回 {"type":"text","text":已识别全文} … {"type":"done"}（协议见 SisRealtimeService）。
    // 鉴权：Authorization: Bearer <登录 token>（或 ?token=），防止被当成免费语音接口
    webSocket("/api/ai/voice/stream") {
        val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
            ?: call.request.queryParameters["token"]
        if (token.isNullOrBlank() || com.kewu.security.JwtConfig.verifyToken(token) == null) {
            send(io.ktor.websocket.Frame.Text("""{"type":"error","message":"请先登录"}"""))
            close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return@webSocket
        }
        com.kewu.recserver.services.SisRealtimeService.relay(this)
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
private data class TranscribeRequest(val audioUrl: String = "", val audioFormat: String = "wav")

@Serializable
private data class StreamMessage(
    val role: String = "user",
    val content: String = "",
    /** 这条消息附带的图片：自家 OBS 链接或 data URL（2026-11-03）。 */
    val images: List<String> = emptyList(),
)

private const val MAX_IMAGES_PER_MSG = 4
private const val MAX_IMAGE_BYTES = 15L * 1024 * 1024
private const val MAX_VOICE_BYTES = 8L * 1024 * 1024

@Serializable
private data class StreamRequest(
    val messages: List<StreamMessage> = emptyList(),
    val maxTokens: Int? = null,
    // false = 关闭模型思维链（对话场景，首字快）；true = 保留（出题等需要推理的场景）
    val thinking: Boolean = false,
)

@Serializable
private data class AnswerRequest(val question: String = "", val context: String = "", val maxTokens: Int? = null)
