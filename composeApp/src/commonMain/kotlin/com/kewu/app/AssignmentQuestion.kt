package com.kewu.app

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
 * 作业题目模型与序列化（2026-10-25 作业题目系统）。
 *
 * 后端目前（接口文档 3.2 / 3.11）的 Assignment 只有 title/description/deadline/totalScore/
 * knowledgePoints，**没有题目结构化字段**。为让题目化作业立刻可用，本系统采用：
 *
 *   - 发布时把题目列表序列化进 `description` 末尾的 `[KEWU_QUESTIONS]…[/KEWU_QUESTIONS]` 块；
 *   - 学生作答时把答案序列化进 `content` 末尾的 `[KEWU_ANSWERS]…[/KEWU_ANSWERS]` 块；
 *   - 后端原样透传 description/content，App 两端解析渲染。
 *
 * 同时 [com.kewu.app.KewuApi] 的 AssignmentInfo 已预留 `questions` 字段：后端若落地原生字
 * 段，则优先用原生 questions，否则回退到从 description 提取。后端需求见项目根
 * `作业题目系统-后端对接需求.md`。
 *
 * 项目无 kotlinx.serialization 编译插件，全部手工拼/解析 JSON（与 KewuApi 一致）。
 */
enum class QuestionType(val value: String, val label: String) {
    SINGLE_CHOICE("SINGLE_CHOICE", "单选题"),
    MULTIPLE_CHOICE("MULTIPLE_CHOICE", "多选题"),
    TRUE_FALSE("TRUE_FALSE", "判断题"),
    FILL_BLANK("FILL_BLANK", "填空题"),
    SHORT_ANSWER("SHORT_ANSWER", "简答题");

    companion object {
        fun from(v: String?): QuestionType =
            entries.firstOrNull { it.value == v } ?: SHORT_ANSWER
    }
}

data class Question(
    val id: String,
    val type: QuestionType,
    val stem: String,
    val options: List<String> = emptyList(),
    val answer: String = "",
    val score: Int = 0,
    val analysis: String = "",
) {
    /** 客观题（可自动/半自动判定）：选择 / 判断 / 多选 / 填空。简答题为主观，需老师判。 */
    val isObjective: Boolean
        get() = type == QuestionType.SINGLE_CHOICE || type == QuestionType.TRUE_FALSE ||
                type == QuestionType.MULTIPLE_CHOICE || type == QuestionType.FILL_BLANK
}

/** 判断题答案归一化：true/false/正确/错误/对/错 → 选项标签「正确」/「错误」。 */
private fun normalizeTrueFalse(raw: String): String = when (raw.trim().lowercase()) {
    "true", "正确", "对", "t", "yes" -> "正确"
    "false", "错误", "错", "f", "no" -> "错误"
    else -> raw
}

object QuestionStore {
    const val MARK_OPEN = "[KEWU_QUESTIONS]"
    const val MARK_CLOSE = "[/KEWU_QUESTIONS]"
    const val ANS_OPEN = "[KEWU_ANSWERS]"
    const val ANS_CLOSE = "[/KEWU_ANSWERS]"

    private val json = Json { ignoreUnknownKeys = true }

    // ── description 透传（题目） ──

    /** 把题目 JSON 块附到 description 末尾（后端未上线题目字段前）。 */
    fun embedInDescription(desc: String, qs: List<Question>): String {
        if (qs.isEmpty()) return desc
        return (desc.trim() + "\n\n" + MARK_OPEN + questionsToJson(qs) + MARK_CLOSE).trim()
    }

    /** 从 description 提取题目；无标记返回空。 */
    fun extractFromDescription(desc: String): List<Question> {
        val s = desc.indexOf(MARK_OPEN)
        val e = desc.indexOf(MARK_CLOSE)
        if (s < 0 || e <= s) return emptyList()
        return runCatching { parseQuestionsJson(desc.substring(s + MARK_OPEN.length, e)) }
            .getOrDefault(emptyList())
    }

    fun questionsToJson(qs: List<Question>): String = buildJsonArray {
        qs.forEach { q ->
            add(buildJsonObject {
                put("id", q.id)
                put("type", q.type.value)
                put("stem", q.stem)
                if (q.options.isNotEmpty())
                    put("options", buildJsonArray { q.options.forEach { add(it) } })
                if (q.answer.isNotBlank()) put("answer", q.answer)
                if (q.score > 0) put("score", q.score)
                if (q.analysis.isNotBlank()) put("analysis", q.analysis)
            })
        }
    }.toString()

    fun parseQuestionsJson(raw: String): List<Question> {
        val arr = json.parseToJsonElement(raw.trim()).jsonArray
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val type = QuestionType.from(o["type"]?.jsonPrimitive?.content)
            val stem = o["stem"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val options = (o["options"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.content }.orEmpty()
            val id = o["id"]?.jsonPrimitive?.content ?: ("q${(el.hashCode().toString().take(6))}")
            // 判断题答案归一化为选项标签（正确/错误），与教师端手工编辑、学生端渲染统一口径
            val rawAnswer = o["answer"]?.jsonPrimitive?.content ?: ""
            val answer = if (type == QuestionType.TRUE_FALSE) normalizeTrueFalse(rawAnswer) else rawAnswer
            Question(
                id = id, type = type, stem = stem,
                options = if (type == QuestionType.TRUE_FALSE && options.isEmpty()) listOf("正确", "错误") else options,
                answer = answer,
                score = o["score"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt() ?: 0, // AI 可能给 20.0
                analysis = o["analysis"]?.jsonPrimitive?.content ?: "",
            )
        }
    }

    // ── content 透传（作答） ──

    fun answersToContent(answers: Map<String, String>): String = buildString {
        append(ANS_OPEN)
        append(buildJsonArray {
            answers.forEach { (k, v) ->
                add(buildJsonObject { put("questionId", k); put("value", v) })
            }
        }.toString())
        append(ANS_CLOSE)
    }

    fun parseAnswers(content: String): Map<String, String> {
        val s = content.indexOf(ANS_OPEN)
        val e = content.indexOf(ANS_CLOSE)
        if (s < 0 || e <= s) return emptyMap()
        return runCatching {
            json.parseToJsonElement(content.substring(s + ANS_OPEN.length, e)).jsonArray
                .mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val k = o["questionId"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    k to (o["value"]?.jsonPrimitive?.content ?: "")
                }.toMap()
        }.getOrDefault(emptyMap())
    }
}
