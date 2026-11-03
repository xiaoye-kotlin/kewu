package com.kewu.recserver.services

import com.kewu.infra.HuaweiSigner
import com.kewu.infra.SecretStore
import com.kewu.recserver.config.loadSisConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// 华为云 SIS 实时语音识别中转（2026-11-03 用户：App 里对 AI 说话不要再上传文件转写，太慢，改实时转录）。
//
// App ⇄ 本服务（WebSocket /api/ai/voice/stream）⇄ SIS RASR 连续识别（wss://sis-ext.{region}/v1/{pid}/rasr/continue-stream）。
// 密钥只在服务器（SecretStore），App 只发 PCM、收文字：
//   App → 服务：二进制帧 = PCM16 / 16kHz / 单声道；文本 "END" = 说完了；"CANCEL" = 取消
//   服务 → App：{"type":"ready"} / {"type":"text","text":已识别全文} / {"type":"done","text":全文} / {"type":"error","message":…}
// 握手用 AK/SK 签名（与文件转写同一个 HuaweiSigner，只签 host + x-sdk-date）。
object SisRealtimeService {
    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy { HttpClient(CIO) { install(WebSockets) } }
    private val sdkDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    private data class Target(val host: String, val path: String, val ak: String, val sk: String, val region: String)

    private fun target(): Target? {
        val cfg = loadSisConfig()
        if (cfg.ak.isNullOrBlank() || cfg.sk.isNullOrBlank() || cfg.projectId.isBlank()) return null
        val host = cfg.endpoint.removePrefix("https://").removePrefix("http://").removeSuffix("/")
        return Target(host, "/v1/${cfg.projectId}/rasr/continue-stream", cfg.ak, cfg.sk, cfg.region)
    }

    private fun startCommand(): String = buildJsonObject {
        put("command", "START")
        put("config", buildJsonObject {
            put("audio_format", "pcm16k16bit")
            put("property", SecretStore.get("SIS_RASR_PROPERTY") ?: "chinese_16k_general")
            put("add_punc", "yes")
            put("interim_results", "yes")
            put("digit_norm", "yes")
            put("vad_head", 10_000)
            put("vad_tail", 800)
        })
    }.toString()

    private val endCommand = """{"command":"END","cancel":"false"}"""

    /** 连 SIS 并在会话里执行 [block]（已发 START）。 */
    private suspend fun withSis(block: suspend DefaultWebSocketSession.() -> Unit) {
        val t = target() ?: throw IllegalStateException("服务器未配置 SIS（项目 ID / AK / SK），请在总控端「密钥配置」填写")
        val date = ZonedDateTime.now(ZoneOffset.UTC).format(sdkDate)
        val auth = HuaweiSigner.authHeader(t.ak, t.sk, "GET", t.path, "", t.host, t.region, "sis", date)
        client.webSocket(urlString = "wss://${t.host}${t.path}", request = {
            header("X-Sdk-Date", date)
            header("Authorization", auth)
        }) {
            send(Frame.Text(startCommand()))
            block()
        }
    }

    /** 把一个 App 会话中转到 SIS，直到说完 / 断开。 */
    suspend fun relay(app: DefaultWebSocketSession) {
        try {
            withSis {
                val sis = this
                var committed = ""
                var partial = ""
                coroutineScope {
                    // 上行：App 的音频 → SIS
                    val up = launch {
                        try {
                            for (f in app.incoming) {
                                when (f) {
                                    is Frame.Binary -> sis.send(Frame.Binary(true, f.data))
                                    is Frame.Text -> {
                                        val cmd = f.readText().trim()
                                        if (cmd == "END" || cmd == "CANCEL") { sis.send(Frame.Text(endCommand)); break }
                                    }
                                    else -> Unit
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                        // App 断开但没说 END：照样让 SIS 收尾
                        runCatching { sis.send(Frame.Text(endCommand)) }
                    }
                    // 下行：SIS 结果 → App（发累计全文，App 直接替换显示）
                    for (f in sis.incoming) {
                        if (f !is Frame.Text) continue
                        val o = runCatching { json.parseToJsonElement(f.readText()) as? JsonObject }.getOrNull() ?: continue
                        when ((o["resp_type"] as? JsonPrimitive)?.content) {
                            "START" -> app.send(Frame.Text("""{"type":"ready"}"""))
                            "RESULT" -> {
                                (o["segments"] as? JsonArray).orEmpty().forEach { seg ->
                                    val s = seg as? JsonObject ?: return@forEach
                                    val text = ((s["result"] as? JsonObject)?.get("text") as? JsonPrimitive)?.content.orEmpty()
                                    val final = (s["is_final"] as? JsonPrimitive)?.booleanOrNull ?: false
                                    if (final) { committed += text; partial = "" } else partial = text
                                }
                                app.send(Frame.Text(msg("text", committed + partial)))
                            }
                            "ERROR" -> {
                                val m = (o["error_msg"] as? JsonPrimitive)?.content ?: "识别出错"
                                println("RASR-ERROR: ${o["error_code"]} $m")
                                app.send(Frame.Text(msg("error", "语音识别出错：$m", key = "message")))
                                break
                            }
                            "END" -> break
                            else -> Unit
                        }
                    }
                    up.cancel()
                }
                runCatching { app.send(Frame.Text(msg("done", committed + partial))) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("RASR-RELAY-ERROR: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { app.send(Frame.Text(msg("error", e.message ?: "连接语音识别服务失败", key = "message"))) }
        } finally {
            runCatching { app.close(CloseReason(CloseReason.Codes.NORMAL, "bye")) }
        }
    }

    private fun msg(type: String, text: String, key: String = "text"): String = buildJsonObject {
        put("type", type); put(key, text)
    }.toString()

    /** 总控端「测试连接」：握手成功并收到 SIS 的 START 应答即认为可用。 */
    suspend fun probe(): Pair<Boolean, String> = try {
        var ok: Pair<Boolean, String> = false to "SIS 没有回应"
        withTimeoutOrNull(12_000) {
            withSis {
                for (f in incoming) {
                    if (f !is Frame.Text) continue
                    val o = runCatching { json.parseToJsonElement(f.readText()) as? JsonObject }.getOrNull() ?: continue
                    when (o["resp_type"]?.jsonPrimitive?.content) {
                        "START" -> { ok = true to "实时语音识别可用"; break }
                        "ERROR" -> { ok = false to ("SIS 返回错误：" + (o["error_msg"]?.jsonPrimitive?.content ?: "")); break }
                    }
                }
                runCatching { send(Frame.Text("""{"command":"END","cancel":"true"}""")) }
            }
        } ?: run { ok = false to "连接 SIS 超时" }
        ok
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false to (e.message ?: e.javaClass.simpleName)
    }
}
