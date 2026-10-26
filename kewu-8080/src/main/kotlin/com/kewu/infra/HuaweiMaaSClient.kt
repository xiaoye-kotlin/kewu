package com.kewu.infra

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

// 华为云 MaaS（盘古大模型）语义提取客户端。
// 只做一件事：把一段学生提问文本发过去，拿回模型生成的“原始文本”。
// 解析(去 markdown 围栏、抽 JSON 数组)交给 RadarService，这里不关心业务形态。
//
// 容错铁律：4xx/5xx 会抛 ResponseException（必须读 e.response 才能拿到错误体），
// 超时/连不上/任何异常都返回 null —— 上层据此降级返回空数组，绝不抛 500。
class HuaweiMaaSClient {
    private val log = LoggerFactory.getLogger(HuaweiMaaSClient::class.java)

    // 配置全走环境变量，没配就空串（下面会判定为“未配置”直接降级）
    private val apiUrl = System.getenv("API_URL") ?: ""
    private val apiKey = System.getenv("API_KEY") ?: ""
    private val modelName = System.getenv("MODEL_NAME") ?: ""

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            // 这台服务器只有 2G 内存、磁盘又满过，响应普遍偏慢，超时给宽一点
            requestTimeoutMillis = 90_000   // 这台服务器负载重，模型 routinely 要 30s+，给足
            connectTimeoutMillis = 15_000
        }
    }

    // 把 50 条问题拼成提示词，调模型，返回模型“思考出来的文本”。
    // 返回 null = 本次调用失败/未配置，上层当降级处理。
    suspend fun extractKeywords(questionsText: String): String? {
        if (apiUrl.isBlank() || apiKey.isBlank()) {
            log.warn("HuaweiMaaS: API_URL/API_KEY 未配置，跳过 AI 调用（降级返回空）")
            return null
        }

        val prompt = buildPrompt(questionsText)
        // 注意：messages 必须是 JSON 数组（哪怕只有一轮 user 消息），
        // 拼成对象会被 MaaS 网关判 400，上层只看到降级成 []，极难排查
        val body = Json.encodeToString(
            buildJsonObject {
                put("model", modelName)
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }
        )
        log.info("HuaweiMaaS: 请求体前 200 字 = ${body.take(200)}")

        return try {
            val resp = client.post(apiUrl) {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            // 先把整段响应体当文本拿出来，再从常见字段里抠出模型实际说的话
            val raw = resp.bodyAsText()
            extractTextFromResponse(raw)
        } catch (e: ResponseException) {
            // 4xx/5xx：e.response 里才有错误详情，不读就只剩状态码
            val err = runCatching { e.response.bodyAsText() }.getOrNull() ?: e.message
            log.warn("HuaweiMaaS: 模型返回错误(${e.response.status})：$err")
            null
        } catch (e: Throwable) {
            log.warn("HuaweiMaaS: 调用异常（超时/网络/解析），降级：${e.message}")
            null
        }
    }

    // 华为云 MaaS / OpenAI 兼容网关的返回形态不一，这里尽量兼容几种常见结构，
    // 最终抠出模型生成的“那段文本”（里面应当就是我们想要的 JSON 数组）。
    private fun extractTextFromResponse(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // 只有“纯 JSON 数组”才可能是模型直接吐给我们的结构，可以原样返回。
        // 千万不要把 startsWith("{") 也加进来：MaaS 的完整响应信封本身就以 { 开头，
        // 那样会连 choices[0].message.content 都不抽，直接把整个信封当知识点数组，
        // 上层必然解析失败——这个坑踩过一次，日志里表现为"AI 返回无法解析为知识点数组"。
        if (trimmed.startsWith("[")) return trimmed
        // 否则当 JSON 对象解析，从常见字段里取
        return runCatching {
            val el = Json.parseToJsonElement(trimmed)
            when {
                el is kotlinx.serialization.json.JsonObject -> {
                    // OpenAI 风格: choices[0].message.content
                    el["choices"]?.let { ch ->
                        if (ch is kotlinx.serialization.json.JsonArray && ch.isNotEmpty()) {
                            ch[0].let { c ->
                                if (c is kotlinx.serialization.json.JsonObject) {
                                    c["message"]?.let { m ->
                                        if (m is kotlinx.serialization.json.JsonObject) {
                                            m["content"]?.let { it.jsonPrimitive.content }
                                        } else null
                                    }
                                } else null
                            }
                        } else null
                    }
                        // 华为部分网关: result / predictions[0].content
                        ?: el["result"]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.content else null }
                        ?: el["predictions"]?.let { p ->
                            if (p is kotlinx.serialization.json.JsonArray && p.isNotEmpty()) {
                                p[0].let { first ->
                                    if (first is kotlinx.serialization.json.JsonObject) {
                                        first["content"]?.let { it.jsonPrimitive.content }
                                            ?: first["generated_text"]?.let { it.jsonPrimitive.content }
                                    } else null
                                }
                            } else null
                        }
                        ?: trimmed // 都没匹配上，原样返回交给上层去 markdown 围栏
                }
                else -> trimmed
            }
        }.getOrNull() ?: trimmed
    }

    private fun buildPrompt(questionsText: String): String = """
请从下面这些学生提问里，提炼出他们最关心/最容易卡住的【核心知识点】。
要求：
1. 合并相同或相近的知识点，并统计它出现的次数（count = 提到该知识点的提问条数）。
2. 只输出一个严格的 JSON 数组，元素结构严格为 {"keyword":"知识点名","count":出现次数}。
3. 绝对不要输出任何 Markdown 标记（尤其不要出现 ```json 代码块围栏），也不要输出任何解释性文字，只输出 JSON 数组本身。
4. 如果提问里看不出明确知识点，就返回空数组 []。

学生提问列表：
$questionsText
""".trimIndent()

    fun close() = runCatching { client.close() }
}
