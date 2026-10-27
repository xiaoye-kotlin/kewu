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
class SisTranscriptionService(private val cfg: SisConfig) {

    private val log = LoggerFactory.getLogger(SisTranscriptionService::class.java)

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000 // 单次请求 30s 超时，防 SIS 接口卡死
            connectTimeoutMillis = 15_000
        }
    }

    /** 对外入口：传入 OBS 上的音频 URL，返回转写文本。任何失败都抛 TranscriptionException，绝不把 500 细节抛给上层。 */
    suspend fun transcribe(dataUrl: String, audioFormat: String): String {
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
    private suspend fun pollJob(host: String, jobId: String): String {
        val uri = "/v1/${cfg.projectId}/asr/transcriber/jobs/$jobId"
        val maxAttempts = 60 // 60 * 10s = 最多等 10 分钟（SIS 长语音识别上限内）
        repeat(maxAttempts) { attempt ->
            // 每次轮询都重新算时间和签名：X-Sdk-Date 必须在请求时间 15 分钟内，轮询久了会过期
            val date = nowUtc()
            val auth = HuaweiSigner.authHeader(cfg.ak!!, cfg.sk!!, "GET", uri, "", host, cfg.region, "sis", date)
            try {
                val resp = client.get("https://$host$uri") {
                    header("Host", host)
                    header("X-Sdk-Date", date)
                    header("Authorization", auth)
                }
                val raw = resp.bodyAsText()
                val el = Json.parseToJsonElement(raw).jsonObject
                when (val status = (el["status"] as? JsonPrimitive)?.content) {
                    "FINISHED" -> {
                        val text = extractText(el)
                        log.info("SIS 转写完成 job_id=$jobId，文本长度=${text.length}")
                        return text
                    }
                    "ERROR" -> {
                        val code = (el["error_code"] as? JsonPrimitive)?.content
                        val msg = (el["error_msg"] as? JsonPrimitive)?.content
                        log.error("SIS 转写任务失败 job_id=$jobId code=$code msg=$msg")
                        throw TranscriptionException("转写失败，请重试（识别任务出错：${code ?: ""} ${msg ?: ""}）".trim())
                    }
                    null -> {
                        // 2026-10-27：没有 status 的响应是网关 / 鉴权错误体（4xx 不抛异常），别当成「处理中」空等 10 分钟
                        log.error("SIS 查询任务 HTTP ${resp.status.value}：${raw.take(500)}")
                        throw TranscriptionException("转写失败，请重试（查询任务 HTTP ${resp.status.value}：${raw.take(300)}）")
                    }
                    else -> {
                        log.info("SIS 转写进行中 job_id=$jobId status=$status 第 ${attempt + 1} 次轮询，10s 后重试")
                        delay(10_000)
                    }
                }
            } catch (e: ResponseException) {
                val err = runCatching { e.response.bodyAsText() }.getOrNull() ?: e.message
                log.error("SIS 查询任务 HTTP ${e.response.status}：$err")
                throw TranscriptionException("转写失败，请重试（查询任务出错：${e.response.status}）")
            } catch (e: TranscriptionException) {
                throw e
            } catch (t: Throwable) {
                log.error("SIS 查询任务异常：$t")
                throw TranscriptionException("转写失败，请重试（查询任务：${t.javaClass.simpleName} ${t.message.orEmpty().take(200)}）")
            }
        }
        log.error("SIS 转写轮询超时 job_id=$jobId（超过 ${maxAttempts * 10} 秒）")
        throw TranscriptionException("转写超时，请稍后重试（任务处理超过 10 分钟）")
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
