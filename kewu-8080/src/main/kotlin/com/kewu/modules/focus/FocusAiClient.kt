package com.kewu.modules.focus

import com.kewu.infra.SecretStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

// 认知习惯分析用的 DeepSeek 客户端：把 prompt 发给 MaaS，抠出模型吐的那段文本。
// 配置每次现读（总控端改了立即生效），超时 / 4xx / 任何异常都返回 null 交给上层走默认推荐。
class FocusAiClient {
    private val log = LoggerFactory.getLogger(FocusAiClient::class.java)
    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 12_000
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 12_000
        }
    }

    suspend fun chat(prompt: String): String? {
        val apiUrl = SecretStore.get("MAAS_API_URL")?.trim().orEmpty()
        val apiKey = SecretStore.get("MAAS_API_KEY")?.trim().orEmpty()
        val model = SecretStore.get("MAAS_MODEL_NAME")?.trim().orEmpty()
        if (apiUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
            log.warn("FocusAI: MAAS_API_URL/KEY/MODEL_NAME 未配置，跳过 AI 调用")
            return null
        }

        val body = buildJsonObject {
            put("model", model)
            put("temperature", 0.3)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }.toString()

        return try {
            val raw = client.post(apiUrl) {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(body)
            }.bodyAsText()
            extractContent(raw)
        } catch (t: Throwable) {
            log.warn("FocusAI: 调用失败/超时，降级为默认推荐：${t.message}")
            null
        }
    }

    // 兼容 OpenAI 风格信封和少数网关直接回 result 的情况；实在认不出就原样交给上层解析
    private fun extractContent(raw: String): String {
        val text = raw.trim()
        return runCatching {
            val obj = Json.parseToJsonElement(text) as? JsonObject ?: return text
            obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: obj["result"]?.jsonPrimitive?.content
                ?: text
        }.getOrNull() ?: text
    }

    fun close() = runCatching { client.close() }
}
