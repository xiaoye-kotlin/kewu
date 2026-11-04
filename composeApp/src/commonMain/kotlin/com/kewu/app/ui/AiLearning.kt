package com.kewu.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import io.github.suqi8.coui.kmp.basic.Chip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.collectAsState
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
import com.kewu.app.AssignmentRepo
import com.kewu.app.CourseRepo
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuBlue
import com.kewu.app.KewuGreen
import com.kewu.app.Question
import com.kewu.app.QuestionResult
import com.kewu.app.QuestionType
import com.kewu.app.ScheduleEntry
import com.kewu.app.haptic
import com.kewu.app.kvGet
import com.kewu.app.kvSet
import com.kewu.app.todayCn
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.kewu.app.KnowledgeTagger

/*
 * 学生端 AI 学习中心（2026-10-26，ICT 创新赛 AI 智能体功能）：
 *   ① [MistakeBookScreen]  AI 错题本：自动汇总所有作业里的错题 → 小悟讲错因（流式）→ 「举一反三」现出一道同类变式题当场作答、即时判对错；
 *                          变式题答对即「已攻克」移出错题本。
 *   ② [StudyPlanScreen]    AI 学习规划：知识点掌握度画像 + 未交作业截止时间 + 课表空闲时段 → 小悟排一份落到具体时段的 7 天计划。
 *   ③ [AiPreviewCard]      课前 AI 预习卡（首页）：下一节课 + 老师上传的课前资料 → 3 个预习要点 + 1 个带着去听课的问题。
 * 数据全部来自 App 已有的真实数据（作业逐题判定、课表、课前资料），没有就如实说没有。
 */

// ───────────────────────── 公共：错题汇总 ─────────────────────────

/** 一道错题（来自某份作业的逐题判定 correct=false）。 */
internal data class Mistake(val assignment: AssignmentInfo, val question: Question, val result: QuestionResult) {
    val key: String get() = assignment.id + "#" + question.id
}

private fun masteredKey() = "kewu_mistake_mastered_" + KewuApi.username.orEmpty()

private fun loadMastered(): Set<String> = kvGet(masteredKey()).orEmpty().split('|').filter { it.isNotBlank() }.toSet()

private fun saveMastered(s: Set<String>) = kvSet(masteredKey(), s.joinToString("|"))

/** 从作业仓库汇总我的所有错题（已攻克的除外）。 */
internal fun collectMistakes(items: List<AssignmentRepo.Item>, includeMastered: Boolean = false): List<Mistake> {
    val mastered = if (includeMastered) emptySet() else loadMastered()
    return items.flatMap { item ->
        val sub = item.mine ?: return@flatMap emptyList()
        val qs = item.assignment.questions.associateBy { it.id }
        sub.questionResults.filter { it.correct == false }.mapNotNull { r ->
            qs[r.questionId]?.let { Mistake(item.assignment, it, r) }
        }
    }.filter { it.key !in mastered }
}

private fun normChoice(s: String) = s.uppercase().filter { it in 'A'..'Z' }.toSet()
private fun normTf(s: String) = when (s.trim().lowercase()) { "true", "正确", "对", "t" -> "T"; "false", "错误", "错", "f" -> "F"; else -> s.trim() }
private fun normText(s: String) = s.replace(Regex("\\s+"), "").lowercase()

/** 本地判客观题；判不了（填空不完全一致 / 简答）返回 null 交给 AI。 */
private fun localJudge(q: Question, value: String): Boolean? = when (q.type) {
    QuestionType.SINGLE_CHOICE, QuestionType.MULTIPLE_CHOICE -> normChoice(value) == normChoice(q.answer)
    QuestionType.TRUE_FALSE -> normTf(value) == normTf(q.answer)
    QuestionType.FILL_BLANK -> if (normText(value) == normText(q.answer)) true else null
    QuestionType.SHORT_ANSWER -> null
}

// ───────────────────────── ① AI 错题本 ─────────────────────────

@Composable
internal fun MistakeBookScreen(onBack: () -> Unit) {
    val cs = COUITheme.colorScheme
    val pageBackdrop = rememberGlassBackdrop()
    val items by AssignmentRepo.items.collectAsState()
    var version by remember { mutableStateOf(0) } // 攻克后刷新列表
    LaunchedEffect(Unit) { AssignmentRepo.refresh(maxAgeMs = 60_000) }
    val list = remember(items, version) { items?.let { collectMistakes(it) } }
    val total = remember(items) { items?.let { collectMistakes(it, includeMastered = true).size } ?: 0 }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().layerBackdrop(pageBackdrop).background(cs.surface))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 128.dp)) {
            KewuTopBar("AI 错题本", onBack)
            KewuGlassCard(
                backdrop = pageBackdrop, shape = RoundedCornerShape(24.dp), blurRadius = 22.dp, tintAlpha = 0.30f,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("待攻克 ${list?.size ?: 0} 道", color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "已攻克 ${(total - (list?.size ?: 0)).coerceAtLeast(0)} / $total · 每道错题：小悟讲错因 → 举一反三出一道变式题 → 答对即攻克",
                            color = cs.onSurfaceSecondary, fontSize = 12.5.sp, lineHeight = 18.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            when {
                list == null -> KewuLoading(text = "正在汇总你的错题…")
                list.isEmpty() -> if (total == 0) {
                    KewuEmpty("还没有错题：作业里判错的题会自动收进这里")
                } else {
                    // ★ A3（2026-11-05）：全部攻克 → 画勾动画 + 一行字（与费曼「全部掌握」同款语言；去 emoji、去「太棒了」AI 味）
                    Column(Modifier.fillMaxWidth().padding(vertical = 44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        ThreeQuarterCheck(size = 88.dp, color = KewuGreen)
                        Spacer(Modifier.height(16.dp))
                        Text("错题已全部攻克！", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                else -> list.forEach { m -> MistakeCard(m) { saveMastered(loadMastered() + m.key); version++ } }
            }
        }
    }
}

@Composable
private fun MistakeCard(m: Mistake, onMastered: () -> Unit) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    val snackbar = LocalKewuSnackbar.current
    var open by remember { mutableStateOf(false) }
    var explain by remember { mutableStateOf<String?>(null) }
    var explaining by remember { mutableStateOf(false) }
    var variant by remember { mutableStateOf<Question?>(null) }
    var genBusy by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf("") }
    var verdict by remember { mutableStateOf<String?>(null) }
    var judging by remember { mutableStateOf(false) }
    val course = CourseRepo.courses.value?.firstOrNull { it.id == m.assignment.courseId }?.name ?: ""

    KewuCardGroup(Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("《${m.assignment.title}》· ${m.question.type.label}", color = KewuBlue, fontSize = 12.5.sp)
            Spacer(Modifier.height(6.dp))
            Text(m.question.stem, color = cs.onSurface, fontSize = 15.sp, lineHeight = 22.sp)
            if (m.question.options.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                m.question.options.forEachIndexed { i, o ->
                    Text("${'A' + i}. ${o.removePrefix("${'A' + i}.").removePrefix("${'A' + i}、").trim()}",
                        color = cs.onSurfaceSecondary, fontSize = 13.5.sp, lineHeight = 20.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Text("我的答案：", color = cs.onSurfaceSecondary, fontSize = 13.sp)
                Text(m.result.studentValue.ifBlank { "（未作答）" }, color = Color(0xFFE5484D), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(12.dp))
                Text("正确答案：", color = cs.onSurfaceSecondary, fontSize = 13.sp)
                Text(m.result.standardAnswer.ifBlank { m.question.answer }, color = KewuGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            KewuSecondaryButton(if (open) "收起" else "讲错因", { open = !open }, Modifier.fillMaxWidth())
            AnimatedVisibility(
                visible = open,
                enter = expandVertically(spring(dampingRatio = 0.86f, stiffness = 380f)) + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(Modifier.padding(top = 10.dp)) {
                    LaunchedEffect(Unit) {
                        if (explain != null || explaining) return@LaunchedEffect
                        explaining = true
                        explain = ""
                        AiClient.chatStream(
                            listOf(
                                AiClient.Msg("system", "你是课悟的 AI 助教小悟，帮学生分析一道错题。用中文、不用 Markdown 符号，分三段：" +
                                    "【错因】学生为什么会选 / 写错（结合他的答案推测思维误区；如果给了他做题时和小悟的辅导对话，要据此指出他当时具体卡在哪一步、哪个想法错了）；" +
                                    "【正解】这道题的正确思路；【避坑】下次遇到同类题的一句口诀。总共不超过 250 字。"),
                                AiClient.Msg("user", "课程：$course\n题目：${m.question.stem}\n选项：${m.question.options.joinToString(" / ")}\n" +
                                    "学生答案：${m.result.studentValue}\n正确答案：${m.result.standardAnswer.ifBlank { m.question.answer }}\n解析：${m.question.analysis}" +
                                    // 2026-11-02：做题时「问小悟」的对话（按作业 id#题目 id 落盘），让 AI 知道学生当时怎么想的
                                    TutorSessions.transcript(m.key).let { t ->
                                        if (t.isBlank()) "\n做题时没有向小悟求助。"
                                        else "\n学生做这道题时和小悟的辅导对话（学生真实的思考过程）：\n$t"
                                    }),
                            ),
                            maxTokens = 1024,
                        ) { explain = (explain ?: "") + it }
                        if (explain.isNullOrBlank()) explain = "小悟暂时连不上 AI 服务，请稍后重试"
                        explaining = false
                    }
                    Text(explain?.ifEmpty { "小悟正在分析你的错因…" } ?: "", color = cs.onSurface, fontSize = 14.sp, lineHeight = 21.sp)
                }
            }
            // ★ 变式题区放在动画容器之外：作答卡是液态玻璃采样者，不能处在尺寸 / 透明度动画里（会压成 0 高闪退）
            if (open) {
                Column(Modifier.padding(top = 12.dp)) {
                    val v = variant
                    if (v == null) {
                        KewuPrimaryButton(if (genBusy) "小悟正在出变式题…" else "举一反三：出一道同类题练练", {
                            if (genBusy) return@KewuPrimaryButton
                            genBusy = true
                            scope.launch {
                                variant = AiClient.generateOneQuestion(
                                    "围绕下面这道错题考查的同一个知识点，出一道变式题（换数据或情境，难度相当，不能与原题相同）。" +
                                        "原题：${m.question.stem}；原题正确答案：${m.question.answer}；课程：$course",
                                    m.question.type, 1, 1,
                                )
                                genBusy = false
                                if (variant == null) snackbar.toast("出题失败，请稍后重试")
                            }
                        }, Modifier.fillMaxWidth(), enabled = !genBusy)
                    } else {
                        Text("变式题", color = cs.onSurfaceSecondary, fontSize = 12.5.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
                        QuestionAnswerCard(q = v, index = 1, value = answer, onValue = { answer = it; verdict = null }, readOnly = false, result = null)
                        Spacer(Modifier.height(8.dp))
                        verdict?.let {
                            Text(it, color = if (it.startsWith("✓")) KewuGreen else Color(0xFFE5484D), fontSize = 14.sp,
                                lineHeight = 20.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                        }
                        KewuPrimaryButton(if (judging) "小悟判题中…" else "提交答案", {
                            if (answer.isBlank() || judging) return@KewuPrimaryButton
                            val local = localJudge(v, answer)
                            if (local != null) {
                                verdict = if (local) "✓ 答对了！这个知识点你已经掌握，已移出错题本" else "✗ 还不对，正确答案是 ${v.answer}。${v.analysis}"
                                if (local) { haptic(HapticKind.Light); onMastered() }
                                return@KewuPrimaryButton
                            }
                            judging = true
                            scope.launch {
                                val r = AiClient.chat(
                                    system = "你是判题老师。判断学生答案与参考答案是否意思一致。只输出一行：第一个字是「对」或「错」，后面跟不超过 40 字的理由。",
                                    user = "题目：${v.stem}\n参考答案：${v.answer}\n学生答案：$answer",
                                    maxTokens = 256,
                                ).orEmpty().trim()
                                judging = false
                                val ok = r.startsWith("对")
                                verdict = if (ok) "✓ 答对了！" + r.drop(1).trim() + "（已移出错题本）" else "✗ " + r.removePrefix("错").trim().ifBlank { "参考答案：${v.answer}" }
                                if (ok) { haptic(HapticKind.Light); onMastered() }
                            }
                        }, Modifier.fillMaxWidth(), enabled = answer.isNotBlank() && !judging)
                        Spacer(Modifier.height(6.dp))
                        KewuSecondaryButton("换一道", { variant = null; answer = ""; verdict = null }, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

// ───────────────────────── ② AI 学习规划 ─────────────────────────

private data class Mastery(val point: String, val rate: Double, val samples: Int)

/**
 * 按知识点统计掌握度（2026-11-03 改）：知识点来自 [KnowledgeTagger]（AI 读题目内容识别），不再拿作业标题充数。
 * 有逐题判定时按题统计（每道题的得分率记到它考查的知识点上）；只有总分时把整份得分率摊到全部知识点上。
 * 还没识别出知识点的作业先跳过（识别完成后自动刷新）。
 */
private fun masteryOf(items: List<AssignmentRepo.Item>): List<Mastery> {
    val acc = mutableMapOf<String, MutableList<Double>>()
    // 课后自测（AI 学习工具）的逐题结果也算样本：课堂 → 自测 → 掌握度 → 学习规划，形成闭环
    StudyLabStore.quizSamples().forEach { (p, l) -> if (l.isNotEmpty()) acc.getOrPut(p) { mutableListOf() }.addAll(l) }
    items.forEach { it ->
        val sub = it.mine ?: return@forEach
        val a = it.assignment
        val tags = KnowledgeTagger.cached(a.id)
        val wholePoints = (tags?.all ?: emptyList()).ifEmpty { a.knowledgePoints.filter { p -> p.isNotBlank() && p != a.title } }
        if (wholePoints.isEmpty()) return@forEach
        val judged = sub.questionResults.withIndex().filter { (_, r) -> r.correct != null || r.finalScore != null }
        if (tags != null && tags.perQuestion.isNotEmpty() && judged.isNotEmpty()) {
            judged.forEach { (i, r) ->
                val rate = when {
                    r.finalScore != null && r.fullScore > 0 -> (r.finalScore / r.fullScore).coerceIn(0.0, 1.0)
                    else -> if (r.correct == true) 1.0 else 0.0
                }
                KnowledgeTagger.pointsOf(tags, a, r.questionId, i).forEach { p -> acc.getOrPut(p) { mutableListOf() } += rate }
            }
            return@forEach
        }
        val rate = when {
            judged.isNotEmpty() -> judged.count { (_, r) -> r.correct == true }.toDouble() / judged.size
            sub.score != null && a.totalScore > 0 -> sub.score / a.totalScore
            else -> return@forEach
        }
        wholePoints.forEach { p -> acc.getOrPut(p) { mutableListOf() } += rate }
    }
    return acc.map { (p, l) -> Mastery(p, l.average(), l.size) }.sortedBy { it.rate }
}

/** 课表在每个星期几的空闲时段（08:00–22:00 去掉上课时间，留 ≥45 分钟的段）。 */
private fun freeSlots(schedule: List<ScheduleEntry>): Map<Int, List<String>> {
    fun m(t: String) = t.split(":").let { (it.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (it.getOrNull(1)?.toIntOrNull() ?: 0) }
    fun s(v: Int) = "${(v / 60).toString().padStart(2, '0')}:${(v % 60).toString().padStart(2, '0')}"
    return (1..7).associateWith { d ->
        val busy = schedule.filter { it.weekday == d }.map { m(it.startTime) to m(it.endTime) }.sortedBy { it.first }
        val out = mutableListOf<String>()
        var cur = 8 * 60
        busy.forEach { (a, b) -> if (a - cur >= 45) out += "${s(cur)}-${s(a)}"; cur = maxOf(cur, b) }
        if (22 * 60 - cur >= 45) out += "${s(cur)}-22:00"
        out
    }
}

@Composable
internal fun StudyPlanScreen(onBack: () -> Unit) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    val pageBackdrop = rememberGlassBackdrop()
    val items by AssignmentRepo.items.collectAsState()
    var schedule by remember { mutableStateOf<List<ScheduleEntry>?>(null) }
    LaunchedEffect(Unit) {
        AssignmentRepo.refresh(maxAgeMs = 60_000)
        schedule = KewuApi.getMySchedule() ?: emptyList()
    }
    // 学生自评（看完视频后填的，只升不降）；变化后 version++ 刷新
    var selfVersion by remember { mutableStateOf(0) }
    val selfRates = remember(selfVersion) { MasteryStore.selfRates() }
    val history = remember(selfVersion) { MasteryStore.history() }
    // 作业 → 知识点（AI 识别，按作业缓存）：有新作业就补识别，识别完 tagVersion++ 重算掌握度
    var tagVersion by remember { mutableStateOf(0) }
    var tagging by remember { mutableStateOf(false) }
    LaunchedEffect(items) {
        val todo = items.orEmpty().filter { it.mine != null && KnowledgeTagger.cached(it.assignment.id) == null }
        if (todo.isEmpty()) return@LaunchedEffect
        tagging = true
        coroutineScope {
            todo.map { async { KnowledgeTagger.tag(it.assignment) } }.awaitAll()
        }
        tagging = false
        tagVersion++
    }
    val mastery = remember(items, selfVersion, tagVersion) {
        masteryOf(items.orEmpty()).map { m ->
            val self = selfRates[m.point] ?: 0
            val percent = maxOf((m.rate * 100).toInt(), self).coerceAtMost(100)
            MasteryView(m, percent, percent >= 100)
        }.sortedWith(compareBy<MasteryView> { it.mastered }.thenBy { it.percent })
    }
    // 2026-11-02 用户口径：薄弱 = 作业正确率 < 60% 且还没自评到 100%；自评到 100% 就从薄弱里减掉
    val weakCount = mastery.count { !it.mastered && it.m.rate < 0.6 }
    val pending = remember(items) { items.orEmpty().filter { it.pending }.map { it.assignment } }
    val mistakes = remember(items) { collectMistakes(items.orEmpty()).size }
    val cacheKey = "kewu_plan2_" + KewuApi.username.orEmpty() + "_" + todayCn()
    var plan by remember { mutableStateOf(kvGet(cacheKey)?.ifBlank { null }) }
    var busy by remember { mutableStateOf(false) }

    // 知识点视频弹窗 / 看完后的自评弹窗
    var dialogPoint by remember { mutableStateOf<MasteryView?>(null) }
    var showVideos by remember { mutableStateOf(false) }
    var ratePoint by remember { mutableStateOf<MasteryView?>(null) }
    // AI 学习工具（2026-11-03）：1 今日复习（全部到期卡片）/ 2 讲给小悟听（薄弱知识点）
    var labTool by remember { mutableStateOf(0) }
    val dueCards = remember(labTool) { StudyLabStore.due().size }
    var waitingPlayback by remember { mutableStateOf<MasteryView?>(null) }
    val playing = VideoPlayerHub.current
    LaunchedEffect(playing) {
        // 播放层关闭 → 问学生现在掌握了多少（已掌握的不再问）
        if (playing == null) waitingPlayback?.let { mv ->
            waitingPlayback = null
            if (!mv.mastered) ratePoint = mv
        }
    }

    fun generate() {
        if (busy || schedule == null) return
        busy = true
        plan = ""
        val week = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val free = freeSlots(schedule.orEmpty())
        val ctx = buildString {
            appendLine("今天：${todayCn()}")
            appendLine("知识点掌握度（越低越薄弱）：" + mastery.joinToString("；") { "${it.m.point} ${it.percent}%" + if (it.mastered) "（已掌握）" else "" }.ifBlank { "暂无已批改作业" })
            appendLine("未完成作业：" + pending.joinToString("；") { "《${it.title}》截止 ${it.deadline.ifBlank { "未设置" }}" }.ifBlank { "无" })
            appendLine("错题本待攻克：$mistakes 道")
            appendLine("每天空闲时段（课表之外）：" + (1..7).joinToString("；") { d -> week[d - 1] + " " + free[d].orEmpty().joinToString(" ").ifBlank { "无" } })
            appendLine("每天的课：" + schedule.orEmpty().joinToString("；") { "${week[it.weekday - 1]} ${it.startTime} ${it.courseName}" }.ifBlank { "暂无课表" })
        }
        scope.launch {
            AiClient.chatStream(
                listOf(
                    AiClient.Msg("system", "你是课悟的 AI 学习规划师小悟。根据学生的真实学情、作业截止时间和课表空闲时段，排一份从今天起 7 天的学习计划。" +
                        "要求：每天 1–3 项，时间必须落在给出的空闲时段内；截止早的作业优先，薄弱知识点安排复习和错题本练习，课前安排预习、课后安排复习。" +
                        "输出格式（App 会解析成表格，必须严格遵守）：每项一行，用竖线分隔四列「周X|HH:MM-HH:MM|做什么|为什么这样安排」，" +
                        "例如「周一|19:00-20:00|完成《线性代数作业3》|周三截止，先做最紧急的」；不要表头、不要编号、不要 Markdown 符号、不要其它多余的行；" +
                        "全部计划写完后，最后单独一行以「鼓励：」开头写一句鼓励。"),
                    AiClient.Msg("user", ctx),
                ),
                maxTokens = 2048,
            ) { plan = (plan ?: "") + it }
            busy = false
            if (!plan.isNullOrBlank()) kvSet(cacheKey, plan) else plan = "小悟暂时连不上 AI 服务，请稍后重试"
        }
    }
    // 等知识点识别完再排计划，计划里写的是具体知识点而不是作业标题
    LaunchedEffect(schedule, tagging) { if (schedule != null && !tagging && plan == null) generate() }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().layerBackdrop(pageBackdrop).background(cs.surface))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 128.dp)) {
            KewuTopBar("AI 学习规划", onBack)
            KewuGlassCard(
                backdrop = pageBackdrop, shape = RoundedCornerShape(24.dp), blurRadius = 22.dp, tintAlpha = 0.30f,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            ) {
                Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlanStat("待完成作业", "${pending.size}", KewuBlue)
                    PlanStat("待攻克错题", "$mistakes", Color(0xFFE5484D))
                    PlanStat("薄弱知识点", "$weakCount", Color(0xFFF5A623))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlanToolCard("今日复习", if (dueCards > 0) "$dueCards 张卡片到期" else "记忆卡片", KewuBlue, Modifier.weight(1f)) { labTool = 1 }
                PlanToolCard("讲给小悟听", "费曼学习法自测", Color(0xFFF5A623), Modifier.weight(1f)) { labTool = 2 }
            }
            Spacer(Modifier.height(14.dp))
            KewuSectionTitle("知识点掌握度")
            KewuCardGroup(Modifier.padding(horizontal = 12.dp)) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    if (mastery.isEmpty()) Text(
                        if (tagging) "小悟正在识别作业里的知识点…" else "还没有批改过的作业，交几份作业后这里会画出你的知识画像",
                        color = cs.onSurfaceSecondary, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    mastery.take(12).forEach { mv ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { haptic(HapticKind.Tick); dialogPoint = mv; showVideos = true }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(mv.m.point, color = cs.onSurface, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                                Text(
                                    if (mv.mastered) "已掌握 · 100%" else "${mv.percent}% · ${mv.m.samples} 次",
                                    color = if (mv.mastered) KewuGreen else cs.onSurfaceSecondary, fontSize = 12.5.sp,
                                )
                                Text("  ›", color = cs.onSurfaceSecondary, fontSize = 14.sp)
                            }
                            Spacer(Modifier.height(5.dp))
                            MasteryBar(mv.percent / 100f, mv.mastered)
                        }
                    }
                }
            }
            if (history.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                KewuSectionTitle("曾经的薄弱知识点（已攻克）")
                KewuCardGroup(Modifier.padding(horizontal = 12.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        history.sortedByDescending { it.masteredAt }.forEach { r ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(KewuGreen))
                                Spacer(Modifier.width(8.dp))
                                Text(r.point, color = cs.onSurface, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                                Text("${r.fromPercent}% → 100% · ${todayCn(r.masteredAt)}", color = cs.onSurfaceSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            KewuSectionTitle("小悟为你排的 7 天计划（结合课表空闲时段）")
            PlanTable(plan, busy, pageBackdrop)
            Spacer(Modifier.height(10.dp))
            KewuSecondaryButton(if (busy) "生成中…" else "重新规划", { generate() }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), enabled = !busy)
        }

        dialogPoint?.let { mv ->
            KnowledgeVideoDialog(
                show = showVideos,
                point = mv.m.point,
                percent = mv.percent,
                mastered = mv.mastered,
                course = "",
                onPlay = { v ->
                    showVideos = false
                    waitingPlayback = mv
                    VideoPlayerHub.current = v
                },
                onDismiss = { showVideos = false },
            )
        }
        ratePoint?.let { mv ->
            MasteryRateDialog(
                show = true,
                point = mv.m.point,
                currentPercent = mv.percent,
                onConfirm = { v ->
                    MasteryStore.setRate(mv.m.point, v, (mv.m.rate * 100).toInt())
                    ratePoint = null
                    selfVersion++
                    haptic(HapticKind.Light)
                },
                onDismiss = { ratePoint = null },
            )
        }
        SlideOverlay(labTool.takeIf { it != 0 }) { tool ->
            if (tool == 1) {
                FlashcardsScreen(deck = null, deckTitle = "全部课程", onBack = { labTool = 0 })
            } else {
                FeynmanScreen(
                    // 2026-11-04（P3）：所有未掌握（<100%）的知识点都可选择，不再截断 8 个
                    points = mastery.filter { !it.mastered }.map { it.m.point },
                    subtitle = "挑一个薄弱知识点讲一遍",
                    onBack = { labTool = 0; selfVersion++ },
                )
            }
        }
    }
}

/** 学习规划页的工具小卡。 */
@Composable
private fun PlanToolCard(title: String, desc: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val cs = COUITheme.colorScheme
    Card(
        modifier = modifier,
        cornerRadius = 18.dp,
        insideMargin = androidx.compose.foundation.layout.PaddingValues(14.dp),
        colors = kewuCardColors(),
        onClick = { haptic(HapticKind.Light); onClick() },
    ) {
        Column {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.height(8.dp))
            Text(title, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(desc, color = cs.onSurfaceSecondary, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** 掌握度展示：作业正确率 + 学生自评取较高者；自评到 100% 为已掌握。 */
private data class MasteryView(val m: Mastery, val percent: Int, val mastered: Boolean)

/** 计划表一行。 */
private data class PlanRow(val day: String, val time: String, val task: String, val reason: String)

/** 解析「周X|时间|做什么|为什么」行；流式生成中只解析已写完的行。 */
private fun parsePlan(text: String, complete: Boolean): Pair<List<PlanRow>, String?> {
    val lines = text.lines().let { if (complete) it else it.dropLast(1) }
    val rows = lines.mapNotNull { l ->
        val parts = l.trim().trim('|').split('|', '｜').map { it.trim() }
        if (parts.size >= 3 && parts[0].startsWith("周")) PlanRow(parts[0], parts[1], parts[2], parts.getOrElse(3) { "" }) else null
    }
    val cheer = lines.firstOrNull { it.trim().startsWith("鼓励") }?.trim()?.removePrefix("鼓励：")?.removePrefix("鼓励:")
    return rows to cheer
}

/**
 * 7 天计划表格（2026-11-02 重做，coui + 液态玻璃）：顶部 coui Chip 切换星期，
 * 表格放在液态玻璃卡里（三列「时间 / 安排 / 为什么这样排」，时间做成品牌色小胶囊），
 * 切换星期时各行错峰上浮入场。解析不出表格时（出错文案等）原样显示文字。
 */
@Composable
private fun PlanTable(plan: String?, busy: Boolean, backdrop: LayerBackdrop) {
    val cs = COUITheme.colorScheme
    val (rows, cheer) = remember(plan, busy) { parsePlan(plan.orEmpty(), complete = !busy) }
    val p = rememberEnterProgress(0, baseDelayMs = 160L)
    if (rows.isEmpty()) {
        KewuGlassCard(
            backdrop = backdrop, shape = RoundedCornerShape(22.dp), blurRadius = glassCondenseBlur(p, 22f), tintAlpha = 0.30f * p,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).glassEnter(p, 30f),
        ) {
            Text(
                when {
                    plan == null -> "正在加载课表…"
                    plan.isBlank() || busy -> "小悟正在结合你的课表和作业截止时间排计划…"
                    else -> plan
                },
                color = cs.onSurface, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(18.dp),
            )
        }
        return
    }
    val days = rows.groupBy { it.day }
    var sel by remember { mutableStateOf<String?>(null) }
    val current = sel?.takeIf { it in days } ?: days.keys.first()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        days.forEach { (d, list) ->
            Chip(selected = d == current, onClick = { haptic(HapticKind.Tick); sel = d }, label = "$d · ${list.size} 项")
        }
    }
    KewuGlassCard(
        backdrop = backdrop, shape = RoundedCornerShape(22.dp), blurRadius = glassCondenseBlur(p, 22f), tintAlpha = 0.32f * p,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).glassEnter(p, 30f),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            // 表头
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text("时间", color = cs.onSurfaceSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(96.dp))
                Text("安排", color = cs.onSurfaceSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("为什么这样排", color = cs.onSurfaceSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(start = 10.dp))
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(cs.onSurface.copy(alpha = 0.08f)))
            // key(current)：切换星期时重新播放错峰入场
            androidx.compose.runtime.key(current) {
                days[current].orEmpty().forEachIndexed { i, r ->
                    val rp = rememberEnterProgress(i, baseDelayMs = 30L, stepMs = 60L)
                    if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(cs.onSurface.copy(alpha = 0.05f)))
                    Row(Modifier.fillMaxWidth().riseEnter(rp, 22f).padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Box(Modifier.width(96.dp)) {
                            Text(
                                r.time, color = KewuBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(KewuBlue.copy(alpha = 0.12f))
                                    .padding(horizontal = 7.dp, vertical = 4.dp),
                            )
                        }
                        Text(r.task, color = cs.onSurface, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, lineHeight = 19.sp, modifier = Modifier.weight(1f))
                        Text(r.reason, color = cs.onSurfaceSecondary, fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f).padding(start = 10.dp))
                    }
                }
            }
        }
    }
    if (busy) Text("小悟还在继续排…", color = cs.onSurfaceSecondary, fontSize = 12.5.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    cheer?.takeIf { it.isNotBlank() }?.let {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(KewuGreen))
            Spacer(Modifier.width(8.dp))
            Text(it, color = KewuGreen, fontSize = 13.5.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.PlanStat(label: String, value: String, color: Color) {
    val cs = COUITheme.colorScheme
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(cs.surface.copy(alpha = 0.55f)).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(label, color = cs.onSurfaceSecondary, fontSize = 12.sp)
    }
}

// ───────────────────────── ③ 首页：AI 学习入口 + 课前预习卡 ─────────────────────────

/** 首页「AI 学习中心」两张入口卡（coui Card）。 */
@Composable
internal fun AiStudyEntryRow(onMistakes: () -> Unit, onPlan: () -> Unit, modifier: Modifier = Modifier) {
    val cs = COUITheme.colorScheme
    val items by AssignmentRepo.items.collectAsState()
    val mistakes = remember(items) { collectMistakes(items.orEmpty()).size }
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(
            Triple("AI 错题本", if (mistakes > 0) "$mistakes 道待攻克" else "暂无错题", onMistakes),
            Triple("AI 学习规划", "按课表排 7 天计划", onPlan),
        ).forEachIndexed { i, (t, sub, click) ->
            Card(
                modifier = Modifier.weight(1f),
                cornerRadius = 20.dp,
                colors = kewuCardColors(),
                onClick = { haptic(HapticKind.Tick); click() },
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background((if (i == 0) Color(0xFFE5484D) else KewuGreen).copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (i == 0) "错" else "划", color = if (i == 0) Color(0xFFE5484D) else KewuGreen, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(t, color = cs.onSurface, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                        Text(sub, color = cs.onSurfaceSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** 下一节课（今天还没上的 → 之后几天最近的一节）。 */
private fun nextClass(schedule: List<ScheduleEntry>): Pair<ScheduleEntry, Int>? {
    val nowMs = kotlin.time.Clock.System.now().toEpochMilliseconds() + 8 * 3_600_000L
    val today = ((nowMs / 86_400_000L + 3) % 7).toInt() + 1
    val minute = ((nowMs / 60_000L) % (24 * 60)).toInt()
    fun m(t: String) = t.split(":").let { (it.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (it.getOrNull(1)?.toIntOrNull() ?: 0) }
    for (offset in 0..7) {
        val d = (today - 1 + offset) % 7 + 1
        val c = schedule.filter { it.weekday == d && (offset > 0 || m(it.startTime) > minute) }.minByOrNull { it.startTime }
        if (c != null) return c to offset
    }
    return null
}

/** 课前 AI 预习卡（首页液态玻璃卡）：下一节课 + 课前资料 → 预习要点。按「课 + 日期」缓存。 */
@Composable
internal fun AiPreviewCard(backdrop: LayerBackdrop, modifier: Modifier = Modifier) {
    val cs = COUITheme.colorScheme
    var target by remember { mutableStateOf<Pair<ScheduleEntry, Int>?>(null) }
    var text by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val sch = KewuApi.getMySchedule().orEmpty()
        val n = nextClass(sch) ?: return@LaunchedEffect
        target = n
        val (c, _) = n
        val key = "kewu_preview_" + c.courseId + "_" + c.weekday + "_" + todayCn()
        kvGet(key)?.ifBlank { null }?.let { text = it; return@LaunchedEffect }
        val materials = KewuApi.getCourseMaterials(c.courseId).take(2)
        text = ""
        AiClient.chatStream(
            listOf(
                AiClient.Msg("system", "你是课悟的 AI 助教小悟，帮学生课前预习。根据课程和老师上传的课前资料，输出：三条以「·」开头的预习要点（每条不超过 30 字），" +
                    "再一行以「带着问题听课：」开头的一个思考问题。没有资料就根据课程名给出通用的入门要点。用中文，不用 Markdown 符号。"),
                AiClient.Msg("user", "课程：${c.courseName}，任课老师：${c.teacherName}，地点：${c.location}\n课前资料：" +
                    materials.joinToString("；") { (t, note, files) -> "$t ${note.take(300)} 附件：${files.joinToString("、")}" }.ifBlank { "无" }),
            ),
            maxTokens = 1024,
        ) { text = (text ?: "") + it }
        if (!text.isNullOrBlank()) kvSet(key, text.orEmpty())
    }
    val t = target ?: return
    val (c, offset) = t
    val week = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    KewuGlassCard(
        backdrop = backdrop, shape = RoundedCornerShape(22.dp), blurRadius = 22.dp, tintAlpha = 0.30f,
        onClick = { expanded = !expanded },
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(KewuGreen))
                Spacer(Modifier.width(8.dp))
                Text("课前 AI 预习 · ${c.courseName}", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (expanded) "收起" else "展开", color = cs.onSurfaceSecondary, fontSize = 12.5.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                (when (offset) { 0 -> "今天"; 1 -> "明天"; else -> week[c.weekday - 1] }) + " ${c.startTime} · ${c.location.ifBlank { "地点待定" }} · ${c.teacherName}",
                color = cs.onSurfaceSecondary, fontSize = 12.5.sp,
            )
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(spring(dampingRatio = 0.86f, stiffness = 380f)) + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Text(
                    text?.ifEmpty { "小悟正在根据课前资料准备预习要点…" } ?: "小悟正在准备…",
                    color = cs.onSurface, fontSize = 13.5.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}
