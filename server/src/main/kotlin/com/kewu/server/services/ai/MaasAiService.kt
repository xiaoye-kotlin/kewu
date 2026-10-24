package com.kewu.server.services.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// 真实 AI 实现：对接华为云 ModelArts MaaS 的 V2 标准 chat/completions 接口。
// 配置全部走环境变量（密钥不进代码、不进仓库）：
//   MAAS_API_KEY  必填，MaaS 控制台拿到的 API Key
//   MAAS_API_URL  可选，默认 https://api.modelarts-maas.com/v2/chat/completions
//   MAAS_MODEL    可选，默认 openpangu-2.0-flash
// 鉴权：Authorization: Bearer <MAAS_API_KEY>，请求体 OpenAI 兼容（model + messages[]）
class MaasAiService : AiService {

    private val apiKey: String = System.getenv("MAAS_API_KEY") ?: ""
    private val apiUrl: String =
        System.getenv("MAAS_API_URL").takeIf { !it.isNullOrBlank() }
            ?: "https://api.modelarts-maas.com/v2/chat/completions"
    private val model: String =
        System.getenv("MAAS_MODEL").takeIf { !it.isNullOrBlank() } ?: "openpangu-2.0-flash"

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    override suspend fun summarize(transcript: String): String {
        if (transcript.isBlank()) return "（转写内容为空，无法总结）"
        return chat(
            system = "你是一个课堂纪要助手。请把下面的课堂转写文本提炼成简洁的中文要点，" +
                "按条理分点列出，不要编造原文未提及的内容。",
            user = transcript,
            maxTokens = 1024
        )
    }

    override suspend fun answer(question: String, context: String): String {
        if (question.isBlank()) return "（问题为空）"
        return chat(
            system = "你是课堂智能助手，只能基于下面提供的课堂内容来回答学生的问题；" +
                "如果内容里没有相关信息，就如实说明无法回答，不要凭空编造。",
            user = "【课堂内容】\n${context.ifBlank { "（无）" }}\n\n【学生问题】\n$question",
            maxTokens = 768
        )
    }

    private suspend fun chat(system: String, user: String, maxTokens: Int): String {
        if (apiKey.isBlank()) return "（AI 未配置：缺少环境变量 MAAS_API_KEY）"
        val body = Json.encodeToString(
            MaasRequest.serializer(),
            MaasRequest(
                model = model,
                messages = listOf(
                    MaasMessage("system", system),
                    MaasMessage("user", user)
                ),
                max_tokens = maxTokens
            )
        )
        return withContext(Dispatchers.IO) {
            try {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer $apiKey")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    return@withContext "（AI 调用失败：HTTP ${response.statusCode()} ${response.body().take(300)}）"
                }
                extractContent(response.body())
            } catch (t: Throwable) {
                "（AI 调用异常：${t.message ?: t.javaClass.simpleName}）"
            }
        }
    }

    // 从 V2 响应里取 choices[0].message.content（reasoning_content 那部分是思考链，不要）
    private fun extractContent(body: String): String {
        return try {
            val root = Json.parseToJsonElement(body).jsonObject
            val choices = root["choices"]?.jsonArray
            val message = choices?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            val content = message?.get("content")?.jsonPrimitive?.content
            if (!content.isNullOrBlank()) content else "（AI 返回内容为空）"
        } catch (t: Throwable) {
            "（AI 返回解析失败：${t.message ?: t.javaClass.simpleName}）"
        }
    }
}

@Serializable
private data class MaasMessage(val role: String, val content: String)

@Serializable
private data class MaasRequest(
    val model: String,
    val messages: List<MaasMessage>,
    val max_tokens: Int
)
