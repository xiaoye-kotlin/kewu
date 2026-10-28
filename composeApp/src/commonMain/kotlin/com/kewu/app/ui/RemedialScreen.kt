package com.kewu.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.AiClient
import com.kewu.app.AssignmentInfo
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuBlue
import com.kewu.app.KewuGreen
import com.kewu.app.Question
import com.kewu.app.QuestionType
import com.kewu.app.SubmissionInfo
import com.kewu.app.haptic
import com.kewu.app.todayCn
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/*
 * 教师端「AI 分层补救」（2026-10-26，ICT 创新赛 AI 智能体功能）：
 * 一份作业批完后，按每位学生的正确率分成三层（待加强 < 60% / 巩固 60–85% / 拔高 ≥ 85%），
 * 找出每层错得最多的题，小悟针对该层的薄弱点各出一组练习（待加强偏基础、巩固同难度变式、拔高给挑战题），
 * 老师核对后一键发布，**只有该层学生能看到**（后端 assignments.target_students）。
 */

private data class Tier(val name: String, val color: Color, val hint: String, val count: Int, val students: List<SubmissionInfo>)

@Composable
internal fun RemedialScreen(assignmentId: String, onBack: () -> Unit) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    val snackbar = LocalKewuSnackbar.current
    val pageBackdrop = rememberGlassBackdrop()
    var assignment by remember { mutableStateOf<AssignmentInfo?>(null) }
    var subs by remember { mutableStateOf<List<SubmissionInfo>?>(null) }
    var analysis by remember { mutableStateOf<String?>(null) }
    val generated = remember { mutableStateMapOf<String, List<Question>>() }
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    val published = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(assignmentId) {
        val a = async { KewuApi.getAssignment(assignmentId) }
        val s = async { KewuApi.getSubmissions(assignmentId) }
        assignment = a.await()
        subs = s.await().orEmpty()
    }
    val a = assignment
    val all = subs
    val tiers = remember(all) {
        val withAcc = all.orEmpty().mapNotNull { s -> s.metrics().accuracy?.let { s to it } }
        listOf(
            Tier("待加强", Color(0xFFE5484D), "基础题：回到概念本身，一步一步来", 3, withAcc.filter { it.second < 0.6 }.map { it.first }),
            Tier("巩固", KewuBlue, "同难度变式题：换情境再练一次", 2, withAcc.filter { it.second in 0.6..0.85 }.map { it.first }),
            Tier("拔高", KewuGreen, "挑战题：综合应用、举一反三", 1, withAcc.filter { it.second > 0.85 }.map { it.first }),
        )
    }

    /** 某层学生错得最多的题（按错误人数排序）。 */
    fun weakQuestions(t: Tier): List<Pair<Question, Int>> {
        val qs = a?.questions.orEmpty().associateBy { it.id }
        return t.students.flatMap { s -> s.questionResults.filter { it.correct == false }.map { it.questionId } }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            .mapNotNull { (id, n) -> qs[id]?.let { it to n } }
    }

    LaunchedEffect(a, all) {
        if (a == null || all == null || analysis != null || all.isEmpty()) return@LaunchedEffect
        analysis = ""
        val ctx = buildString {
            appendLine("作业：${a.title}，知识点：${a.knowledgePoints.joinToString("、")}，共 ${all.size} 人提交")
            tiers.forEach { t ->
                appendLine("${t.name}层 ${t.students.size} 人：错得最多的题 " +
                    weakQuestions(t).take(3).joinToString("；") { (q, n) -> "${q.stem.take(40)}（错 $n 人）" }.ifBlank { "无" })
            }
        }
        AiClient.chatStream(
            listOf(
                AiClient.Msg("system", "你是课悟的 AI 教学助手小悟。根据分层学情，给老师写一段分层补救建议：每层一行，说清这一层的共性问题和补救重点，" +
                    "最后一行给课堂上可以统一讲的一个点。用中文，不用 Markdown 符号，总共不超过 180 字。"),
                AiClient.Msg("user", ctx),
            ),
            maxTokens = 1024,
        ) { analysis = (analysis ?: "") + it }
    }

    fun generate(t: Tier) {
        val aa = a ?: return
        if (busy[t.name] == true) return
        busy[t.name] = true
        scope.launch {
            val weak = weakQuestions(t).take(3).map { it.first }.ifEmpty { aa.questions.take(2) }
            val list = (1..t.count).map { i ->
                async {
                    val base = weak.getOrNull((i - 1) % weak.size.coerceAtLeast(1))
                    AiClient.generateOneQuestion(
                        "为「${t.name}」层学生出补救练习（${t.hint}）。作业《${aa.title}》知识点：${aa.knowledgePoints.joinToString("、")}。" +
                            (base?.let { "这一层错得最多的题：${it.stem}（正确答案 ${it.answer}），围绕它考查的知识点出题，不要与原题相同。" } ?: ""),
                        base?.type ?: QuestionType.SINGLE_CHOICE, i, t.count,
                    )
                }
            }.awaitAll().filterNotNull().mapIndexed { i, q -> q.copy(id = "r${i + 1}", score = 10) }
            generated[t.name] = list
            busy[t.name] = false
            if (list.isEmpty()) snackbar.toast("AI 出题失败，请稍后重试")
        }
    }

    fun publish(t: Tier) {
        val aa = a ?: return
        val qs = generated[t.name].orEmpty()
        if (qs.isEmpty() || t.students.isEmpty()) return
        busy[t.name] = true
        scope.launch {
            val deadlineMs = kotlin.time.Clock.System.now().toEpochMilliseconds() + 3 * 86_400_000L
            val r = KewuApi.publishAssignment(
                courseId = aa.courseId,
                title = "《${aa.title}》补救练习 · ${t.name}",
                description = "小悟根据你在《${aa.title}》中的作答情况为你准备的针对性练习（${t.hint}）。",
                deadline = todayCn(deadlineMs) + " 23:59",
                totalScore = qs.sumOf { it.score },
                knowledgePoints = aa.knowledgePoints,
                publisher = KewuApi.nameForServer(),
                questions = qs,
                targetStudents = t.students.map { it.studentId },
            )
            busy[t.name] = false
            if (r != null) {
                published[t.name] = true
                haptic(HapticKind.Light)
                snackbar.toast("已发布给「${t.name}」层 ${t.students.size} 位同学，其他同学看不到")
            } else snackbar.toast("发布失败，请稍后重试")
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().layerBackdrop(pageBackdrop).background(cs.surface))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 128.dp)) {
            KewuTopBar("AI 分层补救", onBack)
            when {
                a == null || all == null -> KewuLoading(text = "正在分析全班作答…")
                all.isEmpty() -> KewuEmpty("还没有学生提交这份作业")
                tiers.all { it.students.isEmpty() } -> KewuEmpty("这份作业没有可自动判定的题（或还没批改），暂时无法分层")
                else -> {
                    KewuGlassCard(
                        backdrop = pageBackdrop, shape = RoundedCornerShape(24.dp), blurRadius = 22.dp, tintAlpha = 0.30f,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Text("《${a.title}》· ${all.size} 人提交", color = cs.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            Row {
                                tiers.forEach { t ->
                                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("${t.students.size}", color = t.color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                        Text(t.name, color = cs.onSurfaceSecondary, fontSize = 12.sp)
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(analysis?.ifEmpty { "小悟正在分析各层的共性问题…" } ?: "", color = cs.onSurface, fontSize = 13.5.sp, lineHeight = 20.sp)
                        }
                    }
                    tiers.filter { it.students.isNotEmpty() }.forEach { t ->
                        Spacer(Modifier.height(14.dp))
                        KewuSectionTitle("${t.name}层 · ${t.students.size} 人 · ${t.hint}")
                        KewuCardGroup(Modifier.padding(horizontal = 12.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(t.color))
                                    Spacer(Modifier.width(8.dp))
                                    Text(t.students.joinToString("、") { it.studentName.ifBlank { it.studentId } },
                                        color = cs.onSurface, fontSize = 13.5.sp, lineHeight = 19.sp)
                                }
                                val weak = weakQuestions(t).take(2)
                                if (weak.isNotEmpty()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text("共性错题：" + weak.joinToString("；") { (q, n) -> "${q.stem.take(24)}…（$n 人错）" },
                                        color = cs.onSurfaceSecondary, fontSize = 12.5.sp, lineHeight = 18.sp)
                                }
                                Spacer(Modifier.height(12.dp))
                                val qs = generated[t.name]
                                if (qs == null) {
                                    KewuPrimaryButton(if (busy[t.name] == true) "小悟出题中…" else "让小悟出 ${t.count} 道针对性练习",
                                        { generate(t) }, Modifier.fillMaxWidth(), enabled = busy[t.name] != true)
                                } else {
                                    qs.forEachIndexed { i, q ->
                                        QuestionAnswerCard(q = q, index = i + 1, value = q.answer, onValue = {}, readOnly = true, result = null)
                                        Spacer(Modifier.height(8.dp))
                                    }
                                    if (published[t.name] == true) {
                                        Text("✓ 已发布，仅这 ${t.students.size} 位同学可见，3 天后截止", color = KewuGreen, fontSize = 13.5.sp)
                                    } else {
                                        KewuPrimaryButton(if (busy[t.name] == true) "发布中…" else "发布给这 ${t.students.size} 位同学",
                                            { publish(t) }, Modifier.fillMaxWidth(), enabled = busy[t.name] != true)
                                        Spacer(Modifier.height(6.dp))
                                        KewuSecondaryButton("换一批", { generated.remove(t.name); generate(t) }, Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
