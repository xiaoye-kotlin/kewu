package com.kewu.recserver.services

import com.kewu.infra.HuaweiSigner
import com.kewu.recserver.config.SisConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// 录音转文本：调华为云 SIS 长语音识别（异步录音文件识别接口）。
//
// 业务流程严格三步（对应需求）：
//   1) 鉴权：用配置好的 AK/SK 对每个请求算签名头（见 HuaweiSigner，等价于 token 鉴权，但无需换 token）。
//   2) 提交转写任务：POST /v1/{project_id}/asr/transcriber/jobs，拿回 job_id。
//   3) 轮询结果：GET /v1/{project_id}/asr/transcriber/jobs/{job_id}，每 10s 查一次，
//      FINISHED 提取文本返回；ERROR 抛业务异常；仍处理中则 delay(10s) 继续查。
//
// 避坑全按需求落实：
//   - 凭证（AK/SK、projectId、region）从环境变量读，不写死；
//   - 不用 @Serializable 数据类：请求体用 buildJsonObject 拼，响应用 Json.parseToJsonElement 手动解析；
//   - Ktor Client 遇 4xx/5xx 抛 ResponseException，已 try-catch 并打印 e.response 内容；
//   - 单次请求 30s 超时，防 SIS 卡死；
//   - 失败/超时绝不抛 500 崩溃，而是抛明确的 TranscriptionException，交给路由层兜底。
class SisTranscriptionService(@Suppress("unused") private val initial: SisConfig) {
    // 每次现读（2026-11-03 总控端可在线改 SIS 密钥）
    private val cfg: SisConfig get() = com.kewu.recserver.config.loadSisConfig()


    private val log = LoggerFactory.getLogger(SisTranscriptionService::class.java)

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000 // 单次请求 30s 超时，防 SIS 接口卡死
            connectTimeoutMillis = 15_000
        }
    }

    /** 对外入口：传入 OBS 上的音频 URL，返回转写文本。任何失败都抛 TranscriptionException，绝不把 500 细节抛给上层。 */
    suspend fun transcribe(dataUrl: String, audioFormat: String): String =
        extractText(transcribeRaw(dataUrl, audioFormat))

    /** 一句话一段：相对录音开头的起止毫秒 + 文本（2026-11-02 多端录音按时间互补合并用）。 */
    data class Segment(val startMs: Long, val endMs: Long, val text: String)

    suspend fun transcribeSegments(dataUrl: String, audioFormat: String): List<Segment> {
        val el = transcribeRaw(dataUrl, audioFormat)
        val segments = el["segments"] as? JsonArray ?: return emptyList()
        return segments.mapNotNull { seg ->
            val o = seg as? JsonObject ?: return@mapNotNull null
            val text = ((o["result"] as? JsonObject)?.get("text") as? JsonPrimitive)?.content?.trim().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            val st = (o["start_time"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: 0L
            val et = (o["end_time"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: st
            Segment(st, et, text)
        }
    }

    /**
     * 聊天语音转文字（2026-11-03，助教发语音）：录音已先存到 OBS（[dataUrl]），这里交给 SIS。
     * 先走「一句话识别」（同步、1–2 秒出结果，≤60 秒音频），失败再退回录音文件识别（OBS 链接，2 秒一查，最多等 [maxWaitMs]）。
     * 识别不出文字返回空串；配置缺失 / 都失败抛 [TranscriptionException]。
     */
    suspend fun transcribeChatVoice(dataUrl: String, wav: ByteArray, maxWaitMs: Long = 90_000): String {
        checkConfig()
        val host = cfg.endpoint.removePrefix("https://").removePrefix("http://").removeSuffix("/")
        val shortErr = runCatching { shortAudio(wav) }.fold(
            onSuccess = { return it },
            onFailure = { it.message ?: it.javaClass.simpleName },
        )
        log.warn("SIS 一句话识别失败，改走录音文件识别：$shortErr")
        val jobId = submitJob(host, dataUrl, "wav")
        val attempts = (maxWaitMs / 2_000).toInt().coerceAtLeast(5)
        return extractText(pollJob(host, jobId, intervalMs = 2_000, maxAttempts = attempts)).replace("\n", "")
    }

    /** SIS 一句话识别：16k 单声道 16bit WAV 去掉头部按 pcm16k16bit 发，其它按 wav 发；先试配置的域名再试 sis-ext。 */
    private suspend fun shortAudio(wav: ByteArray): String {
        val pcm16k = wav.size > 44 && String(wav, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            le32(wav, 24) == 16000 && le16(wav, 22) == 1 && le16(wav, 34) == 16
        val payload = if (pcm16k) dataChunk(wav) else wav
        val body = buildJsonObject {
            put("config", buildJsonObject {
                put("audio_format", if (pcm16k) "pcm16k16bit" else "wav")
                put("property", "chinese_16k_general")
                put("add_punc", "yes")
            })
            put("data", java.util.Base64.getEncoder().encodeToString(payload))
        }.toString()
        val uri = "/v1/${cfg.projectId}/asr/short-audio"
        val cfgHost = cfg.endpoint.removePrefix("https://").removePrefix("http://").removeSuffix("/")
        var lastErr = ""
        for (host in listOf(cfgHost, "sis-ext.${cfg.region}.myhuaweicloud.com").distinct()) {
            val date = nowUtc()
            val auth = HuaweiSigner.authHeader(cfg.ak!!, cfg.sk!!, "POST", uri, body, host, cfg.region, "sis", date)
            val (code, raw) = try {
                jdkPost("https://$host$uri", body, mapOf("X-Sdk-Date" to date, "Authorization" to auth))
            } catch (t: Throwable) {
                lastErr = "$host ${t.javaClass.simpleName}"
                continue
            }
            val el = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            val text = ((el?.get("result") as? JsonObject)?.get("text") as? JsonPrimitive)?.content
            if (code in 200..299 && text != null) return text.trim()
            lastErr = "$host HTTP $code ${raw.take(200)}"
        }
        throw TranscriptionException(lastErr)
    }

    /** WAV 里 data 块的内容（找不到 data 块就跳过标准 44 字节头）。 */
    private fun dataChunk(wav: ByteArray): ByteArray {
        var i = 12
        while (i + 8 <= wav.size) {
            val id = String(wav, i, 4, Charsets.US_ASCII)
            val len = le32(wav, i + 4)
            if (len < 0) break
            if (id == "data") return wav.copyOfRange(i + 8, minOf(wav.size, i + 8 + len))
            i += 8 + len + (len and 1)
        }
        return wav.copyOfRange(44, wav.size)
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)

    private fun checkConfig() {
        if (cfg.ak.isNullOrBlank() || cfg.sk.isNullOrBlank()) {
            throw TranscriptionException("转写失败，请重试（SIS 未配置 AK/SK，请检查 SIS_AK/SIS_SK 或 OBS_AK/OBS_SK）")
        }
        if (cfg.projectId.isBlank()) {
            throw TranscriptionException("转写失败，请重试（缺少 SIS_PROJECT_ID，请在华为云控制台查北京四的 projectId 并配置）")
        }
    }

    private suspend fun transcribeRaw(dataUrl: String, audioFormat: String): JsonObject {
        if (cfg.ak.isNullOrBlank() || cfg.sk.isNullOrBlank()) {
            throw TranscriptionException("转写失败，请重试（SIS 未配置 AK/SK，请检查 SIS_AK/SIS_SK 或 OBS_AK/OBS_SK）")
        }
        if (cfg.projectId.isBlank()) {
            throw TranscriptionException("转写失败，请重试（缺少 SIS_PROJECT_ID，请在华为云控制台查北京四的 projectId 并配置）")
        }
        val host = cfg.endpoint.removePrefix("https://").removePrefix("http://").removeSuffix("/")
        val jobId = submitJob(host, dataUrl, audioFormat)
        return pollJob(host, jobId)
    }

    // 步骤2：提交录音文件识别任务
    private suspend fun submitJob(host: String, dataUrl: String, audioFormat: String): String {
        val uri = "/v1/${cfg.projectId}/asr/transcriber/jobs"
        // 请求体按 SIS 文档结构：config 里放格式/模型参数，data_url 放 OBS 音频地址
        val body = buildJsonObject {
            put("config", buildJsonObject {
                // 2026-10-27：按官方 audio_format 取值表，wav/mp3/m4a 等容器格式只能填 auto（系统自动判断），
                // 只有裸 PCM / ulaw / alaw 才填具体值；旧版直接把 "wav" 传过去不在取值范围内
                put("audio_format", if (audioFormat.startsWith("pcm") || audioFormat.startsWith("ulaw") || audioFormat.startsWith("alaw")) audioFormat else "auto")
                put("property", "chinese_16k_media") // 课堂录音建议用媒体模型
                put("add_punc", "yes")               // 加标点，便于后续 AI 总结
                put("digit_norm", "no")
            })
            put("data_url", dataUrl)
        }.toString()
        val date = nowUtc()
        val auth = HuaweiSigner.authHeader(cfg.ak!!, cfg.sk!!, "POST", uri, body, host, cfg.region, "sis", date)
        return try {
            val resp = client.post("https://$host$uri") {
                header("Host", host)
                header("X-Sdk-Date", date)
                header("Authorization", auth)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val raw = resp.bodyAsText()
            val el = Json.parseToJsonElement(raw).jsonObject
            // 不同版本返回字段可能是 job_id 或 task_id，做容错
            val id = (el["job_id"] as? JsonPrimitive)?.content
                ?: (el["task_id"] as? JsonPrimitive)?.content
            if (id.isNullOrBlank()) {
                log.error("SIS 提交任务未返回 job_id，响应前 500 字：${raw.take(500)}")
                // 2026-10-27：Ktor 默认不对 4xx 抛异常，401 / 400 的错误体走到这里——带上 HTTP 状态和错误码
                throw TranscriptionException("转写失败，请重试（提交任务 HTTP ${resp.status.value}：${raw.take(300)}）")
            }
            log.info("SIS 提交转写任务成功 job_id=$id")
            id
        } catch (e: ResponseException) {
            // 4xx/5xx：e.response 里才有错误详情，不读就只剩状态码
            val err = runCatching { e.response.bodyAsText() }.getOrNull() ?: e.message
            log.error("SIS 提交任务 HTTP ${e.response.status}：$err")
            throw TranscriptionException("转写失败，请重试（提交任务出错：${e.response.status}）")
        } catch (e: TranscriptionException) {
            throw e // 业务异常原样上抛
        } catch (t: Throwable) {
            log.error("SIS 提交任务异常：$t")
            // 2026-10-27：把真实原因带出来（旧版一律「网络或超时」，DNS 不存在也看不出来）
            throw TranscriptionException("转写失败，请重试（提交任务：${t.javaClass.simpleName} ${t.message.orEmpty().take(200)}）")
        }
    }

    // 步骤3：轮询任务状态，每 10s 一次
    private suspend fun pollJob(host: String, jobId: String, intervalMs: Long = 10_000, maxAttempts: Int = 240): JsonObject {
        val uri = "/v1/${cfg.projectId}/asr/transcriber/jobs/$jobId"
        // 2026-11-02：一节课 1 小时以上的录音 10 分钟常转不完 → 放到 40 分钟（240 × 10s）
        var transient = 0
        repeat(maxAttempts) { attempt ->
            // 每次轮询都重新算时间和签名：X-Sdk-Date 必须在请求时间 15 分钟内，轮询久了会过期
            val date = nowUtc()
            val auth = HuaweiSigner.authHeader(cfg.ak!!, cfg.sk!!, "GET", uri, "", host, cfg.region, "sis", date)
            try {
                // 2026-11-02：查询改用 JDK HttpClient。Ktor CIO 读大号 FINISHED 结果偶发
                //   「EOFException Not enough data available」，一次失败就让整节课转写作废（线代 11-02 卡在 10%）。
                val (code, raw) = jdkGet("https://$host$uri", mapOf("X-Sdk-Date" to date, "Authorization" to auth))
                transient = 0
                val el = Json.parseToJsonElement(raw).jsonObject
                when (val status = (el["status"] as? JsonPrimitive)?.content) {
                    "FINISHED" -> {
                        log.info("SIS 转写完成 job_id=$jobId，文本长度=${extractText(el).length}")
                        return el
                    }
                    "ERROR" -> {
                        val code = (el["error_code"] as? JsonPrimitive)?.content
                        val msg = (el["error_msg"] as? JsonPrimitive)?.content
                        log.error("SIS 转写任务失败 job_id=$jobId code=$code msg=$msg")
                        throw TranscriptionException("转写失败，请重试（识别任务出错：${code ?: ""} ${msg ?: ""}）".trim())
                    }
                    null -> {
                        // 2026-10-27：没有 status 的响应是网关 / 鉴权错误体（4xx 不抛异常），别当成「处理中」空等 10 分钟
                        log.error("SIS 查询任务 HTTP $code：${raw.take(500)}")
                        throw TranscriptionException("转写失败，请重试（查询任务 HTTP $code：${raw.take(300)}）")
                    }
                    else -> {
                        log.info("SIS 转写进行中 job_id=$jobId status=$status 第 ${attempt + 1} 次轮询")
                        delay(intervalMs)
                    }
                }
            } catch (e: ResponseException) {
                val err = runCatching { e.response.bodyAsText() }.getOrNull() ?: e.message
                log.error("SIS 查询任务 HTTP ${e.response.status}：$err")
                throw TranscriptionException("转写失败，请重试（查询任务出错：${e.response.status}）")
            } catch (e: TranscriptionException) {
                throw e
            } catch (t: Throwable) {
                // 网络抖动 / 读响应中断：任务还在 SIS 那边跑，重试查询即可，连续 6 次才判失败
                transient++
                log.error("SIS 查询任务异常（第 $transient 次）：$t")
                if (transient >= 6) throw TranscriptionException("转写失败，请重试（查询任务：${t.javaClass.simpleName} ${t.message.orEmpty().take(200)}）")
                delay(intervalMs)
            }
        }
        log.error("SIS 转写轮询超时 job_id=$jobId（超过 ${maxAttempts * intervalMs / 1000} 秒）")
        throw TranscriptionException("转写超时，请稍后重试（任务处理超过 ${maxAttempts * intervalMs / 60_000} 分钟）")
    }

    private val jdk: java.net.http.HttpClient = java.net.http.HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(15)).build()

    /** POST JSON 并完整读出响应体（状态码, 文本）。 */
    private suspend fun jdkPost(url: String, body: String, headers: Map<String, String>): Pair<Int, String> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val b = java.net.http.HttpRequest.newBuilder(java.net.URI(url)).timeout(java.time.Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
            headers.forEach { (k, v) -> b.header(k, v) }
            val r = jdk.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            r.statusCode() to r.body()
        }

    /** GET 并完整读出响应体（状态码, 文本）。 */
    private suspend fun jdkGet(url: String, headers: Map<String, String>): Pair<Int, String> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val b = java.net.http.HttpRequest.newBuilder(java.net.URI(url)).timeout(java.time.Duration.ofSeconds(60)).GET()
            headers.forEach { (k, v) -> b.header(k, v) }
            val r = jdk.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            r.statusCode() to r.body()
        }

    // 从 FINISHED 响应里把所有句子的 result.text 拼成完整文本（按返回顺序）
    private fun extractText(el: JsonObject): String {
        val segments = el["segments"]
        if (segments !is JsonArray || segments.isEmpty()) return ""
        return segments.mapNotNull { seg ->
            if (seg is JsonObject) {
                val result = seg["result"]
                if (result is JsonObject) (result["text"] as? JsonPrimitive)?.content else null
            } else null
        }.joinToString("\n")
    }

    private fun nowUtc(): String =
        ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
}

// 明确的业务异常：转写失败/超时时抛出，让路由层优雅兜底，不把 500 崩给用户
class TranscriptionException(message: String) : Exception(message)
