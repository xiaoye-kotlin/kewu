package com.kewu.modules.voicechat

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * POST /api/ai/voice-chat —— 发一段语音，回一段 AI 语音。
 *
 * 请求 multipart/form-data：audio（m4a / wav / mp3，≤2MB，≈60 秒以内）、sessionId（会话 ID）。
 * 成功：200 + audio/mpeg 二进制流；字幕放 X-AI-Text（中文用 Base64，见 X-AI-Text-Encoding）。
 * 降级：TTS 也不可用时返回 200 + text/plain 纯文本并带 X-AI-Fallback: 1，绝不返回 500。
 *
 * 内存控制：不用 part.provider().toByteArray() 那种一次性无上限读取；这里用 16KB 缓冲区
 * 流式读，边读边写临时缓存文件，累计超过 2MB 立刻停，全程最多占 2MB 内存。
 */
fun Route.voiceChatRoutes(service: VoiceChatService) {
    val log = LoggerFactory.getLogger("VoiceChatRoutes")

    post("/api/ai/voice-chat") {
        var upload: Upload? = null
        // 先把结果算完再统一响应，保证 respond 只调用一次（异常也不会出现二次响应）
        val outcome: Outcome = try {
            upload = readUpload(call)
            val sid = upload?.sessionId.orEmpty()
            val bytes = upload?.audio
            when {
                upload == null || upload.tooLarge ->
                    Outcome.Bad("没有收到 audio 文件，或音频超过 2MB（请录 60 秒以内）")
                sid.isBlank() -> Outcome.Bad("缺少 sessionId（只允许字母、数字、-、_）")
                bytes == null || bytes.size < 512 -> Outcome.Bad("音频为空或太短，请重新录音")
                // 全链路总超时：SIS / DeepSeek / TTS 任一环节卡住，35 秒后也必须给出答复，
                // 绝不让 App 那一头无限转圈
                else -> {
                    val reply = withTimeoutOrNull(35_000) { service.chat(sid, bytes) }
                    if (reply == null) Outcome.Error else Outcome.Ok(reply)
                }
            }
        } catch (t: Throwable) {
            log.error("voice-chat 未捕获异常：${t.javaClass.simpleName} ${t.message.orEmpty().take(200)}")
            // 再兜一层：超大请求把 multipart 解析打断时，仍按「参数过大」给 400，
            // 不要变成 200 纯文本「服务正忙」，否则前端没法判断该提示"录音太长"。
            val len = call.request.headers[io.ktor.http.HttpHeaders.ContentLength]?.toLongOrNull()
            if (len != null && len > MAX_AUDIO_BYTES + 64 * 1024) {
                Outcome.Bad("音频超过 2MB（请录 60 秒以内）")
            } else Outcome.Error
        }

        try {
            when (outcome) {
                is Outcome.Bad -> call.respondText(
                    "{\"error\":\"${outcome.msg}\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                is Outcome.Error -> {
                    // 超时/未知异常也照常给字幕，前端不用猜发生了什么
                    val (v, enc) = encodeHeader(VoiceChatService.BUSY)
                    call.response.header("X-AI-Text", v)
                    call.response.header("X-AI-Text-Encoding", enc)
                    call.response.header("X-AI-Stage", "timeout")
                    call.response.header("X-AI-Fallback", "1")
                    // contentType 留空：Ktor 自动给 text/plain; charset=UTF-8，中文不会乱码
                    call.respondText(text = VoiceChatService.BUSY, status = HttpStatusCode.OK)
                }
                is Outcome.Ok -> {
                    val r = outcome.reply
                    // HTTP header 只能放 ASCII，中文直接塞会被 Netty 拒掉；纯 ASCII 时保持明文，
                    // 含中文时用 URL-safe Base64，并附 X-AI-Text-Encoding 告诉前端怎么解
                    val (textValue, textEnc) = encodeHeader(r.text)
                    val (userValue, userEnc) = encodeHeader(r.userText)
                    call.response.header("X-AI-Text", textValue)
                    call.response.header("X-AI-Text-Encoding", textEnc)
                    call.response.header("X-AI-User-Text", userValue)
                    call.response.header("X-AI-User-Text-Encoding", userEnc)
                    call.response.header("X-AI-Stage", r.stage)
                    call.response.header("Cache-Control", "no-store")
                    val mp3 = r.audio
                    if (mp3 != null) {
                        call.respondBytes(mp3, ContentType.Audio.MPEG)
                    } else {
                        // TTS 挂了：按需求返回包含错误信息的纯文本，前端看到 X-AI-Fallback 就只显示字幕
                        call.response.header("X-AI-Fallback", "1")
                        call.respondText(text = r.text, status = HttpStatusCode.OK)
                    }
                }
            }
        } finally {
            upload?.cleanup()   // 删掉临时缓存文件，别在服务器 temp 目录堆垃圾
        }
    }
}

private const val MAX_AUDIO_BYTES = 2 * 1024 * 1024   // 2MB：16k 单声道 WAV 约 32KB/秒，60 秒 ≈ 1.9MB

private class Upload(
    val sessionId: String,
    val audio: ByteArray?,
    val fileName: String,
    val tempFile: Path?,
    val tooLarge: Boolean,
) {
    fun cleanup() {
        tempFile?.let { runCatching { Files.deleteIfExists(it) } }
    }
}

private sealed class Outcome {
    data class Ok(val reply: VoiceChatService.VoiceReply) : Outcome()
    data class Bad(val msg: String) : Outcome()
    data object Error : Outcome()
}

/**
 * 流式收 multipart：边读边写临时缓存文件，同时累计到内存（上限 2MB）。
 * 不用 toByteArray()，超大文件不会把 1.9GB 内存的服务器撑爆。
 */
private suspend fun readUpload(call: ApplicationCall): Upload? = withContext(Dispatchers.IO) {
    var sessionId = ""
    var audio: ByteArray? = null
    var fileName = ""
    var tooLarge = false
    var tempFile: Path? = null

    val ok = runCatching {
        // formFieldLimit 给普通字段留一点余量，真正的硬上限在下面逐块累计判断
        call.receiveMultipart(formFieldLimit = (MAX_AUDIO_BYTES + 64 * 1024).toLong()).forEachPart { part ->
            when (part) {
                is PartData.FormItem -> if (part.name == "sessionId") sessionId = part.value
                is PartData.FileItem -> if (part.name == "audio" && audio == null) {
                    fileName = part.originalFileName.orEmpty()
                    val channel = part.provider()
                    val buf = ByteArray(16 * 1024)
                    val mem = ByteArrayOutputStream()
                    val tmp = Files.createTempFile("kewu-voice-", ".audio")
                    tempFile = tmp
                    Files.newOutputStream(tmp).use { os ->
                        while (true) {
                            val n = channel.readAvailable(buf, 0, buf.size)
                            if (n <= 0) break                       // -1 / 0 = 读完了
                            // 超限后不能立刻 break：multipart 剩下的字节没被消费，Ktor 解析
                            // 下一个 part 会失败甚至取消协程，请求就会掉进「服务正忙」兜底。
                            // 继续把剩余数据读干净但不保存，让 forEachPart 正常收尾 → 稳定返回 400。
                            if (tooLarge) continue
                            if (mem.size() + n > MAX_AUDIO_BYTES) {
                                tooLarge = true
                                continue
                            }
                            mem.write(buf, 0, n)
                            os.write(buf, 0, n)
                        }
                    }
                    audio = if (tooLarge) null else mem.toByteArray()
                }
                else -> Unit
            }
            part.dispose()
        }
    }.onFailure {
        LoggerFactory.getLogger("VoiceChatRoutes").warn("读取 multipart 失败：${it.message}")
    }.isSuccess

    val sid = sessionId.trim()
        .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        .take(64)
    if (!ok || tooLarge) {
        tempFile?.let { runCatching { Files.deleteIfExists(it) } }
        return@withContext Upload(sid, null, fileName, null, true)
    }
    Upload(sid, audio, fileName, tempFile, false)
}

/** 中文不能进 HTTP header：ASCII 保持明文，含中文转 URL-safe Base64。 */
private fun encodeHeader(text: String): Pair<String, String> =
    if (text.isNotEmpty() && text.all { it.code in 32..126 }) {
        text to "plain"
    } else {
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(text.toByteArray(Charsets.UTF_8)) to "base64"
    }
