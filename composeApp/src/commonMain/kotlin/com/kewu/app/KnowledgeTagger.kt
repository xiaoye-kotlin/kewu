package com.kewu.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 作业 → 知识点（2026-11-03）。老师的作业标题常常只是「第三次作业」「11.2 课后练习」，
 * 拿标题当知识点既看不出薄弱在哪，搜出来的视频也不对题。这里让 AI 读标题 + 说明 + 每道题的题干，
 * 给**每道题**标 1–2 个具体知识点（如「矩阵的秩」「泰勒公式」），学习规划按题统计掌握度、按知识点搜视频。
 *
 * 结果按作业 id 永久缓存在本地 kv（作业发布后题目不变）；AI 失败不缓存，下次进页面再试。
 */
object KnowledgeTagger {

    /** 标注结果：[perQuestion] 题目序号（从 0 起，字符串）→ 知识点；没有结构化题目时用 [whole]。 */
    data class Tags(val perQuestion: Map<String, List<String>>, val whole: List<String>) {
        val all: List<String> get() = (perQuestion.values.flatten() + whole).distinct()
    }

    private fun key(assignmentId: String) = "kewu_kp1_$assignmentId"

    /** 某道题的知识点：按题目 id 找到它在作业里的序号；找不到用提交结果里的顺序 [fallbackIndex]。 */
    fun pointsOf(t: Tags, a: AssignmentInfo, questionId: String, fallbackIndex: Int): List<String> {
        val i = a.questions.indexOfFirst { it.id == questionId && questionId.isNotBlank() }.takeIf { it >= 0 } ?: fallbackIndex
        return t.perQuestion[i.toString()].orEmpty()
    }
    /** 同时最多 3 个识别请求（作业多时并行，又不至于一下打满 AI）。 */
    private val gate = Semaphore(3)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 只读缓存（界面同步取用）。 */
    fun cached(assignmentId: String): Tags? = kvGet(key(assignmentId))?.let { decode(it) }

    /** 有缓存直接返回；否则调 AI 标注并缓存。失败返回 null。 */
    suspend fun tag(a: AssignmentInfo): Tags? {
        cached(a.id)?.let { return it }
        return gate.withPermit {
            cached(a.id) ?: runAi(a)?.also { kvSet(key(a.id), encode(it)) }
        }
    }

    private suspend fun runAi(a: AssignmentInfo): Tags? = try {
        val qs = a.questions
        val desc = a.description.substringBefore("<!--").substringBefore("[[").trim().take(400)
        val user = buildString {
            appendLine("作业标题：${a.title}")
            if (desc.isNotBlank()) appendLine("作业说明：$desc")
            if (a.knowledgePoints.isNotEmpty()) appendLine("老师填写的知识点（可参考、可细化）：${a.knowledgePoints.joinToString("、")}")
            if (qs.isNotEmpty()) {
                appendLine("题目：")
                qs.forEachIndexed { i, q ->
                    val opts = if (q.options.isNotEmpty()) "（选项：" + q.options.joinToString(" ").take(120) + "）" else ""
                    appendLine("${i + 1}. ${q.stem.take(200)}$opts")
                }
            }
        }
        val system = if (qs.isNotEmpty()) {
            "你是课悟的知识点识别助手。读作业标题、说明和每道题，识别每道题考查的具体知识点。" +
                "只输出一个 JSON 对象，不要任何其他文字、不要 markdown 代码块：{\"q\":[[\"知识点\"],[\"知识点\",\"知识点\"],...]}，" +
                "q 的长度必须等于题目数，按题号顺序，每题 1–2 个知识点。" +
                "知识点是 2–10 个字的标准课程术语（如「矩阵的秩」「泰勒公式」「链式法则」），能直接拿去搜教学视频；" +
                "不要用作业标题、章节号、「第几次作业」「综合练习」这类词；不同题考同一个点时用完全相同的名称；全份作业去重后不超过 6 个。"
        } else {
            "你是课悟的知识点识别助手。根据作业标题和说明，总结这份作业考查的具体知识点。" +
                "只输出一个 JSON 对象，不要任何其他文字、不要 markdown 代码块：{\"all\":[\"知识点\",...]}，1–4 个。" +
                "知识点是 2–10 个字的标准课程术语（如「矩阵的秩」「泰勒公式」），能直接拿去搜教学视频；不要照抄作业标题或「第几次作业」这类词。"
        }
        val text = AiClient.chat(system, user, maxTokens = 600)
        text?.let { parse(it, qs) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun clean(p: String) = p.trim().trim('「', '」', '"', '《', '》', '。', '，', ',').take(16)

    private fun parse(text: String, qs: List<Question>): Tags? {
        val s = text.indexOf('{')
        val e = text.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        val obj = runCatching { json.parseToJsonElement(text.substring(s, e + 1)) as? JsonObject }.getOrNull() ?: return null
        fun strs(el: Any?): List<String> = (el as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content?.let(::clean) }.filter { it.isNotBlank() }.distinct()
        val q = (obj["q"] as? JsonArray).orEmpty()
        val per = qs.indices.mapNotNull { i -> strs(q.getOrNull(i)).take(2).takeIf { it.isNotEmpty() }?.let { i.toString() to it } }.toMap()
        val whole = strs(obj["all"]).take(4)
        return Tags(per, whole).takeIf { it.all.isNotEmpty() }
    }

    private fun encode(t: Tags): String = buildJsonObject {
        put("q", buildJsonObject {
            t.perQuestion.forEach { (id, ps) -> put(id, buildJsonArray { ps.forEach { add(JsonPrimitive(it)) } }) }
        })
        put("all", buildJsonArray { t.whole.forEach { add(JsonPrimitive(it)) } })
    }.toString()

    private fun decode(s: String): Tags? = runCatching {
        val o = json.parseToJsonElement(s) as JsonObject
        val per = (o["q"] as? JsonObject).orEmpty().mapValues { (_, v) -> (v as JsonArray).map { it.jsonPrimitive.content } }
        val whole = (o["all"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
        Tags(per, whole)
    }.getOrNull()
}
