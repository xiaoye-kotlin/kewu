package com.kewu.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.AiClient
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.Question
import com.kewu.app.QuestionType
import com.kewu.app.haptic
import com.kewu.app.kvGet
import com.kewu.app.kvSet
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/*
 * 「小悟一题一辅」苏格拉底式作业辅导（2026-10-26，ICT 创新赛 AI 智能体功能）。
 *
 * 学生作答时，每道题都可以叫小悟；小悟按「提示阶梯」引导：
 *   ① 思路点拨（这题考什么、从哪下手）→ ② 关键一步（给出关键公式 / 概念，不算出结果）→ ③ 方向检查（看学生写的答案方向对不对，只说哪里有问题）。
 * 提交前绝不给出最终答案或正确选项（防代写）；每题求助次数随提交上报，老师在「学生作业详情」里看到学生卡在哪。
 *
 * 2026-11-02：
 *   - 界面改用全 App 统一的 [KewuAiChatScreen]（原底部弹层输入框会被输入法挡住）；
 *   - 对话按「作业 id#题目 id」落盘保存（[TutorSessions]），AI 错题本分析错因时把这段对话一起交给 AI，
 *     让它知道学生当时是怎么想的、卡在哪一步。
 */

/** 一条辅导消息。 */
internal data class TutorMsg(val fromAi: Boolean, val text: String)

/** 按「作业 id#题目 id」保存的辅导对话（按账号落盘，换账号互不可见）。 */
internal object TutorSessions {
    private val cache = mutableMapOf<String, List<TutorMsg>>()
    private val json = Json { ignoreUnknownKeys = true }

    private fun storeKey(key: String) = "kewu_tutor_" + KewuApi.username.orEmpty() + "_" + key

    fun of(key: String): List<TutorMsg> = cache.getOrPut(key) {
        runCatching {
            (json.parseToJsonElement(kvGet(storeKey(key)) ?: return@getOrPut emptyList()) as JsonArray).map {
                val o = it.jsonObject
                TutorMsg(o["ai"]?.jsonPrimitive?.booleanOrNull == true, (o["t"] as? JsonPrimitive)?.content.orEmpty())
            }
        }.getOrDefault(emptyList())
    }

    fun save(key: String, msgs: List<TutorMsg>) {
        cache[key] = msgs
        kvSet(storeKey(key), buildJsonArray {
            msgs.takeLast(60).forEach { m -> add(buildJsonObject { put("ai", m.fromAi); put("t", m.text) }) }
        }.toString())
    }

    /** 给错题本用的对话文本（无对话返回空串）。 */
    fun transcript(key: String, maxChars: Int = 2500): String =
        of(key).joinToString("\n") { (if (it.fromAi) "小悟：" else "学生：") + it.text }.takeLast(maxChars)

    /** 课程 id → 最近课堂纪要（SIS 转写 + AI 整理），进程内缓存。 */
    val classNotes = mutableMapOf<String, String>()
}

private val TUTOR_SYSTEM = """你是课悟 App 的 AI 助教「小悟」，正在用苏格拉底式方法辅导一名学生做作业里的一道题。
铁律：
1. 绝不直接给出最终答案、正确选项字母、判断结论或完整解答；学生索要答案时，温和拒绝并换一种方式启发。
2. 每次只推进一小步：先问学生已经想到哪，再给提示；回复不超过 120 字，用中文，不用 Markdown 符号。
3. 按学生请求的提示等级回应：「思路点拨」只说考查点和切入方向；「关键一步」给出关键概念 / 公式 / 第一步，但不算出结果；「检查方向」只评价学生答案的思路对不对、哪里可能有问题，不说正确答案是什么。
4. 结尾用一个引导性小问题，让学生自己往下想。"""

/**
 * 作业里某道题的「问小悟」整页对话（统一对话界面）。[show] 为 false 时不渲染。
 * 放在作答页根 Box 的最后，盖住整页；返回键 / 顶栏返回关闭。
 */
@Composable
internal fun AiTutorSheet(
    show: Boolean,
    sessionKey: String,
    question: Question,
    index: Int,
    courseName: String,
    currentAnswer: String,
    onDismiss: () -> Unit,
    onUsed: () -> Unit,
    courseId: String = "",
) {
    if (!show) return
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    // 这门课最近的课堂纪要（下课后录音经华为云 SIS 转写 + AI 整理）：小悟按老师课上讲的方法来提示
    LaunchedEffect(courseId) {
        if (courseId.isNotBlank() && courseId !in TutorSessions.classNotes) {
            TutorSessions.classNotes[courseId] = KewuApi.getClassNotes(courseId, 2)
                .joinToString("\n") { (d, t) -> "$d：" + t.take(1200) }
        }
    }
    var msgs by remember(sessionKey) { mutableStateOf(TutorSessions.of(sessionKey)) }
    var streaming by remember { mutableStateOf<String?>(null) }
    val welcome = remember { newChatId() }
    // 每条消息的稳定 id（列表 key）：按下标派生，消息只追加不删除
    val baseId = remember(sessionKey) { newChatId() * 10 }

    fun ask(level: String, text: String, quote: String? = null) {
        if (streaming != null) return
        val shown = if (quote != null) "（引用：$quote）$text" else text
        msgs = msgs + TutorMsg(false, shown)
        TutorSessions.save(sessionKey, msgs)
        onUsed()
        streaming = ""
        val questionText = buildString {
            appendLine("课程：$courseName")
            appendLine("题型：${question.type.label}")
            appendLine("题目：${question.stem}")
            if (question.options.isNotEmpty()) appendLine("选项：" + question.options.mapIndexed { i, o -> "${'A' + i}. $o" }.joinToString("  "))
            appendLine("学生当前填写的答案：" + currentAnswer.ifBlank { "（还没作答）" })
            TutorSessions.classNotes[courseId]?.takeIf { it.isNotBlank() }?.let {
                appendLine("这门课最近的课堂纪要（优先用老师课上讲过的方法和例子来提示）：$it")
            }
            // 标准答案只给模型判断方向用，铁律已禁止泄露
            if (question.answer.isNotBlank()) appendLine("（仅供你判断方向，严禁透露）标准答案：${question.answer}")
        }
        val history = msgs.takeLast(8).map { AiClient.Msg(if (it.fromAi) "assistant" else "user", it.text) }
        scope.launch {
            val reply = AiClient.chatStream(
                listOf(AiClient.Msg("system", "$TUTOR_SYSTEM\n\n$questionText\n本次学生请求的提示等级：$level")) + history,
                maxTokens = 1024,
            ) { piece -> streaming = (streaming ?: "") + piece }
            msgs = msgs + TutorMsg(true, reply?.trim()?.ifBlank { null } ?: "小悟暂时连不上 AI 服务，请稍后再试～")
            TutorSessions.save(sessionKey, msgs)
            streaming = null
        }
    }

    val chatMsgs = buildList {
        if (msgs.isEmpty()) add(AiChatMsg("这道题你目前想到哪一步了？可以先点下面的「思路点拨」，我陪你一步步想。", isUser = false, id = welcome))
        msgs.forEachIndexed { i, m -> add(AiChatMsg(m.text, isUser = !m.fromAi, id = baseId + i)) }
    }
    KewuAiChatScreen(
        title = "小悟 · 第 $index 题辅导",
        onBack = onDismiss,
        messages = chatMsgs,
        streamText = streaming,
        placeholder = "说说你卡在哪…",
        thinkingText = "小悟正在想怎么提示你…",
        onSend = { text, quote -> ask("学生追问", text.ifBlank { "帮我看看这段" }, quote) },
        header = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Text(
                    "第 $index 题 · ${question.type.label}：${question.stem}",
                    color = cs.onSurface, fontSize = 13.5.sp, lineHeight = 20.sp, maxLines = 4,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "小悟只给提示、不给答案；你每次求助都会记录在作业里，老师能看到你在哪一步卡住。这段对话会保存，作业批改后 AI 错题本会结合它分析你的错因。",
                    color = cs.onSurfaceVariantSummary, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
        },
        chips = {
            Chip(selected = false, onClick = { haptic(HapticKind.Tick); ask("思路点拨", "给我一点思路") }, label = "① 思路点拨")
            Chip(selected = false, onClick = { haptic(HapticKind.Tick); ask("关键一步", "提示一下关键的一步") }, label = "② 关键一步")
            Chip(selected = false, onClick = {
                haptic(HapticKind.Tick)
                ask("检查方向", "我现在的答案是「${currentAnswer.ifBlank { "还没写" }}」，帮我看看方向对不对")
            }, label = "③ 检查方向")
        },
    )
}

/** 题型名（错题本 / 规划等共用）。 */
internal val QuestionType.shortLabel: String get() = label
