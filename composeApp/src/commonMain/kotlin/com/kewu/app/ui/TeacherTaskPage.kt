package com.kewu.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.AiSuggestion
import com.kewu.app.AssignmentInfo
import com.kewu.app.AssignmentStats
import com.kewu.app.BackHandler
import com.kewu.app.CourseInfo
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuBlue
import com.kewu.app.KewuGreen
import com.kewu.app.PendingAssignmentGroup
import com.kewu.app.PendingCourseGroup
import com.kewu.app.PendingReview
import com.kewu.app.PendingReviewItem
import com.kewu.app.ReviewQueueItem
import com.kewu.app.SimilarityPair
import com.kewu.app.SubmissionInfo
import com.kewu.app.haptic
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.basic.Badge
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.ProgressIndicatorDefaults
import io.github.suqi8.coui.kmp.basic.Surface
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Add
import io.github.suqi8.coui.kmp.icon.extended.ChevronForward
import io.github.suqi8.coui.kmp.preference.RadioButtonPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch

// ───────────────────────── 教师端 · 作业（tab 2） ─────────────────────────
//
// 功能闭环（全部走 KewuApi 真实接口，失败降级提示不白屏）：
//   作业列表（getAssignments，按课程过滤 chip）+ 跨课程待批阅卡（getPendingReview，文档 3.12）→
//   待批阅总览：概览数字 / 按课程分组 / 「建议先改」明细（服务端已按 priority 降序 + 人话理由）→
//   作业详情：批阅统计（getAssignmentStats）/ 智能批阅队列（getReviewQueue，点进批阅）/
//             相似度查重（getSimilarity）/ 发布新作业（publishAssignment）→
//   批阅详情：提交内容 + 历史版本（getSubmission）+ AI 辅助批阅（aiPrereview）+
//             打分评语提交（reviewSubmission，approve=打分 / return=打回）。
//
// 视觉（2026-10-23 全局 coui 化）：液态玻璃卡（KewuGlassCard）本体保留，其余卡片 / 设置行 / 按钮 /
// 输入框 / chip / 角标 / 标签 / 进度 / 顶栏一律 coui 原生件，颜色读 COUITheme.colorScheme。

/**
 * 作业 tab 下钻。[DETAIL] / [REVIEW] 带 `back`：从哪一层进来就回哪一层
 * （总览 → 批阅 → 返回总览；作业详情 → 批阅 → 返回详情），BackHandler 与顶栏返回同一口径。
 */
private sealed class TaskDrill {
    data object NONE : TaskDrill()
    data object PENDING : TaskDrill()
    data class DETAIL(val assignmentId: String, val back: TaskDrill = NONE) : TaskDrill()
    data class REVIEW(val submissionId: String, val back: TaskDrill = NONE) : TaskDrill()
    data class PUBLISH(val courseId: String) : TaskDrill()
}

private fun TaskDrill.parent(): TaskDrill = when (this) {
    is TaskDrill.DETAIL -> back
    is TaskDrill.REVIEW -> back
    else -> TaskDrill.NONE
}

@Composable
fun TeacherTaskPage(onNavHide: (Boolean) -> Unit = {}) {
    val cs = COUITheme.colorScheme
    val pageBackdrop = rememberGlassBackdrop()
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val snackbar = LocalKewuSnackbar.current

    var drill by remember { mutableStateOf<TaskDrill>(TaskDrill.NONE) }
    BackHandler(enabled = drill != TaskDrill.NONE) { drill = drill.parent() }

    var assignments by remember { mutableStateOf<List<AssignmentInfo>?>(null) }
    var courses by remember { mutableStateOf<List<CourseInfo>?>(null) }
    var courseFilter by remember { mutableStateOf<String?>(null) }
    // ★ 跨课程「待批阅」（2026-10-23 接入文档 3.12）：优先服务端聚合 /api/teacher/pending-review
    //   （按优先级排好序 + 理由 + 按课程分组）；返回 null（老后端 / 网络失败）才降级到逐作业
    //   getSubmissions 过滤 PENDING 的手工聚合，[pendingLocal]=true 标记降级（无优先级/积压数据）。
    var pending by remember { mutableStateOf<PendingReview?>(null) }
    var pendingLocal by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            if (KewuApi.currentUser == null) KewuApi.me()
            val cl = KewuApi.currentUser?.courses?.takeIf { it.isNotEmpty() } ?: KewuApi.getCourses()
            courses = cl
            val list = KewuApi.getAssignments(courseFilter)
            assignments = list
            val pr = KewuApi.getPendingReview()
            if (pr != null) {
                pending = pr
                pendingLocal = false
            } else {
                pending = aggregatePendingLocally(list, cl)
                pendingLocal = true
            }
        }
    }
    LaunchedEffect(courseFilter) { reload() }

    LaunchedEffect(scrollState) {
        var last = 0
        snapshotFlow { scrollState.value }.collect { cur ->
            if (cur <= 0) onNavHide(false)
            else {
                val d = cur - last
                if (d > 6) onNavHide(true) else if (d < -6) onNavHide(false)
            }
            last = cur
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(pageBackdrop)
                .background(cs.surface),
        )
        AnimatedContent(
            targetState = drill,
            transitionSpec = {
                slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { w -> w } togetherWith
                    slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { w -> -w / 5 }
            },
            modifier = Modifier.fillMaxSize(),
            label = "teacherTaskNav",
        ) { d ->
            when (d) {
                TaskDrill.NONE -> TeacherAssignmentList(
                    backdrop = pageBackdrop,
                    scrollState = scrollState,
                    assignments = assignments,
                    courses = courses,
                    courseFilter = courseFilter,
                    pending = pending,
                    pendingLocal = pendingLocal,
                    onFilter = { courseFilter = it },
                    onOpen = { drill = TaskDrill.DETAIL(it) },
                    onOpenPending = { drill = TaskDrill.PENDING },
                    onPublish = { courseId -> drill = TaskDrill.PUBLISH(courseId) },
                )
                TaskDrill.PENDING -> PendingReviewScreen(
                    pending = pending,
                    local = pendingLocal,
                    onBack = { drill = TaskDrill.NONE },
                    onReview = { sid -> drill = TaskDrill.REVIEW(sid, back = TaskDrill.PENDING) },
                    onOpenAssignment = { aid -> drill = TaskDrill.DETAIL(aid, back = TaskDrill.PENDING) },
                )
                is TaskDrill.DETAIL -> TeacherAssignmentDetail(
                    assignmentId = d.assignmentId,
                    onBack = { drill = d.back },
                    onReview = { sid -> drill = TaskDrill.REVIEW(sid, back = d) },
                )
                is TaskDrill.REVIEW -> ReviewSubmissionScreen(
                    submissionId = d.submissionId,
                    // 批阅完回上一层时刷新一次待批阅（刚批掉的那份应从总览/顶部卡消失）
                    onBack = { drill = d.back; reload() },
                )
                is TaskDrill.PUBLISH -> PublishAssignmentScreen(
                    presetCourseId = d.courseId,
                    courses = courses,
                    onBack = { drill = TaskDrill.NONE },
                    onPublished = {
                        drill = TaskDrill.NONE
                        reload()
                        // 提示放页面级 scope：发布页随即离开组合，它自己的 scope 会把 Snackbar 一并取消
                        scope.launch { snackbar.toast("发布成功，已推送到学生任务页") }
                    },
                )
            }
        }
    }
}

/**
 * 降级聚合（getPendingReview 返回 null 时）：逐作业 getSubmissions 过滤 PENDING，拼成与 3.12 同形的
 * [PendingReview]，让顶部卡与总览页只认一种数据。本地拿不到积压时长 / 优先级 / 风险，相应字段置 0 / 空。
 */
private suspend fun aggregatePendingLocally(
    list: List<AssignmentInfo>?,
    courses: List<CourseInfo>?,
): PendingReview {
    fun courseName(id: String) = courses?.firstOrNull { it.id == id }?.name ?: id
    val items = mutableListOf<PendingReviewItem>()
    list?.forEach { a ->
        KewuApi.getSubmissions(a.id)?.filter { it.status == "PENDING" }?.forEach { s ->
            items += PendingReviewItem(
                submissionId = s.id, assignmentId = a.id, assignmentTitle = a.title,
                courseId = a.courseId, courseName = courseName(a.courseId),
                studentId = s.studentId, studentName = s.studentName, submittedAt = s.submittedAt,
                waitHours = 0.0, version = s.version, status = s.status,
                aiSuggestedScore = s.ai?.suggestedScore, riskLevel = s.ai?.riskLevel.orEmpty(),
                priority = 0.0, urgent = false, reasons = emptyList(),
            )
        }
    }
    val groups = items.groupBy { it.courseId }.map { (cid, its) ->
        PendingCourseGroup(
            courseId = cid, courseName = courseName(cid), pendingCount = its.size,
            assignmentCount = its.map { it.assignmentId }.distinct().size,
            urgentCount = 0, oldestWaitHours = 0.0,
            assignments = its.groupBy { it.assignmentId }.map { (aid, ai) ->
                PendingAssignmentGroup(
                    assignmentId = aid, title = ai.first().assignmentTitle,
                    deadline = list?.firstOrNull { it.id == aid }?.deadline.orEmpty(),
                    pendingCount = ai.size, urgentCount = 0,
                )
            },
        )
    }
    return PendingReview(
        teacherName = "", scope = "LOCAL", total = items.size, courseCount = groups.size,
        assignmentCount = items.map { it.assignmentId }.distinct().size,
        studentCount = items.map { it.studentId }.distinct().size,
        urgentCount = 0, highRiskCount = 0, oldestWaitHours = 0.0, avgWaitHours = 0.0,
        groups = groups, items = items,
    )
}

/** 积压时长人话化：< 1 小时按分钟，< 48 小时按小时，再往上按「X 天 Y 小时」（commonMain 无 String.format，手写）。 */
private fun fmtWait(hours: Double): String = when {
    hours <= 0.0 -> "刚刚"
    hours < 1.0 -> "${(hours * 60).toInt().coerceAtLeast(1)} 分钟"
    hours < 48.0 -> "${hours.toInt()} 小时"
    else -> {
        val h = hours.toInt()
        if (h % 24 == 0) "${h / 24} 天" else "${h / 24} 天 ${h % 24} 小时"
    }
}

/** 分数显示：整数分不带小数点。 */
private fun fmtScore(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

// ───────────────────────── 作业列表 ─────────────────────────

@Composable
private fun TeacherAssignmentList(
    backdrop: LayerBackdrop,
    scrollState: androidx.compose.foundation.ScrollState,
    assignments: List<AssignmentInfo>?,
    courses: List<CourseInfo>?,
    courseFilter: String?,
    pending: PendingReview?,
    pendingLocal: Boolean,
    onFilter: (String?) -> Unit,
    onOpen: (String) -> Unit,
    onOpenPending: () -> Unit,
    onPublish: (String) -> Unit,
) {
    val cs = COUITheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(bottom = 118.dp),
    ) {
        Spacer(Modifier.height(12.dp))

        // 发布作业大卡（教师高频入口，置顶；液态玻璃本体保留）
        val p0 = rememberEnterProgress(0)
        KewuGlassCard(
            backdrop = backdrop,
            shape = RoundedCornerShape(20.dp),
            blurRadius = glassCondenseBlur(p0, 22f),
            tintAlpha = 0.30f * p0,
            onClick = { onPublish(courseFilter ?: courses?.firstOrNull()?.id ?: "") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp)
                .glassEnter(p0)
                .border(1.dp, cs.dividerLine, RoundedCornerShape(20.dp)),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = cs.tertiaryContainer,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(COUIIcons.Add, contentDescription = null, tint = cs.onTertiaryContainer,
                            modifier = Modifier.size(22.dp))
                    }
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("发布新作业", color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text("一键推送到所有学生的任务页", color = cs.onSurfaceSecondary, fontSize = 12.5.sp)
                }
            }
        }

        // ★ 待批阅聚合卡（跨课程，点进「待批阅总览」）
        val pd = pending
        if (pd != null && pd.total > 0) {
            val pp = rememberEnterProgress(1)
            Box(Modifier.riseEnter(pp, 24f)) {
                PendingSummaryCard(pd, pendingLocal, onOpenPending)
            }
        }

        // 课程过滤 chips
        if (!courses.isNullOrEmpty()) {
            val p1 = rememberEnterProgress(1)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 10.dp)
                    .riseEnter(p1),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip(selected = courseFilter == null, onClick = { haptic(HapticKind.Tick); onFilter(null) }, label = "全部")
                courses.take(3).forEach { c ->
                    Chip(selected = courseFilter == c.id, onClick = { haptic(HapticKind.Tick); onFilter(c.id) },
                        label = c.name.take(6))
                }
            }
        }

        KewuSectionTitle("作业列表", Modifier.riseEnter(rememberEnterProgress(2)))
        when {
            assignments == null -> KewuLoading()
            assignments.isEmpty() -> KewuEmpty("暂无作业，点上方「发布新作业」开始")
            else -> assignments.forEachIndexed { i, a ->
                val p = rememberEnterProgress(3 + i, stepMs = 50L)
                TeacherAssignmentRow(a, p) { onOpen(a.id) }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** 顶部「待批阅 N 份」卡：总数 + 紧急（coui Badge 红点计数）+ 疑似雷同 + 最久积压 + 建议先改前两名。 */
@Composable
private fun PendingSummaryCard(pd: PendingReview, local: Boolean, onOpen: () -> Unit) {
    val cs = COUITheme.colorScheme
    Card(
        onClick = { haptic(HapticKind.Tick); onOpen() },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
        cornerRadius = 18.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        colors = kewuCardColors(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("待批阅", color = cs.error, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            KewuTag("${pd.total} 份", cs.error.copy(alpha = 0.14f), cs.error)
            if (pd.urgentCount > 0) {
                Spacer(Modifier.width(8.dp))
                Text("紧急", color = cs.onSurfaceSecondary, fontSize = 12.sp)
                Spacer(Modifier.width(4.dp))
                Badge(count = pd.urgentCount)
            }
            Spacer(Modifier.weight(1f))
            Text("总览", color = cs.onSurfaceSecondary, fontSize = 12.5.sp)
            Icon(COUIIcons.ChevronForward, contentDescription = null,
                tint = cs.onSurfaceVariantSummary, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (local) "涉及 ${pd.courseCount} 门课 · ${pd.assignmentCount} 道作业（本地汇总，暂无优先级）"
            else buildString {
                append("疑似雷同 ${pd.highRiskCount} 份")
                if (pd.oldestWaitHours > 0) append(" · 最久积压 ${fmtWait(pd.oldestWaitHours)}")
            },
            color = cs.onSurfaceSecondary, fontSize = 12.5.sp, maxLines = 1,
        )
        pd.items.take(2).takeIf { it.isNotEmpty() }?.let { top ->
            Spacer(Modifier.height(4.dp))
            Text(
                "建议先改：" + top.joinToString("、") { "${it.studentName}《${it.assignmentTitle}》" },
                color = cs.onSurfaceVariantSummary, fontSize = 12.sp, maxLines = 1,
            )
        }
    }
}

@Composable
private fun TeacherAssignmentRow(a: AssignmentInfo, enterP: Float, onClick: () -> Unit) {
    val cs = COUITheme.colorScheme
    Box(Modifier.riseEnter(enterP, 24f)) {
        Card(
            onClick = { haptic(HapticKind.Tick); onClick() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
            cornerRadius = 16.dp,
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            colors = kewuCardColors(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.title, color = cs.onSurface, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "课程 ${a.courseId} · 截止 ${a.deadline.ifBlank { "未设置" }} · 总分 ${a.totalScore}",
                        color = cs.onSurfaceSecondary, fontSize = 12.sp, maxLines = 1,
                    )
                }
                Text("批阅", color = KewuBlue, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Icon(COUIIcons.ChevronForward, contentDescription = null, tint = KewuBlue,
                    modifier = Modifier.size(16.dp))
            }
        }
    }
}

// ───────────────────────── 待批阅总览（文档 3.12） ─────────────────────────

/**
 * 跨课程待批阅总览：概览数字 → 按课程分组（课程 → 作业，带截止与份数）→ 「建议先改」明细
 * （服务端已按 priority 降序，逐条展示 reasons 人话理由），点明细进批阅页、点作业进作业详情。
 */
@Composable
private fun PendingReviewScreen(
    pending: PendingReview?,
    local: Boolean,
    onBack: () -> Unit,
    onReview: (String) -> Unit,
    onOpenAssignment: (String) -> Unit,
) {
    val cs = COUITheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
        KewuTopBar("待批阅总览", onBack = onBack)
        Spacer(Modifier.height(4.dp))
        when {
            pending == null -> KewuLoading(text = "正在汇总待批阅…")
            pending.total == 0 -> KewuEmpty("暂无待批阅，辛苦了")
            else -> {
                // 概览数字
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
                    cornerRadius = 18.dp,
                    insideMargin = PaddingValues(16.dp),
                    colors = kewuCardColors(),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TStat("${pending.total}", "待批阅", cs.tertiaryContainer, cs.onTertiaryContainer)
                        TStat("${pending.urgentCount}", "紧急",
                            if (pending.urgentCount > 0) cs.errorContainer else cs.secondaryContainer,
                            if (pending.urgentCount > 0) cs.onErrorContainer else cs.onSurface)
                        TStat("${pending.highRiskCount}", "疑似雷同",
                            if (pending.highRiskCount > 0) Color(0xFFFFB300).copy(alpha = 0.18f) else cs.secondaryContainer,
                            if (pending.highRiskCount > 0) Color(0xFF8A5A00) else cs.onSurface)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "涉及 ${pending.courseCount} 门课 · ${pending.assignmentCount} 道作业 · ${pending.studentCount} 名学生",
                        color = cs.onSurfaceSecondary, fontSize = 12.5.sp,
                    )
                    if (!local) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "平均积压 ${fmtWait(pending.avgWaitHours)} · 最久 ${fmtWait(pending.oldestWaitHours)}",
                            color = cs.onSurfaceSecondary, fontSize = 12.5.sp,
                        )
                    } else {
                        Spacer(Modifier.height(2.dp))
                        Text("服务端汇总暂不可用，以下为本地汇总（无优先级与积压数据）",
                            color = cs.onSurfaceVariantSummary, fontSize = 12.sp)
                    }
                }

                // 按课程分组
                KewuSectionTitle("按课程分组")
                pending.groups.forEach { g ->
                    KewuCardGroup {
                        BasicComponent(
                            title = g.courseName.ifBlank { g.courseId },
                            summary = buildString {
                                append("${g.pendingCount} 份待批 · ${g.assignmentCount} 道作业")
                                if (g.oldestWaitHours > 0) append(" · 最久 ${fmtWait(g.oldestWaitHours)}")
                            },
                            endActions = {
                                if (g.urgentCount > 0) Badge(count = g.urgentCount)
                            },
                        )
                        g.assignments.forEach { a ->
                            BasicComponent(
                                title = a.title,
                                summary = "截止 ${a.deadline.ifBlank { "未设置" }}",
                                endActions = {
                                    if (a.urgentCount > 0) {
                                        Badge()
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text("${a.pendingCount} 份", color = cs.onSurfaceSecondary, fontSize = 13.sp)
                                    Icon(COUIIcons.ChevronForward, contentDescription = null,
                                        tint = cs.onSurfaceVariantSummary, modifier = Modifier.size(16.dp))
                                },
                                onClick = { haptic(HapticKind.Tick); onOpenAssignment(a.assignmentId) },
                            )
                        }
                    }
                }

                // 建议先改（items 已按 priority 降序）
                KewuSectionTitle(if (local) "待批阅明细" else "建议先改（按优先级）")
                pending.items.forEach { item -> PendingItemCard(item) { onReview(item.submissionId) } }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 「建议先改」一条：学生 + 紧急红点 + 风险标签 / 作业·课程 / 等待·版本 / AI 建议分 / 逐条理由。 */
@Composable
private fun PendingItemCard(item: PendingReviewItem, onClick: () -> Unit) {
    val cs = COUITheme.colorScheme
    Card(
        onClick = { haptic(HapticKind.Tick); onClick() },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        colors = kewuCardColors(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.urgent) {
                Badge()
                Spacer(Modifier.width(6.dp))
            }
            Text(item.studentName.ifBlank { item.studentId }, color = cs.onSurface, fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1)
            RiskTag(item.riskLevel)
            Spacer(Modifier.weight(1f))
            Text(
                item.aiSuggestedScore?.let { "AI 建议 ${fmtScore(it)}" } ?: "待 AI 预批",
                color = if (item.aiSuggestedScore != null) KewuBlue else cs.onSurfaceVariantSummary,
                fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(3.dp))
        Text("${item.assignmentTitle} · ${item.courseName.ifBlank { item.courseId }}",
            color = cs.onSurfaceSecondary, fontSize = 12.5.sp, maxLines = 1)
        Text(
            buildString {
                if (item.waitHours > 0) append("已等待 ${fmtWait(item.waitHours)} · ")
                else if (item.submittedAt.isNotBlank()) append("提交于 ${item.submittedAt} · ")
                append("v${item.version}")
                if (item.status == "RETURNED") append(" · 打回待复批")
            },
            color = cs.onSurfaceSecondary, fontSize = 12.5.sp, maxLines = 1,
        )
        if (item.reasons.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            item.reasons.forEach { r ->
                Text("· $r", color = cs.onSurfaceVariantSummary, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
}

/** riskLevel（LOW / MID / HIGH）→ 彩色小标签；空值不显示。 */
@Composable
private fun RiskTag(level: String) {
    val cs = COUITheme.colorScheme
    val (label, bg, fg) = when (level.uppercase()) {
        "HIGH" -> Triple("高风险", cs.errorContainer, cs.onErrorContainer)
        "MID", "MEDIUM" -> Triple("中风险", Color(0xFFFFB300).copy(alpha = 0.18f), Color(0xFF8A5A00))
        "LOW" -> Triple("低风险", KewuGreen.copy(alpha = 0.16f), KewuGreen)
        else -> return
    }
    Spacer(Modifier.width(6.dp))
    KewuTag(label, bg, fg)
}

// ───────────────────────── 作业详情（教师视角） ─────────────────────────

@Composable
private fun TeacherAssignmentDetail(
    assignmentId: String,
    onBack: () -> Unit,
    onReview: (String) -> Unit,
) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    val pageBackdrop = rememberGlassBackdrop()

    var assignment by remember { mutableStateOf<AssignmentInfo?>(null) }
    var stats by remember { mutableStateOf<AssignmentStats?>(null) }
    var queue by remember { mutableStateOf<List<ReviewQueueItem>?>(null) }
    var similarity by remember { mutableStateOf<List<SimilarityPair>?>(null) }
    var loading by remember { mutableStateOf(true) }

    fun reload() {
        scope.launch {
            assignment = KewuApi.getAssignment(assignmentId)
            stats = KewuApi.getAssignmentStats(assignmentId)
            queue = KewuApi.getReviewQueue(assignmentId)
            similarity = KewuApi.getSimilarity(assignmentId)
            loading = false
        }
    }
    LaunchedEffect(assignmentId) { reload() }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().layerBackdrop(pageBackdrop).background(cs.surface))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
            KewuTopBar(assignment?.title ?: "作业详情", onBack = onBack)
            Spacer(Modifier.height(4.dp))
            if (loading) {
                KewuLoading()
            } else {
                assignment?.let { a ->
                    // 头部信息卡
                    KewuGlassCard(
                        backdrop = pageBackdrop, shape = RoundedCornerShape(20.dp),
                        blurRadius = 22.dp, tintAlpha = 0.28f,
                        modifier = Modifier
                            .fillMaxWidth().padding(horizontal = 12.dp)
                            .border(1.dp, cs.dividerLine, RoundedCornerShape(20.dp)),
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Text(a.title, color = cs.onSurface, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text("截止 ${a.deadline.ifBlank { "未设置" }} · 总分 ${a.totalScore}",
                                color = cs.onSurfaceSecondary, fontSize = 13.sp)
                            if (a.knowledgePoints.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    a.knowledgePoints.take(4).forEach {
                                        KewuTag(it, cs.tertiaryContainer, cs.onTertiaryContainer)
                                    }
                                }
                            }
                            if (a.description.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(a.description, color = cs.onSurfaceSecondary, fontSize = 13.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))

                    // 批阅统计
                    KewuSectionTitle("批阅统计")
                    TeacherStatsCard(pageBackdrop, stats)
                    Spacer(Modifier.height(6.dp))

                    // 智能批阅队列（点进批阅详情）
                    KewuSectionTitle("智能批阅队列（AI 按优先级排序）")
                    TeacherQueueCard(pageBackdrop, queue, onReview)
                    Spacer(Modifier.height(6.dp))

                    // 相似度查重
                    KewuSectionTitle("相似片段检测")
                    TeacherSimilarityCard(pageBackdrop, similarity)
                    Spacer(Modifier.height(24.dp))
                } ?: KewuEmpty("未找到该作业")
            }
        }
    }
}

@Composable
private fun TeacherStatsCard(backdrop: LayerBackdrop, stats: AssignmentStats?) {
    val cs = COUITheme.colorScheme
    KewuGlassCard(
        backdrop = backdrop, shape = RoundedCornerShape(16.dp), blurRadius = 18.dp, tintAlpha = 0.30f,
        modifier = Modifier
            .fillMaxWidth().padding(horizontal = 12.dp)
            .border(1.dp, cs.dividerLine, RoundedCornerShape(16.dp)),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (stats == null) {
                Text("暂无批阅数据", color = cs.onSurfaceSecondary, fontSize = 13.sp)
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TStat("${stats.submittedCount}", "已提交", cs.tertiaryContainer, cs.onTertiaryContainer)
                TStat("${stats.pendingCount}", "待批阅", cs.secondaryContainer, cs.onSecondaryVariant)
                TStat("${stats.reviewedCount}", "已批阅", KewuGreen.copy(alpha = 0.16f), KewuGreen)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TStat("${stats.avgScore}", "均分", cs.secondaryContainer, cs.onSurface)
                TStat("${stats.highRiskCount}", "高风险",
                    if (stats.highRiskCount > 0) cs.errorContainer else cs.secondaryContainer,
                    if (stats.highRiskCount > 0) cs.onErrorContainer else cs.onSurface)
            }
            if (stats.scoreDistribution.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("分数分布", color = cs.onSurfaceSecondary, fontSize = 12.5.sp)
                Spacer(Modifier.height(6.dp))
                val maxCount = (stats.scoreDistribution.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
                stats.scoreDistribution.forEach { b ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Text(b.label, color = cs.onSurfaceSecondary, fontSize = 12.sp, modifier = Modifier.width(64.dp))
                        LinearProgressIndicator(
                            progress = b.count.toFloat() / maxCount,
                            modifier = Modifier.weight(1f),
                            colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = KewuBlue),
                            height = 12.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("${b.count}", color = cs.onSurface, fontSize = 12.sp, modifier = Modifier.width(28.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.TStat(value: String, label: String, bg: Color, fg: Color) {
    Surface(
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(12.dp),
        color = bg,
        contentColor = fg,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, color = fg, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(label, color = fg.copy(alpha = 0.75f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun TeacherQueueCard(backdrop: LayerBackdrop, items: List<ReviewQueueItem>?, onReview: (String) -> Unit) {
    val cs = COUITheme.colorScheme
    KewuGlassCard(
        backdrop = backdrop, shape = RoundedCornerShape(16.dp), blurRadius = 18.dp, tintAlpha = 0.30f,
        modifier = Modifier
            .fillMaxWidth().padding(horizontal = 12.dp)
            .border(1.dp, cs.dividerLine, RoundedCornerShape(16.dp)),
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            if (items.isNullOrEmpty()) {
                Text("批阅队列为空", color = cs.onSurfaceSecondary, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                return@Column
            }
            // 队列行：coui BasicComponent（整行可点，COUI 原生按压反馈）
            items.forEach { item ->
                BasicComponent(
                    title = item.studentName,
                    summary = "v${item.version} · 优先级 ${item.priority}" +
                        if (item.reasons.isNotEmpty()) " · " + item.reasons.joinToString("；") else "",
                    endActions = {
                        Text("AI 建议 ${item.aiSuggestedScore}", color = KewuBlue, fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold)
                    },
                    onClick = { haptic(HapticKind.Tick); onReview(item.submissionId) },
                )
            }
        }
    }
}

@Composable
private fun TeacherSimilarityCard(backdrop: LayerBackdrop, pairs: List<SimilarityPair>?) {
    val cs = COUITheme.colorScheme
    KewuGlassCard(
        backdrop = backdrop, shape = RoundedCornerShape(16.dp), blurRadius = 18.dp, tintAlpha = 0.30f,
        modifier = Modifier
            .fillMaxWidth().padding(horizontal = 12.dp)
            .border(1.dp, cs.dividerLine, RoundedCornerShape(16.dp)),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (pairs.isNullOrEmpty()) {
                Text("未检测到相似片段", color = cs.onSurfaceSecondary, fontSize = 13.sp)
                return@Column
            }
            pairs.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${p.studentA} ↔ ${p.studentB}", color = cs.onSurface, fontSize = 14.sp,
                            fontWeight = FontWeight.Medium)
                        if (p.sharedFragments.isNotEmpty()) {
                            Text(p.sharedFragments.joinToString("；").take(60),
                                color = cs.onSurfaceSecondary, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                    val sim = (p.similarity * 100).toInt()
                    Text("$sim%", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        color = if (sim >= 70) cs.error else if (sim >= 40) Color(0xFFE09A00) else KewuGreen)
                }
            }
        }
    }
}

// ───────────────────────── 批阅详情（AI 辅助 + 打分） ─────────────────────────

@Composable
private fun ReviewSubmissionScreen(
    submissionId: String,
    onBack: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    val snackbar = LocalKewuSnackbar.current
    val pageBackdrop = rememberGlassBackdrop()

    var sub by remember { mutableStateOf<SubmissionInfo?>(null) }
    var ai by remember { mutableStateOf<AiSuggestion?>(null) }
    var aiLoading by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var scoreText by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }

    LaunchedEffect(submissionId) {
        sub = KewuApi.getSubmission(submissionId)
        sub?.ai?.let { ai = it }
        loading = false
    }

    val reviewer = KewuApi.currentUser?.user?.nickname ?: KewuApi.username ?: "老师"

    fun submit(action: String) {
        val score = scoreText.toDoubleOrNull() ?: ai?.suggestedScore ?: 0.0
        if (submitting) return
        submitting = true
        scope.launch {
            val err = KewuApi.reviewSubmission(submissionId, score, comment.trim(), reviewer, action)
            submitting = false
            // 结果走全局 coui Snackbar（原内联结果文字行）
            snackbar.toast(err ?: if (action == "return") "已打回" else "批阅完成")
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().layerBackdrop(pageBackdrop).background(cs.surface))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
            KewuTopBar("批阅 · ${sub?.studentName ?: ""}", onBack = onBack)
            Spacer(Modifier.height(4.dp))
            val s = sub
            if (loading) {
                KewuLoading()
            } else if (s == null) {
                KewuEmpty("未找到该提交")
            } else {
                // 提交内容
                KewuGlassCard(
                    backdrop = pageBackdrop, shape = RoundedCornerShape(18.dp), blurRadius = 20.dp, tintAlpha = 0.30f,
                    modifier = Modifier
                        .fillMaxWidth().padding(horizontal = 12.dp)
                        .border(1.dp, cs.dividerLine, RoundedCornerShape(18.dp)),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.studentName, color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f))
                            KewuTag("v${s.version}", cs.tertiaryContainer, cs.onTertiaryContainer)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("提交于 ${s.submittedAt.ifBlank { "—" }} · 状态 ${s.status}",
                            color = cs.onSurfaceSecondary, fontSize = 12.5.sp)
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = cs.secondaryContainer,
                        ) {
                            Text(s.content.ifBlank { "（无文字内容）" }, color = cs.onSurface,
                                fontSize = 13.5.sp, lineHeight = 20.sp, modifier = Modifier.padding(12.dp))
                        }
                        if (s.history.size > 1) {
                            Spacer(Modifier.height(8.dp))
                            Text("历史版本 ${s.history.size} 个", color = cs.onSurfaceSecondary, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))

                // AI 辅助批阅
                KewuSectionTitle("AI 辅助批阅")
                KewuGlassCard(
                    backdrop = pageBackdrop, shape = RoundedCornerShape(16.dp), blurRadius = 18.dp, tintAlpha = 0.30f,
                    modifier = Modifier
                        .fillMaxWidth().padding(horizontal = 12.dp)
                        .border(1.dp, cs.dividerLine, RoundedCornerShape(16.dp)),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        val a = ai
                        if (a == null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("让 AI 先给一份初步评价，老师只需确认或修改",
                                    color = cs.onSurfaceSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                if (aiLoading) InfiniteProgressIndicator(size = 18.dp)
                            }
                            Spacer(Modifier.height(10.dp))
                            TextButton(
                                text = if (aiLoading) "AI 分析中…" else "AI 建议",
                                onClick = {
                                    if (aiLoading) return@TextButton
                                    haptic(HapticKind.Light)
                                    aiLoading = true
                                    scope.launch {
                                        ai = KewuApi.aiPrereview(submissionId)
                                        ai?.let { if (scoreText.isBlank()) scoreText = it.suggestedScore.toString() }
                                        aiLoading = false
                                    }
                                },
                                enabled = !aiLoading,
                                minHeight = 36.dp,
                                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        } else {
                            Text("建议分 ${a.suggestedScore} · 置信度 ${(a.confidence * 100).toInt()}% · 风险 ${a.riskLevel}",
                                color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            if (a.summary.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(a.summary, color = cs.onSurfaceSecondary, fontSize = 13.sp)
                            }
                            if (a.hitKnowledgePoints.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    a.hitKnowledgePoints.take(4).forEach {
                                        KewuTag(it, KewuGreen.copy(alpha = 0.16f), KewuGreen)
                                    }
                                }
                            }
                            if (a.reasons.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                a.reasons.take(4).forEach { r ->
                                    Text("· ${r.item}：${r.detail}", color = cs.onSurfaceSecondary, fontSize = 12.5.sp)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))

                // 打分与评语
                KewuSectionTitle("老师批阅")
                ReviewField("分数", scoreText, { scoreText = it }, placeholder = "如 85")
                ReviewField("评语", comment, { comment = it }, singleLine = false, placeholder = "给学生的评语…")
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(
                        text = "打回",
                        onClick = { haptic(HapticKind.Tick); submit("return") },
                        colors = ButtonDefaults.textButtonColorsPrimary(
                            color = cs.errorContainer, textColor = cs.onErrorContainer),
                        modifier = Modifier.weight(1f),
                    )
                    KewuPrimaryButton(
                        text = if (submitting) "提交中…" else "确认打分",
                        onClick = { submit("approve") },
                        enabled = !submitting,
                        modifier = Modifier.weight(2f),
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** 批阅/发布表单字段：coui 原生 TextField（Rectangle 盒式），label 兼作占位。 */
@Composable
private fun ReviewField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
    placeholder: String = "",
) {
    val cs = COUITheme.colorScheme
    val state = rememberTextFieldState(initialText = value)
    // state.text 是输入的唯一事实源，单向同步给外层（外层回写的 value 不回灌 state，避免光标跳动）
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { onValueChange(it) }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
        Text(label, color = cs.onSurfaceSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 6.dp))
        TextField(
            state = state,
            modifier = Modifier.fillMaxWidth(),
            backgroundMode = TextFieldMode.Rectangle,
            label = placeholder.ifEmpty { label },
            lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.Default,
        )
    }
}

// ───────────────────────── 发布作业 ─────────────────────────

@Composable
private fun PublishAssignmentScreen(
    presetCourseId: String,
    courses: List<CourseInfo>?,
    onBack: () -> Unit,
    onPublished: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    val snackbar = LocalKewuSnackbar.current
    val pageBackdrop = rememberGlassBackdrop()

    var courseId by remember { mutableStateOf(presetCourseId) }
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var deadline by remember { mutableStateOf("") }
    var totalScore by remember { mutableStateOf("100") }
    var kps by remember { mutableStateOf("") }
    var publishing by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().layerBackdrop(pageBackdrop).background(cs.surface))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
            KewuTopBar("发布新作业", onBack = onBack)
            Spacer(Modifier.height(4.dp))
            KewuSectionTitle("选择课程")
            if (courses.isNullOrEmpty()) {
                KewuEmpty("暂无可选课程")
            } else {
                // 课程单选：coui 单选设置行（同一张分组卡）
                KewuCardGroup {
                    courses.forEach { c ->
                        RadioButtonPreference(
                            title = c.name,
                            selected = courseId == c.id,
                            onClick = { haptic(HapticKind.Tick); courseId = c.id },
                        )
                    }
                }
            }
            ReviewField("作业标题", title, { title = it }, placeholder = "如 第 5 章习题")
            ReviewField("作业描述", desc, { desc = it }, singleLine = false, placeholder = "题目要求、提交方式…")
            ReviewField("截止时间", deadline, { deadline = it }, placeholder = "如 2026-10-25 23:59")
            ReviewField("总分", totalScore, { totalScore = it.filter { ch -> ch.isDigit() } }, placeholder = "100")
            ReviewField("知识点（逗号分隔）", kps, { kps = it }, placeholder = "如 泰勒公式, 洛必达法则")
            KewuPrimaryButton(
                text = if (publishing) "发布中…" else "发布作业",
                onClick = {
                    if (courseId.isBlank() || title.isBlank() || publishing) return@KewuPrimaryButton
                    publishing = true
                    scope.launch {
                        val publisher = KewuApi.currentUser?.user?.nickname ?: KewuApi.username ?: ""
                        val info = KewuApi.publishAssignment(
                            courseId = courseId, title = title.trim(), description = desc.trim(),
                            deadline = deadline.trim(), totalScore = totalScore.toIntOrNull() ?: 100,
                            knowledgePoints = kps.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() },
                            publisher = publisher,
                        )
                        publishing = false
                        if (info != null) {
                            haptic(HapticKind.Light)
                            onPublished()
                        } else {
                            snackbar.toast("发布失败，请检查网络后重试")
                        }
                    }
                },
                enabled = courseId.isNotBlank() && title.isNotBlank() && !publishing,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
