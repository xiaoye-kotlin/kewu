package com.kewu.app

import io.ktor.utils.io.readUTF8Line
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.request.preparePost
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
    // 2026-10-26 起与业务后端同一端口（8081 已并入 8080）
    private const val RELAY_URL = "http://47.99.42.168:8080/api/ai/answer"

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
     * ★ 2026-10-26 实测：openpangu 的思维链 reasoning_content **计入 max_tokens**（出 3 道题 2169 个输出 token
     *   里 1792 个是思维链）。上限给小了答案会被截断成空 / 半截 JSON —— 这就是「AI 生成题目用不了」的根因，
     *   所以默认 4096，出题 8192。
     * [maxTokens] 只限制最终回答（该模型的思维链不计入 max_tokens 限额风险较小，
     * 但回答偏长时适当调大）。
     */
    suspend fun chat(messages: List<Msg>, maxTokens: Int = 4096): String? {
        val endpoints = endpointOverride?.let { listOf(it) } ?: ENDPOINTS
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        for (ep in endpoints) {
            // 5 分钟内连不上过的接入点直接跳过（旧版每次都要白等直连 + 局域网各 8s 握手超时）
            if ((unreachableUntil[ep] ?: 0L) > now) continue
            val r = chatOnce(ep, messages, maxTokens)
            if (r != null) return r
        }
        return relayOnce(messages, maxTokens)
    }

    /** 后端流式中转（2026-10-26）：SSE，`data: {"d":"片段"}` … `data: [DONE]`，失败 `data: {"error":"…"}`。 */
    private const val STREAM_RELAY_URL = "http://47.99.42.168:8080/api/ai/stream"

    /**
     * 流式对话（打字机效果，2026-10-26）：回答一段段到达就回调 [onDelta]（只含最终回答，不含思维链），
     * 返回完整回答；全部失败返回 null。顺序：直连 / 局域网代理（stream=true）→ 后端流式中转 → 非流式兜底（一次性回调全文）。
     * ★ 已经吐出过内容的通道中途断了不再换下一个（避免重复输出），直接返回已收到的部分。
     */
    suspend fun chatStream(messages: List<Msg>, maxTokens: Int = 4096, onDelta: (String) -> Unit): String? {
        val endpoints = endpointOverride?.let { listOf(it) } ?: ENDPOINTS
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        for (ep in endpoints) {
            if ((unreachableUntil[ep] ?: 0L) > now) continue
            val body = buildJsonObject {
                put("model", MODEL)
                put("stream", true)
                put("max_tokens", maxTokens)
                // ★ 2026-10-26 实测：openpangu 默认先输出一大段思维链（不计入可见回答），用户要干等 7 秒以上才见第一个字；
                //   thinking.type=disabled 后首字约 1–2 秒，流式才有「打字」的感觉。对话场景关掉思考。
                put("thinking", buildJsonObject { put("type", "disabled") })
                put("messages", msgsJson(messages))
            }
            val r = streamOnce(ep, body.toString(), auth = true, onDelta = onDelta)
            if (r != null) return r
        }
        val relayBody = buildJsonObject {
            put("maxTokens", maxTokens)
            put("thinking", false)
            put("messages", msgsJson(messages))
        }
        streamOnce(STREAM_RELAY_URL, relayBody.toString(), auth = false, onDelta = onDelta)?.let { return it }
        // 最后兜底：非流式（老后端 / 中转不可用时仍能回答，只是一次性出现）
        return chat(messages, maxTokens)?.also(onDelta)
    }

    private fun msgsJson(messages: List<Msg>) = buildJsonArray {
        messages.forEach { m ->
            add(buildJsonObject {
                put("role", m.role)
                put("content", m.content)
            })
        }
    }

    /**
     * 读一路 SSE：兼容 OpenAI 格式（choices[0].delta.content）与后端中转格式（{"d":…}）。
     * 没吐出任何内容就失败 → 返回 null（换下一个通道）；吐过内容 → 返回已累积文本。
     */
    private suspend fun streamOnce(url: String, body: String, auth: Boolean, onDelta: (String) -> Unit): String? {
        val acc = StringBuilder()
        return try {
            client.preparePost(url) {
                if (auth) header(HttpHeaders.Authorization, "Bearer $apiKey")
                header(HttpHeaders.Accept, "text/event-stream")
                contentType(ContentType.Application.Json)
                timeout {
                    requestTimeoutMillis = 180_000
                    connectTimeoutMillis = 8_000
                    socketTimeoutMillis = 90_000
                }
                setBody(body)
            }.execute { resp ->
                if (resp.status.value !in 200..299) {
                    lastError = "stream HTTP ${resp.status.value} @ $url"
                    return@execute
                }
                val ch = resp.bodyAsChannel()
                while (true) {
                    val line = ch.readUTF8Line() ?: break
                    val t = line.trim()
                    if (!t.startsWith("data:")) continue
                    val data = t.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val o = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: continue
                    if (o["error"] != null) {
                        lastError = "stream error @ $url: ${o["error"]}"
                        break
                    }
                    val piece = (o["d"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                        ?: (o["choices"] as? JsonArray)?.firstOrNull()?.let { c ->
                            ((c as? JsonObject)?.get("delta") as? JsonObject)?.get("content")
                                ?.let { if (it is kotlinx.serialization.json.JsonNull) null else it.jsonPrimitive.content }
                        }
                    if (!piece.isNullOrEmpty()) {
                        acc.append(piece)
                        onDelta(piece)
                    }
                }
            }
            acc.toString().takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logErr("stream", e)
            val name = e::class.simpleName.orEmpty()
            if (acc.isEmpty() && (name.contains("Connect") || name.contains("UnknownHost") || name.contains("NoRouteToHost"))) {
                unreachableUntil[url] = kotlin.time.Clock.System.now().toEpochMilliseconds() + 5 * 60_000L
            }
            acc.toString().takeIf { it.isNotBlank() }
        }
    }

    /** 接入点 → 「连不上」标记的失效时刻（只记网络层失败，HTTP 错误 / 空内容不算）。 */
    private val unreachableUntil = mutableMapOf<String, Long>()

    /**
     * 8081 中转：多轮消息压成 {question = 最后一条用户消息, context = system + 之前的对话}。
     * 服务器未配置 / 调用失败时也会回 200 + 可读文案，这里只认非空 answer。
     */
    private suspend fun relayOnce(messages: List<Msg>, maxTokens: Int): String? = try {
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
            timeout { requestTimeoutMillis = 180_000 }
            setBody(buildJsonObject {
                put("question", question)
                put("context", context)
                // 合并后的后端支持 maxTokens（旧 8081 写死 768，多题出题必失败）；服务端会夹在 256..8192
                put("maxTokens", maxTokens)
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
        // 只有「连不上」（连接被拒 / 握手超时 / DNS 失败）才 5 分钟内跳过这个接入点；
        // 读超时等「连上了但慢」的错误不算，避免把可用的直连误判掉
        val name = e::class.simpleName.orEmpty()
        if (name.contains("Connect") || name.contains("UnknownHost") || name.contains("NoRouteToHost")) {
            unreachableUntil[endpoint] = kotlin.time.Clock.System.now().toEpochMilliseconds() + 5 * 60_000L
        }
        null
    }

    /** 单轮问答便捷入口。 */
    suspend fun chat(system: String, user: String, maxTokens: Int = 4096): String? =
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
        val text = chat(system, spec, maxTokens = 8192) ?: return null
        val s = text.indexOf('[')
        val e = text.lastIndexOf(']')
        if (s < 0 || e <= s) return null
        return runCatching { QuestionStore.parseQuestionsJson(text.substring(s, e + 1)) }
            .getOrDefault(emptyList()).takeIf { it.isNotEmpty() }
    }

    /**
     * 只出 **1 道** 指定题型的题（2026-10-26）。
     * 背景：一次让模型出 5 道，思维链 + 答案约 3000 token、耗时 40s 以上；手机直连华为云被拦时走 8081 中转，
     * 而中转接口服务端 max_tokens 仅 768（实测 5 道题返回 `[]`），只有单题能稳定出完。所以 UI 按题型 / 数量
     * 拆成单题并行调用本函数（见 AssignmentQuestionUi.AiGenerateBlock）。
     * [angle]/[total] 让并行的同主题题目从不同角度出，减少重复。失败返回 null。
     */
    suspend fun generateOneQuestion(topic: String, type: QuestionType, angle: Int, total: Int): Question? {
        val rule = when (type) {
            QuestionType.SINGLE_CHOICE -> "options 为 4 个，以「A.」「B.」「C.」「D.」开头；answer 为唯一正确选项字母，如 \"B\""
            QuestionType.MULTIPLE_CHOICE -> "options 为 4–5 个，以「A.」「B.」…开头；answer 为 2 个及以上正确字母，逗号分隔，如 \"A,C\""
            QuestionType.TRUE_FALSE -> "options 固定为 [\"正确\",\"错误\"]；answer 为 \"true\" 或 \"false\""
            QuestionType.FILL_BLANK -> "stem 中用「____」表示空位，options 为 []；answer 为标准答案文本"
            QuestionType.SHORT_ANSWER -> "options 为 []；answer 为参考答案要点"
        }
        val system = """
你是课悟的出题助手。只出 1 道「${type.label}」，只输出一个严格的 JSON 对象（不要数组、不要任何其他文字、不要 markdown 代码块）：
{"type":"${type.value}","stem":"题干","options":[...],"answer":"...","score":分值整数,"analysis":"一句话解析"}
规则：$rule；score 选择/判断/填空 10，简答 20；题干与答案用中文，准确无歧义，避免政治/宗教/敏感内容。
""".trimIndent()
        val user = buildString {
            append("出题范围：").append(topic.ifBlank { "通用知识" })
            if (total > 1) append("\n这是同一范围的第 $angle / $total 道，请换一个与其它题不同的考查角度。")
        }
        val text = chat(system, user, maxTokens = 4096) ?: return null
        val s = text.indexOf('{')
        val e = text.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        return runCatching { QuestionStore.parseQuestionsJson("[" + text.substring(s, e + 1) + "]") }
            .getOrNull()?.firstOrNull()?.copy(type = type)
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
        val text = chat(system, user, maxTokens = 4096) ?: return null
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
