package com.kewu.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.AiClient
import com.kewu.app.HapticKind
import com.kewu.app.KewuBlue
import com.kewu.app.KewuGreen
import com.kewu.app.Question
import com.kewu.app.QuestionType
import com.kewu.app.haptic
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch

/*
 * 「小悟一题一辅」苏格拉底式作业辅导（2026-10-26，ICT 创新赛 AI 智能体功能）。
 *
 * 学生作答时，每道题都可以叫小悟；小悟按「提示阶梯」引导：
 *   ① 思路点拨（这题考什么、从哪下手）→ ② 关键一步（给出关键公式 / 概念，不算出结果）→ ③ 方向检查（看学生写的答案方向对不对，只说哪里有问题）。
 * 提交前绝不给出最终答案或正确选项（防代写）；每题求助次数随提交上报，老师在「学生作业详情」里看到学生卡在哪。
 * 对话按题保存在内存里，关掉面板再打开还在。
 */

/** 一条辅导消息。 */
internal data class TutorMsg(val fromAi: Boolean, val text: String)

/** 按「作业 id + 题目 id」保存的辅导对话（进程内）。 */
internal object TutorSessions {
    private val map = mutableMapOf<String, SnapshotStateList<TutorMsg>>()
    fun of(key: String): SnapshotStateList<TutorMsg> = map.getOrPut(key) { mutableStateListOf() }
}

private val TUTOR_SYSTEM = """你是课悟 App 的 AI 助教「小悟」，正在用苏格拉底式方法辅导一名学生做作业里的一道题。
铁律：
1. 绝不直接给出最终答案、正确选项字母、判断结论或完整解答；学生索要答案时，温和拒绝并换一种方式启发。
2. 每次只推进一小步：先问学生已经想到哪，再给提示；回复不超过 120 字，用中文，不用 Markdown 符号。
3. 按学生请求的提示等级回应：「思路点拨」只说考查点和切入方向；「关键一步」给出关键概念 / 公式 / 第一步，但不算出结果；「检查方向」只评价学生答案的思路对不对、哪里可能有问题，不说正确答案是什么。
4. 结尾用一个引导性小问题，让学生自己往下想。"""

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
) {
    KewuBottomSheet(show = show, onDismiss = onDismiss, title = "小悟 · 第 $index 题辅导") { _ ->
        TutorBody(sessionKey, question, courseName, currentAnswer, onUsed)
    }
}

@Composable
private fun TutorBody(
    sessionKey: String,
    question: Question,
    courseName: String,
    currentAnswer: String,
    onUsed: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    val msgs = remember(sessionKey) { TutorSessions.of(sessionKey) }
    var streaming by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    LaunchedEffect(msgs.size, streaming?.length) { scroll.animateScrollTo(scroll.maxValue) }

    fun ask(level: String, text: String) {
        if (streaming != null) return
        haptic(HapticKind.Light)
        msgs += TutorMsg(false, text)
        onUsed()
        streaming = ""
        val questionText = buildString {
            appendLine("课程：$courseName")
            appendLine("题型：${question.type.label}")
            appendLine("题目：${question.stem}")
            if (question.options.isNotEmpty()) appendLine("选项：" + question.options.mapIndexed { i, o -> "${'A' + i}. $o" }.joinToString("  "))
            appendLine("学生当前填写的答案：" + currentAnswer.ifBlank { "（还没作答）" })
            // 标准答案只给模型判断方向用，铁律已禁止泄露
            if (question.answer.isNotBlank()) appendLine("（仅供你判断方向，严禁透露）标准答案：${question.answer}")
        }
        val history = msgs.takeLast(8).map { AiClient.Msg(if (it.fromAi) "assistant" else "user", it.text) }
        scope.launch {
            val reply = AiClient.chatStream(
                listOf(AiClient.Msg("system", "$TUTOR_SYSTEM\n\n$questionText\n本次学生请求的提示等级：$level")) + history,
                maxTokens = 1024,
            ) { piece -> streaming = (streaming ?: "") + piece }
            msgs += TutorMsg(true, reply?.trim()?.ifBlank { null } ?: "小悟暂时连不上 AI 服务，请稍后再试～")
            streaming = null
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            "小悟只给提示、不给答案；你每次求助都会记录在作业里，老师能看到你在哪一步卡住。",
            color = cs.onSurfaceVariantSummary, fontSize = 12.sp, lineHeight = 17.sp,
        )
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 340.dp).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (msgs.isEmpty() && streaming == null) {
                Bubble(true, "这道题你目前想到哪一步了？可以先点下面的「思路点拨」，我陪你一步步想。")
            }
            msgs.forEach { Bubble(it.fromAi, it.text) }
            streaming?.let { Bubble(true, it.ifEmpty { "小悟正在想怎么提示你…" }) }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(selected = false, onClick = { ask("思路点拨", "给我一点思路") }, label = "① 思路点拨")
            Chip(selected = false, onClick = { ask("关键一步", "提示一下关键的一步") }, label = "② 关键一步")
            Chip(selected = false, onClick = {
                ask("检查方向", "我现在的答案是「${currentAnswer.ifBlank { "还没写" }}」，帮我看看方向对不对")
            }, label = "③ 检查方向")
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                input, { input = it }, Modifier.weight(1f),
                backgroundMode = TextFieldMode.Rectangle, singleLine = true, label = "说说你卡在哪…",
            )
            Spacer(Modifier.padding(4.dp))
            KewuPrimaryButton("发送", {
                val t = input.trim()
                if (t.isNotEmpty()) { input = ""; ask("学生追问", t) }
            }, enabled = input.isNotBlank() && streaming == null)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun Bubble(fromAi: Boolean, text: String) {
    val cs = COUITheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromAi) Arrangement.Start else Arrangement.End) {
        Box(
            Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(14.dp))
                .background(if (fromAi) KewuGreen.copy(alpha = 0.12f) else KewuBlue.copy(alpha = 0.14f))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Text(text, color = cs.onSurface, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

/** 题型名（错题本 / 规划等共用）。 */
internal val QuestionType.shortLabel: String get() = label
