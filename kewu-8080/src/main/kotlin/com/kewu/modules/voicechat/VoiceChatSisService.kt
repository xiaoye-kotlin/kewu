package com.kewu.modules.voicechat

import com.kewu.infra.SecretStore
import com.kewu.recserver.config.loadSisConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * 语音对话用到的两个 SIS 接口（一句话识别 + 语音合成）。
 *
 * 为什么单独一份、不直接复用 SisTranscriptionService：
 *   - 那边是「长录音异步 job」思路，shortAudio 是 private 且只认 WAV/pcm16k；
 *   - 这里要吃手机直接传来的 m4a/wav，还要新增工程里完全没有的 TTS；
 *   - 两边共用同一个 HuaweiSigner / loadSisConfig，配置和鉴权口径不分裂。
 *
 * 两个方法都自己吞掉异常：ASR 失败返回空串（上层播「没听清」），TTS 失败返回 null（上层降级），
 * 任何情况下都不会把异常抛到 Ktor 的 500。
 */
class VoiceChatSisService(private val tokens: SisTokenManager = SisTokenManager()) {

    private val log = LoggerFactory.getLogger(VoiceChatSisService::class.java)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(12))
        .proxy(java.net.ProxySelector.of(null))
        .build()

    /** ASR 结果：text 为空即"没听清"，error 只用于日志/排查，不影响降级。 */
    data class AsrResult(val text: String = "", val error: String? = null)

    /**
     * 一句话识别（短音频同步接口，≤60 秒）：POST /v1/{projectId}/asr/short-audio。
     * audio_format 用官方支持的 auto，引擎自己判断 m4a / wav / mp3 / aac，前端不用转码。
     */
    suspend fun recognizeShortAudio(audio: ByteArray): AsrResult {
        if (audio.isEmpty()) return AsrResult(error = "音频为空")
        val cfg = loadSisConfig()
        // 只在这里挡缺 projectId；AK/SK / IAM Token 有没有配交给 TokenManager 判断，
        // 否则配了 IAM 账密但没配 AK/SK 的环境会被误判成"未配置"
        if (cfg.projectId.isBlank()) return AsrResult(error = "缺少 SIS_PROJECT_ID")

        val dataBase64 = Base64.getEncoder().encodeToString(audio)
        val hosts = candidateHosts()
        // 只用一种格式：auto 本身就能自动认 m4a/wav/mp3/aac。之前多留一轮 wav 兜底，
        // 大音频识别失败时要白等一次 15 秒超时，直接把整条链路顶到 35 秒总超时。
        val formats = listOf(get("SIS_VOICE_ASR_FORMAT") ?: "auto")

        var lastErr = ""
        for (host in hosts) {
            for (format in formats) {
                val body = buildJsonObject {
                    put("config", buildJsonObject {
                        put("audio_format", format)
                        put("property", get("SIS_VOICE_ASR_PROPERTY") ?: "chinese_16k_general")
                        put("add_punc", "yes")
                    })
                    put("data", dataBase64)
                }.toString()
                val uri = "/v1/${cfg.projectId}/asr/short-audio"
                val result = callSis(host, uri, body, timeoutSec = 15, tag = "ASR/$format")
                // -1 = 连不上/超时，这个域名没救了，别再拿别的格式白等一轮，直接换域名
                if (result.first == -1) break
                val text = result.second?.let { extractAsrText(it) }?.trim().orEmpty()
                when {
                    result.first in 200..299 && text.isNotEmpty() -> return AsrResult(text = text)
                    result.first in 401..403 -> {
                        // Token 过期/被踢：丢缓存，下一轮循环会重新换
                        tokens.invalidate()
                        lastErr = "$host 鉴权失效（已刷新凭证）"
                    }
                    else -> lastErr = "$host/$format HTTP ${result.first} ${result.second?.take(160).orEmpty()}"
                }
            }
        }
        log.warn("SIS 一句话识别没拿到文本：$lastErr")
        return AsrResult(error = lastErr)
    }

    /**
     * 语音合成（TTS）：POST /v1/{projectId}/tts，成功返回 mp3 字节流。
     * 响应是 JSON，音频在 result.data 里且是 Base64，不是直接返回二进制，所以要解码一次。
     */
    suspend fun synthesizeMp3(text: String): ByteArray? {
        if (text.isBlank()) return null
        val cfg = loadSisConfig()
        if (cfg.projectId.isBlank()) return null

        val body = buildJsonObject {
            put("text", text.take(500))   // 官方文档：单次合成不超过 500 字符
            put("config", buildJsonObject {
                put("audio_format", get("SIS_TTS_AUDIO_FORMAT") ?: "mp3")
                // sample_rate 不传：它是可选字段，默认就是 16000，避开各版本 string/int 类型差异
                put("property", get("SIS_TTS_PROPERTY") ?: "chinese_huaxiaomei_common")
                put("speed", 0)
                put("pitch", 0)
                put("volume", 50)
            })
        }.toString()
        val uri = "/v1/${cfg.projectId}/tts"

        for (host in candidateHosts()) {
            val (code, raw) = callSis(host, uri, body, timeoutSec = 20, tag = "TTS")
            if (code in 401..403) {
                tokens.invalidate()
                continue
            }
            if (code !in 200..299 || raw == null) {
                log.warn("SIS TTS 失败 $host HTTP $code：${raw?.take(160).orEmpty()}")
                continue
            }
            val bytes = extractMp3(raw)
            if (bytes != null && bytes.isNotEmpty()) return bytes
            log.warn("SIS TTS 响应里没有音频：$host ${raw.take(160)}")
        }
        return null
    }

    /** 统一发一次 SIS 请求：带鉴权头、超时、401 自动刷新重试一次。返回 (状态码, 响应体)。 */
    private suspend fun callSis(
        host: String, uri: String, body: String, timeoutSec: Long, tag: String,
    ): Pair<Int, String?> {
        val cfg = loadSisConfig()
        repeat(2) { attempt ->
            val headers = try {
                tokens.authHeaders("POST", uri, body, host, cfg.region, "sis")
            } catch (e: VoiceChatException) {
                log.warn("$tag 鉴权失败：${e.message}")
                return 0 to null
            }
            val (code, raw) = postJson("https://$host$uri", body, headers, timeoutSec)
            if (code in 401..403 && attempt == 0) {
                tokens.invalidate()   // 换一次凭证再试
                return@repeat
            }
            return code to raw
        }
        return 0 to null
    }

    /** POST JSON，阻塞的 JDK HttpClient 放到 IO 线程池，不占 Netty 的请求线程。 */
    private suspend fun postJson(
        url: String, body: String, headers: Map<String, String>, timeoutSec: Long,
    ): Pair<Int, String?> = withContext(Dispatchers.IO) {
        runCatching {
            val b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
            // Host 是 JDK 的受限头，交给它自动带；其它鉴权头照抄
            headers.filterKeys { !it.equals("Host", ignoreCase = true) }
                .forEach { (k, v) -> b.header(k, v) }
            val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            r.statusCode() to r.body()
        }.onFailure { log.warn("SIS 请求异常 $url：${it.javaClass.simpleName} ${it.message.orEmpty().take(120)}") }
            .getOrElse { -1 to null }
    }

    private fun extractAsrText(raw: String): String? = runCatching {
        val el = Json.parseToJsonElement(raw).jsonObject
        ((el["result"] as? JsonObject)?.get("text") as? JsonPrimitive)?.content
            ?: (el["text"] as? JsonPrimitive)?.content
    }.getOrNull()

    private fun extractMp3(raw: String): ByteArray? = runCatching {
        val el = Json.parseToJsonElement(raw).jsonObject
        val data = ((el["result"] as? JsonObject)?.get("data") as? JsonPrimitive)?.content
            ?: (el["data"] as? JsonPrimitive)?.content
        if (data == null) null else Base64.getDecoder().decode(data.filter { !it.isWhitespace() })
    }.getOrNull()

    /** 先试配置的域名，再试官方 sis-ext 域名，DNS/网关抽风时多一条活路。 */
    private fun candidateHosts(): List<String> {
        val cfg = loadSisConfig()
        val configured = cfg.endpoint.removePrefix("https://").removePrefix("http://").removeSuffix("/")
        return listOf(configured, "sis-ext.${cfg.region}.myhuaweicloud.com").distinct().filter { it.isNotBlank() }
    }

    private fun get(key: String): String? = SecretStore.get(key)?.trim()?.takeIf { it.isNotBlank() }
}
