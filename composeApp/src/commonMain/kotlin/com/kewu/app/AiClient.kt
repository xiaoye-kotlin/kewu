package com.kewu.app

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 课悟统一 AI 客户端（OpenAI 兼容协议，commonMain）。
 *
 * 接入 ModelArts MaaS 大模型服务（openpangu-2.0-flash），替换此前各页面的
 * 本地规则 mock / 占位回复。业务后端（8080）的 AI 相关接口目前仍是规则引擎
 * （见后端接口文档「已知限制」），因此需要真实大模型语义的入口统一走这里：
 *   - 教师端「小悟 · 教学洞察」问答（TeacherAssistantPage）
 *   - 学生端「小悟」问答（TaPage）
 *   - 作业 AI 预批阅（AssignmentHub 学生端 / TeacherTaskPage 教师端，
 *     输出结构与后端 AiSuggestion 完全一致，UI 无需改动）
 *
 * 注意：
 *   1. 模型会返回 reasoning_content（思维链），只取 choices[0].message.content；
 *   2. 本项目没开 kotlinx.serialization 编译插件，请求/响应一律手工拼 JSON +
 *      [Json.parseToJsonElement] 解析（与 KewuApi 同款写法）；
 *   3. 所有调用失败返回 null，由调用方降级（本地规则 / 后端规则引擎 / 占位文案），
 *      绝不上抛异常打断 UI；
 *   4. API Key 默认内置（用户 2026-10-24 提供），可用 kv `kewu_ai_key` 覆盖；
 *      长期建议改为业务后端代理转发，避免密钥随 APK 分发。
 */
object AiClient {

    /**
     * 直连接入点（OpenAI 兼容），按顺序尝试，成功即止。
     * 1) ModelArts 官方端点（固定配置，见交接文档 26.1）；
     * 2) 局域网调试代理（kewu_ai_proxy.py，PC 转发；仅开发机网络可达时有效）。
     * 两者都失败时再走 [RELAY_URL]（8081 业务后端的 MaaS 中转，手机能连上业务后端就能用 AI）。
     * 可用 kv `kewu_ai_endpoint` 覆盖为单一直连端点（总控端「自定义 AI 模型」预留口）。
     */
    private val ENDPOINTS = listOf(
        "https://api.modelarts-maas.com/v2/chat/completions",
        "http://192.168.100.104:8377/v2/chat/completions",
    )

    /**
     * 兜底中转：8081 `POST /api/ai/answer {question, context}` → `{answer}`（接口文档 10.11.2，
     * 服务器用自己的环境变量调同一个 MaaS）。部分校园网 / 运营商会拦 api.modelarts-maas.com:443，
     * 而业务后端 47.99.42.168 是 App 必须能连的，所以把它放在最后兜底（2026-10-26 用户反馈「AI 又不可用」）。
     */
    private const val RELAY_URL = "http://47.99.42.168:8081/api/ai/answer"

    private val endpointOverride: String? = kvGet("kewu_ai_endpoint")?.takeIf { it.isNotBlank() }

    private const val MODEL = "openpangu-2.0-flash"

    /** 内置默认 Key（用户提供的 ModelArts MaaS Key）。 */
    private const val DEFAULT_KEY =
        "L9rOhF9l7GfmwTGw4yhhzSImYTg5MXE83fApkEfAgznIbkdNMh0t8s9KfdOypVLNlTjdzRfuKr0s4xRoRphroQ"

    /** 运行时 Key：优先 kv 覆盖值，便于不发版换 Key。 */
    var apiKey: String = kvGet("kewu_ai_key")?.takeIf { it.isNotBlank() } ?: DEFAULT_KEY
        set(value) {
            field = value
            kvSet("kewu_ai_key", value)
        }

    // 共享引擎 + 显式安装 HttpTimeout：Ktor 的 per-request `timeout {}` 必须配合该插件，
    // 否则抛 IllegalArgumentException（2026-10-24 真机踩坑）
    private val client = platformHttpClient().config {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 120_000
        }
    }
    private val json = Json { ignoreUnknownKeys = true }

    /** 最近一次调用的错误描述（调试用；成功置 null）。 */
    @Volatile
    var lastError: String? = null
        private set

    private fun logErr(where: String, e: Exception) {
        lastError = "$where: ${e.javaClass.simpleName}: ${e.message}"
        println("[AiClient] $where failed -> $lastError")
    }

    data class Msg(val role: String, val content: String)

    /**
     * 多轮对话。返回助手文本；任何失败（网络/鉴权/解析）返回 null。
     * [maxTokens] 只限制最终回答（该模型的思维链不计入 max_tokens 限额风险较小，
     * 但回答偏长时适当调大）。
     */
    suspend fun chat(messages: List<Msg>, maxTokens: Int = 1200): String? {
        val endpoints = endpointOverride?.let { listOf(it) } ?: ENDPOINTS
        for (ep in endpoints) {
            val r = chatOnce(ep, messages, maxTokens)
            if (r != null) return r
        }
        return relayOnce(messages)
    }

    /**
     * 8081 中转：多轮消息压成 {question = 最后一条用户消息, context = system + 之前的对话}。
     * 服务器未配置 / 调用失败时也会回 200 + 可读文案，这里只认非空 answer。
     */
    private suspend fun relayOnce(messages: List<Msg>): String? = try {
        val lastUser = messages.indexOfLast { it.role == "user" }
        val question = messages.getOrNull(lastUser)?.content.orEmpty()
        val context = buildString {
            messages.forEachIndexed { i, m ->
                if (i == lastUser) return@forEachIndexed
                when (m.role) {
                    "system" -> appendLine(m.content)
                    "user" -> appendLine("【用户】${m.content}")
                    else -> appendLine("【助手】${m.content}")
                }
            }
            appendLine("（以上是你的身份设定与之前的对话，请据此直接回答最新的问题，不要局限于课堂内容。）")
        }
        val resp = client.post(RELAY_URL) {
            contentType(ContentType.Application.Json)
            timeout { requestTimeoutMillis = 120_000 }
            setBody(buildJsonObject {
                put("question", question)
                put("context", context)
            }.toString())
        }
        val bodyText = resp.bodyAsText()
        val answer = if (resp.status.value in 200..299) {
            (json.parseToJsonElement(bodyText) as? JsonObject)?.get("answer")
                ?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        } else null
        if (answer == null) lastError = "relay HTTP ${resp.status.value}: ${bodyText.take(200)}" else lastError = null
        answer
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logErr("relay", e)
        null
    }

    private suspend fun chatOnce(endpoint: String, messages: List<Msg>, maxTokens: Int): String? = try {
        val body = buildJsonObject {
            put("model", MODEL)
            put("stream", false)
            put("max_tokens", maxTokens)
            put("messages", buildJsonArray {
                messages.forEach { m ->
                    add(buildJsonObject {
                        put("role", m.role)
                        put("content", m.content)
                    })
                }
            })
        }
        val resp = client.post(endpoint) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            // 大模型推理耗时远长于业务接口，单请求超时放宽到 120s
            // 直连握手 8s 就放弃：被网络拦截时尽快落到下一个接入点，不让用户干等
            timeout {
                requestTimeoutMillis = 120_000
                connectTimeoutMillis = 8_000
            }
            setBody(body.toString())
        }
        val bodyText = resp.bodyAsText()
        if (resp.status.value !in 200..299) {
            lastError = "HTTP ${resp.status.value} @ $endpoint: ${bodyText.take(200)}"
            println("[AiClient] $lastError")
            return null
        }
        println("[AiClient] chat HTTP ok, ${bodyText.length} chars")
        val root = json.parseToJsonElement(bodyText).jsonObject
        val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
            ?.trim()?.takeIf { it.isNotEmpty() }
        if (content == null) {
            lastError = "empty content in: ${bodyText.take(200)}"
            println("[AiClient] empty content")
        } else {
            lastError = null
        }
        content
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logErr("chat", e)
        null
    }

    /** 单轮问答便捷入口。 */
    suspend fun chat(system: String, user: String, maxTokens: Int = 1200): String? =
        chat(listOf(Msg("system", system), Msg("user", user)), maxTokens)

    // ───────────────────────── 作业题目生成 ─────────────────────────

    /**
     * 按老师的「知识点范围 + 题型/数量要求」生成结构化题目。
     * 返回 [Question] 列表（解析失败返回 null）。题型 type 取值与 [QuestionType] 对齐。
     */
    suspend fun generateQuestions(spec: String): List<Question>? {
        val system = """
你是课悟的出题助手。根据老师给的知识点范围和题型要求，生成一套题目。
只输出一个严格的 JSON 数组（不要输出任何其他文字，不要使用 markdown 代码块）。
每个元素结构：
{"type":"SINGLE_CHOICE","stem":"题干","options":["A.xxx","B.xxx","C.xxx","D.xxx"],"answer":"B","score":20,"analysis":"解析"}
题型 type 取值：SINGLE_CHOICE 单选 / MULTIPLE_CHOICE 多选 / TRUE_FALSE 判断 / FILL_BLANK 填空 / SHORT_ANSWER 简答。
规则：
· 单选题 options 用「A.」「B.」「C.」「D.」开头，answer 为正确选项序号（如 "B"）；
· 多选题 options 同上，answer 用逗号分隔的多个序号（如 "A,C"）；
· 判断题 options 固定为 ["正确","错误"]，answer 为 "true" 或 "false"；
· 填空题 stem 里用「____」表示空格，answer 给参考答案文本；
· 简答题 answer 给参考答案要点文本；
· score 为每题分值（整数），各题分值之和贴近总合理范围；analysis 给一句话解析（可选）。
题干与答案必须用中文，表述准确、无歧义，避免政治/宗教/敏感内容。
""".trimIndent()
        val text = chat(system, spec, maxTokens = 2200) ?: return null
        val s = text.indexOf('[')
        val e = text.lastIndexOf(']')
        if (s < 0 || e <= s) return null
        return runCatching { QuestionStore.parseQuestionsJson(text.substring(s, e + 1)) }
            .getOrDefault(emptyList()).takeIf { it.isNotEmpty() }
    }

    // ───────────────────────── 作业 AI 预批阅 ─────────────────────────

    /**
     * 真实大模型作业预批阅：输入作业要求 + 学生提交内容，输出与后端
     * [AiSuggestion] 完全同构的结果（UI 层零改动）。解析失败返回 null（调用方
     * 可回退后端规则引擎 KewuApi.aiPrereview）。
     */
    suspend fun gradeSubmission(assignment: AssignmentInfo, submission: SubmissionInfo): AiSuggestion? {
        val kps = assignment.knowledgePoints.joinToString("、").ifBlank { "未标注" }
        val system = """
            你是课悟的作业批阅助手。根据作业要求和学生提交内容给出预批阅意见。
            只输出一个 JSON 对象，不要输出任何其他文字、不要 markdown 代码块。字段：
            {
              "suggestedScore": 0 到 totalScore 之间的数字,
              "confidence": 0 到 1 之间的数字，表示你对此评分的把握,
              "hitKnowledgePoints": [掌握较好的知识点字符串数组],
              "missedKnowledgePoints": [缺失或薄弱的知识点字符串数组],
              "reasons": [{"item":"评分维度","detail":"一句话依据","score":该维度得分}],
              "riskLevel": "LOW" 或 "MEDIUM" 或 "HIGH"（内容过短/跑题/疑似抄袭为 HIGH）,
              "summary": "80 字以内的总评，语气客观、面向老师"
            }
        """.trimIndent()
        val user = buildString {
            appendLine("【作业标题】${assignment.title}")
            if (assignment.description.isNotBlank()) appendLine("【作业要求】${assignment.description}")
            appendLine("【满分】${assignment.totalScore}")
            appendLine("【知识点】$kps")
            appendLine("【学生提交内容（第 ${submission.version} 版）】")
            append(submission.content.ifBlank { "（无文字内容）" }.take(3000))
        }
        val text = chat(system, user, maxTokens = 1500) ?: return null
        return parseGradeJson(text, assignment.totalScore)
    }

    /** 宽容解析模型输出的 JSON（允许首尾多余文字/```json 代码块包裹）。 */
    private fun parseGradeJson(raw: String, totalScore: Int): AiSuggestion? = try {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
        fun num(key: String): Double? = (o[key] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.toDoubleOrNull()
        fun strList(key: String): List<String> =
            (o[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.content.takeIf(String::isNotBlank) }
                ?: emptyList()
        val reasons = (o["reasons"] as? JsonArray)?.mapNotNull { el ->
            val r = el as? JsonObject ?: return@mapNotNull null
            val item = r["item"]?.jsonPrimitive?.content ?: return@mapNotNull null
            AiReason(
                item = item,
                detail = r["detail"]?.jsonPrimitive?.content ?: "",
                score = (r["score"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content?.toDoubleOrNull() ?: 0.0,
            )
        } ?: emptyList()
        AiSuggestion(
            suggestedScore = (num("suggestedScore") ?: return null).coerceIn(0.0, totalScore.toDouble()),
            confidence = (num("confidence") ?: 0.7).coerceIn(0.0, 1.0),
            hitKnowledgePoints = strList("hitKnowledgePoints"),
            missedKnowledgePoints = strList("missedKnowledgePoints"),
            reasons = reasons,
            riskLevel = o["riskLevel"]?.jsonPrimitive?.content?.uppercase()
                ?.takeIf { it == "LOW" || it == "MEDIUM" || it == "HIGH" } ?: "LOW",
            summary = o["summary"]?.jsonPrimitive?.content ?: "",
        )
    } catch (_: Exception) {
        null
    }
}
