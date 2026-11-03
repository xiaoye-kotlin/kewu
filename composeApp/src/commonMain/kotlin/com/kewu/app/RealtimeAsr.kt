package com.kewu.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 边录边送的 PCM 采集（2026-11-03，实时语音识别用）：PCM16 / 16kHz / 单声道。
 * 注册表对象，由平台实现（Android 在 MainActivity 里用 AudioRecord 注册）；未注册的平台为 null，调用方退回整段录音上传。
 * - [start]：开始采集，每 ~100ms 回调一块 PCM，同时把整段写成 WAV 到 [wavPath]（语音气泡回放用）；成功返回 true
 * - [stop]：停止，返回 WAV 路径（失败 null）
 */
object LiveAudio {
    var start: ((wavPath: String, onChunk: (ByteArray) -> Unit) -> Boolean)? = null
    var stop: (() -> String?)? = null
    /** 当前输入音量 0..1（声纹动画可用）。 */
    var level: (() -> Float)? = null
}

/**
 * 华为云 SIS 实时语音识别客户端（2026-11-03 用户：对 AI 说话不要上传文件再转写，改实时转录）。
 * 走自家后端 WebSocket `/api/ai/voice/stream`（密钥只在服务器）：发 PCM 二进制帧、说完发 "END"，
 * 收 `{"type":"text","text":已识别全文}` 实时刷新 [text]，`{"type":"done"}` 结束。
 * 连不上 / 出错时 [failed] = true，调用方退回文件上传识别。课堂录音不走这里。
 */
class RealtimeAsr {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }
    private val audio = Channel<ByteArray>(capacity = 256)
    private val done = CompletableDeferred<String?>()
    private var job: Job? = null

    /** 已识别的全文（边说边更新）。 */
    var text by mutableStateOf("")
        private set

    /** 连接 / 识别失败。 */
    var failed by mutableStateOf(false)
        private set

    fun start() {
        if (job != null) return
        val url = KewuApi.BASE_URL.replaceFirst("http", "ws") + "/api/ai/voice/stream"
        job = scope.launch {
            try {
                client.webSocket(urlString = url, request = {
                    KewuApi.token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                }) {
                    val up = launch {
                        for (chunk in audio) send(Frame.Binary(true, chunk))
                        send(Frame.Text("END"))
                    }
                    for (f in incoming) {
                        if (f !is Frame.Text) continue
                        val o = runCatching { json.parseToJsonElement(f.readText()) as? JsonObject }.getOrNull() ?: continue
                        val t = (o["text"] as? JsonPrimitive)?.content
                        when ((o["type"] as? JsonPrimitive)?.content) {
                            "text" -> if (t != null) text = t
                            "done" -> { if (!t.isNullOrBlank()) text = t; break }
                            "error" -> {
                                println("[RealtimeAsr] ${(o["message"] as? JsonPrimitive)?.content}")
                                failed = true
                                break
                            }
                        }
                    }
                    up.cancel()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("[RealtimeAsr] ${e::class.simpleName}: ${e.message}")
                failed = true
            } finally {
                done.complete(if (failed) null else text)
            }
        }
    }

    /** 送一块 PCM（满了丢弃，不阻塞采集线程）。 */
    fun push(chunk: ByteArray) {
        audio.trySend(chunk)
    }

    /** 说完：等最终结果（最多 [timeoutMs]），失败返回 null。 */
    suspend fun finish(timeoutMs: Long = 5_000): String? {
        audio.close()
        val r = withTimeoutOrNull(timeoutMs) { done.await() }
        cancel()
        return r?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun cancel() {
        audio.close()
        job?.cancel()
    }

    companion object {
        private val client by lazy { platformHttpClient().config { install(WebSockets) } }
    }
}
