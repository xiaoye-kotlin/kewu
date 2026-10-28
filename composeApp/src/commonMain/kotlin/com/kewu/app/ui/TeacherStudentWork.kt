package com.kewu.app.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.AiClient
import com.kewu.app.AssignmentInfo
import com.kewu.app.KewuApi
import com.kewu.app.KewuBlue
import com.kewu.app.KewuGreen
import com.kewu.app.SubmissionInfo
import com.kewu.app.kvGet
import com.kewu.app.kvSet
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/*
 * 教师端「按学生看作业」（2026-10-26）：
 *   - [StudentWorkList]：作业详情页里的「学生作业」分区，每人一行（正确率 / 得分 / 用时 / 状态），点进详情；
 *   - [StudentWorkScreen]：单个学生的作业详情 —— 用时、正确率、得分、全班对比（排名 / 用时快慢）、
 *     题型掌握、逐题作答与判定、附件，以及 AI 学情总结（含「亮点 / 创新点」与指导建议）。
 * 数据全部来自 GET /api/assignments/{id}/submissions（一次拿全班，用于对比）+ GET /api/assignments/{id}。
 */

/** 一份提交的客观指标（列表行与详情页共用）。 */
internal data class WorkMetrics(
    val judged: Int,           // 已判定的题数（客观题自动判 + 老师判过的主观题）
    val correct: Int,          // 判对的题数
    val gotScore: Double?,     // 得分：老师定分优先，否则按逐题得分求和（没有逐题结果为 null）
    val durationSec: Int?,
) {
    val accuracy: Double? get() = if (judged > 0) correct.toDouble() / judged else null
}

internal fun SubmissionInfo.metrics(): WorkMetrics {
    val judgedList = questionResults.filter { it.correct != null }
    val sumScore = questionResults.takeIf { it.isNotEmpty() }?.sumOf { it.finalScore ?: it.autoScore }
    return WorkMetrics(
        judged = judgedList.size,
        correct = judgedList.count { it.correct == true },
        gotScore = score ?: sumScore,
        durationSec = durationSec,
    )
}

internal fun fmtDuration(sec: Int?): String = when {
    sec == null -> "未记录"
    sec < 60 -> "${sec} 秒"
    sec < 3600 -> "${sec / 60} 分 ${sec % 60} 秒"
    else -> "${sec / 3600} 小时 ${(sec % 3600) / 60} 分"
}

private fun fmtPct(v: Double?): String = v?.let { "${(it * 100).toInt()}%" } ?: "—"

private fun fmtNum(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else ((v * 10).toLong() / 10.0).toString()

private fun statusLabel(status: String): Pair<String, Color> = when (status) {
    "REVIEWED" -> "已批阅" to KewuGreen
    "RETURNED" -> "已打回" to Color(0xFFE5484D)
    else -> "待批阅" to KewuBlue
}

// ───────────────────────── 作业详情页里的「学生作业」列表 ─────────────────────────

/** 学生作业列表（coui 卡组，一人一行）。按「待批阅在前、再按正确率高到低」排，方便老师先改没改的。 */
@Composable
internal fun StudentWorkList(subs: List<SubmissionInfo>?, totalScore: Int, onOpen: (SubmissionInfo) -> Unit) {
    val cs = COUITheme.colorScheme
    when {
        subs == null -> KewuLoading(text = "正在加载学生作业…")
        subs.isEmpty() -> KewuEmpty("还没有学生提交")
        else -> KewuCardGroup(Modifier.padding(horizontal = 12.dp)) {
            val sorted = subs.sortedWith(
                compareBy<SubmissionInfo>({ if (it.status == "PENDING") 0 else 1 }, { -(it.metrics().accuracy ?: -1.0) }),
            )
            sorted.forEachIndexed { i, s ->
                val m = s.metrics()
                val (label, color) = statusLabel(s.status)
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(s) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 姓名首字头像
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(KewuBlue.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(s.studentName.ifBlank { s.studentId }.take(1), color = KewuBlue, fontSize = 15.sp,
                            fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.studentName.ifBlank { s.studentId }, color = cs.onSurface, fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            buildString {
                                append("正确率 ").append(fmtPct(m.accuracy))
                                append(" · 用时 ").append(fmtDuration(m.durationSec))
                                if (s.version > 1) append(" · 第 ${s.version} 版")
                            },
                            color = cs.onSurfaceSecondary, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            m.gotScore?.let { "${fmtNum(it)} / $totalScore" } ?: "— / $totalScore",
                            color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (i != sorted.lastIndex) {
                    Box(Modifier.fillMaxWidth().padding(start = 64.dp).height(0.5.dp).background(cs.dividerLine))
                }
            }
        }
    }
}

// ───────────────────────── 单个学生作业详情 ─────────────────────────

@Composable
internal fun StudentWorkScreen(
    assignmentId: String,
    submissionId: String,
    onBack: () -> Unit,
    onReview: (String) -> Unit,
) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    val pageBackdrop = rememberGlassBackdrop()

    var assignment by remember { mutableStateOf<AssignmentInfo?>(null) }
    var all by remember { mutableStateOf<List<SubmissionInfo>?>(null) }
    var loading by remember { mutableStateOf(true) }

    val reviewSaved by ReviewEvents.saved.collectAsState()
    LaunchedEffect(assignmentId, submissionId, reviewSaved) {
        val aD = async { KewuApi.getAssignment(assignmentId) }
        val sD = async { KewuApi.getSubmissions(assignmentId) }
        assignment = aD.await()
        all = sD.await()
        loading = false
    }
    val sub = all?.firstOrNull { it.id == submissionId }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().layerBackdrop(pageBackdrop).background(cs.surface))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 120.dp)) {
            KewuTopBar(sub?.studentName?.ifBlank { null }?.let { "$it 的作业" } ?: "学生作业", onBack = onBack)
            Spacer(Modifier.height(4.dp))
            val a = assignment
            when {
                loading -> KewuLoading(text = "正在加载…")
                a == null || sub == null -> KewuEmpty("没有找到这份提交，请返回刷新")
                else -> StudentWorkBody(a, sub, all.orEmpty(), pageBackdrop, onReview)
            }
        }
    }
}

@Composable
private fun StudentWorkBody(
    a: AssignmentInfo,
    sub: SubmissionInfo,
    all: List<SubmissionInfo>,
    backdrop: LayerBackdrop,
    onReview: (String) -> Unit,
) {
    val cs = COUITheme.colorScheme
    val m = sub.metrics()
    val (label, color) = statusLabel(sub.status)

    // 全班对比：正确率排名、用时与全班中位数比较
    val classAcc = all.mapNotNull { it.metrics().accuracy }
    val rank = m.accuracy?.let { acc -> classAcc.count { it > acc } + 1 }
    val durations = all.mapNotNull { it.durationSec }.sorted()
    val median = durations.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }

    // ── 顶部液态玻璃概览卡 ──
    KewuGlassCard(
        backdrop = backdrop, shape = RoundedCornerShape(22.dp), blurRadius = 22.dp, tintAlpha = 0.30f,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sub.studentName.ifBlank { sub.studentId }, color = cs.onSurface, fontSize = 20.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                KewuTag(label, color.copy(alpha = 0.14f), color)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "学号 ${sub.studentId} · ${a.title} · 提交于 ${sub.submittedAt}" + if (sub.version > 1) " · 第 ${sub.version} 版" else "",
                color = cs.onSurfaceSecondary, fontSize = 12.5.sp,
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCell("用时", fmtDuration(m.durationSec))
                MetricCell("正确率", fmtPct(m.accuracy) + if (m.judged > 0) "\n${m.correct}/${m.judged} 题" else "")
                MetricCell("得分", m.gotScore?.let { "${fmtNum(it)}\n/ ${a.totalScore}" } ?: "待批阅")
            }
        }
    }

    // ── 全班对比 ──
    Spacer(Modifier.height(14.dp))
    KewuSectionTitle("全班对比（${all.size} 人已交）")
    KewuCardGroup(Modifier.padding(horizontal = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            CompareLine("正确率排名", rank?.let { "第 $it / ${classAcc.size} 名" } ?: "暂无可判定的题")
            CompareLine("全班平均正确率", fmtPct(classAcc.takeIf { it.isNotEmpty() }?.average()))
            CompareLine(
                "作答速度",
                when {
                    m.durationSec == null || median == null -> "用时未记录"
                    m.durationSec < median * 0.6 -> "明显快于全班（中位 ${fmtDuration(median)}）"
                    m.durationSec > median * 1.5 -> "明显慢于全班（中位 ${fmtDuration(median)}）"
                    else -> "与全班接近（中位 ${fmtDuration(median)}）"
                },
            )
            if (sub.version > 1) CompareLine("修改次数", "共提交 ${sub.version} 版，学习投入度较高")
            // 「小悟一题一辅」求助记录：学生在哪几题向 AI 求助了几次（老师据此看出卡点）
            val helpTotal = sub.aiHelp.values.sum()
            CompareLine(
                "AI 辅导求助",
                if (helpTotal == 0) "作答时没有求助小悟"
                else "共 $helpTotal 次：" + a.questions.mapIndexedNotNull { i, q -> sub.aiHelp[q.id]?.let { "第 ${i + 1} 题 $it 次" } }.joinToString("、"),
            )
        }
    }

    // ── 题型掌握 ──
    val byType = sub.questionResults.filter { it.correct != null }.groupBy { it.type }
    if (byType.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        KewuSectionTitle("题型掌握")
        KewuCardGroup(Modifier.padding(horizontal = 12.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                byType.forEach { (type, rs) ->
                    val rate = rs.count { it.correct == true }.toDouble() / rs.size
                    MasteryBar(com.kewu.app.QuestionType.from(type).label, rate, "${rs.count { it.correct == true }}/${rs.size}")
                }
            }
        }
    }

    // ── AI 学情总结 ──
    Spacer(Modifier.height(14.dp))
    KewuSectionTitle("小悟 · AI 学情总结")
    AiWorkSummaryCard(a, sub, rank, classAcc.size, median)

    // ── 逐题作答 ──
    if (a.questions.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        KewuSectionTitle("逐题作答（共 ${a.questions.size} 题）")
        val answerMap = sub.answers.associate { it.questionId to it.value }
        val resultMap = sub.questionResults.associateBy { it.questionId }
        a.questions.forEachIndexed { qi, q ->
            QuestionAnswerCard(
                q = q, index = qi + 1, value = answerMap[q.id].orEmpty(), onValue = {},
                readOnly = true, result = resultMap[q.id],
            )
            Spacer(Modifier.height(10.dp))
        }
    }

    // ── 文字作答 / 附件 ──
    val showContent = a.questions.isEmpty() || sub.attachments.isNotEmpty()
    if (showContent) {
        Spacer(Modifier.height(14.dp))
        KewuSectionTitle(if (a.questions.isEmpty()) "作答内容" else "附件")
        KewuCardGroup(Modifier.padding(horizontal = 12.dp)) {
            Column(Modifier.padding(16.dp)) {
                if (a.questions.isEmpty()) {
                    Text(sub.content.ifBlank { "（无文字内容）" }, color = cs.onSurface, fontSize = 14.sp, lineHeight = 21.sp)
                }
                if (sub.attachments.isNotEmpty()) {
                    if (a.questions.isEmpty()) Spacer(Modifier.height(10.dp))
                    AttachmentList(sub.attachments)
                }
            }
        }
    }

    if (!sub.comment.isNullOrBlank()) {
        Spacer(Modifier.height(14.dp))
        KewuSectionTitle("老师评语")
        KewuCardGroup(Modifier.padding(horizontal = 12.dp)) {
            Text(sub.comment, color = cs.onSurface, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
        }
    }

    Spacer(Modifier.height(18.dp))
    KewuPrimaryButton(
        text = if (sub.status == "PENDING") "去批阅这份作业" else "重新批阅",
        onClick = { onReview(sub.id) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

@Composable
private fun RowScope.MetricCell(label: String, value: String) {
    val cs = COUITheme.colorScheme
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(cs.surface.copy(alpha = 0.55f))
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = cs.onSurfaceSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Text(value, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold, lineHeight = 19.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun CompareLine(label: String, value: String) {
    val cs = COUITheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, color = cs.onSurfaceSecondary, fontSize = 13.5.sp, modifier = Modifier.width(110.dp))
        Text(value, color = cs.onSurface, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MasteryBar(label: String, rate: Double, detail: String) {
    val cs = COUITheme.colorScheme
    val barColor = when {
        rate >= 0.8 -> KewuGreen
        rate >= 0.5 -> KewuBlue
        else -> Color(0xFFE5484D)
    }
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = cs.onSurface, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
            Text("$detail · ${(rate * 100).toInt()}%", color = cs.onSurfaceSecondary, fontSize = 12.5.sp)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.onSurface.copy(alpha = 0.08f))) {
            Box(Modifier.fillMaxWidth(rate.toFloat().coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(barColor))
        }
    }
}

/**
 * AI 学情总结卡：把题目、作答、判定、用时、全班位置喂给小悟，输出「总体表现 / 掌握情况 / 薄弱点 /
 * 亮点与创新点 / 指导建议」。结果按「提交 id + 版本」缓存在本地，重复打开不重复调用模型；可手动重新生成。
 */
@Composable
private fun AiWorkSummaryCard(
    a: AssignmentInfo,
    sub: SubmissionInfo,
    rank: Int?,
    classSize: Int,
    medianSec: Int?,
) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    val cacheKey = "kewu_ai_work_${sub.id}_v${sub.version}"
    var summary by remember(cacheKey) { mutableStateOf(kvGet(cacheKey)?.takeIf { it.isNotBlank() }) }
    var running by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    fun generate() {
        if (running) return
        running = true
        failed = false
        scope.launch {
            val prompt = buildWorkPrompt(a, sub, rank, classSize, medianSec)
            val r = AiClient.chat(
                system = "你是课悟的教学助手「小悟」，为老师分析单个学生的一次作业。语气专业、具体、可执行，" +
                    "只依据给出的数据，不编造。用中文，按以下五个小标题输出，每个标题后 1–3 条短句：" +
                    "【总体表现】【掌握情况】【薄弱点】【亮点与创新点】【指导建议】。" +
                    "「亮点与创新点」要找出学生作答中有独到思路、表达清晰或超出标准答案的地方，没有就如实写「暂未发现」。" +
                    "不要使用 Markdown 符号（#、*）。",
                user = prompt,
                maxTokens = 3072,
            )
            running = false
            if (r.isNullOrBlank()) {
                failed = true
            } else {
                summary = r.trim()
                kvSet(cacheKey, summary)
            }
        }
    }
    LaunchedEffect(cacheKey) { if (summary == null) generate() }

    KewuCardGroup(Modifier.padding(horizontal = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            when {
                running -> Text("小悟正在分析这份作业，约 10–30 秒…", color = KewuBlue, fontSize = 13.5.sp)
                failed && summary == null -> Text("AI 分析失败，请检查网络后重试", color = cs.error, fontSize = 13.5.sp)
                summary != null -> Text(summary.orEmpty(), color = cs.onSurface, fontSize = 14.sp, lineHeight = 22.sp)
            }
            Spacer(Modifier.height(12.dp))
            KewuSecondaryButton(
                text = if (running) "分析中…" else if (summary == null) "生成 AI 总结" else "重新生成",
                onClick = { generate() },
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun buildWorkPrompt(a: AssignmentInfo, s: SubmissionInfo, rank: Int?, classSize: Int, medianSec: Int?): String =
    buildString {
        val m = s.metrics()
        appendLine("作业：${a.title}（总分 ${a.totalScore}）")
        if (a.knowledgePoints.isNotEmpty()) appendLine("知识点：${a.knowledgePoints.joinToString("、")}")
        appendLine("学生：${s.studentName.ifBlank { s.studentId }}，第 ${s.version} 次提交")
        appendLine("用时：${fmtDuration(m.durationSec)}" + (medianSec?.let { "（全班中位 ${fmtDuration(it)}）" } ?: ""))
        appendLine("正确率：${fmtPct(m.accuracy)}" + (rank?.let { "，全班第 $it / $classSize 名" } ?: ""))
        if (s.aiHelp.isNotEmpty()) appendLine("作答时向 AI 辅导求助：" + s.aiHelp.entries.joinToString("、") { (q, c) -> "题 $q $c 次" })
        m.gotScore?.let { appendLine("得分：${fmtNum(it)}") }
        val answerMap = s.answers.associate { it.questionId to it.value }
        val resultMap = s.questionResults.associateBy { it.questionId }
        if (a.questions.isNotEmpty()) {
            appendLine("逐题：")
            a.questions.forEachIndexed { i, q ->
                val r = resultMap[q.id]
                val verdict = when (r?.correct) { true -> "对"; false -> "错"; null -> "待判" }
                appendLine("${i + 1}. [${q.type.label}] ${q.stem.take(120)}")
                if (q.options.isNotEmpty()) appendLine("   选项：" + q.options.mapIndexed { oi, o -> "${'A' + oi}.$o" }.joinToString("  ").take(200))
                appendLine("   学生答：${answerMap[q.id].orEmpty().ifBlank { "（未作答）" }.take(300)}；标准答案：${q.answer.take(150)}；判定：$verdict")
            }
        } else {
            appendLine("作答内容：${s.content.take(1500)}")
        }
        if (s.attachments.isNotEmpty()) appendLine("附件 ${s.attachments.size} 个（${s.attachments.joinToString("、") { it.name }}）")
    }
