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
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
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
import com.kewu.app.ReviewQueueItem
import com.kewu.app.SimilarityPair
import com.kewu.app.SubmissionInfo
import com.kewu.app.haptic
import com.kewu.app.hapticClickable
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ───────────────────────── 教师端 · 作业（tab 1） ─────────────────────────
//
// 功能闭环（全部走 KewuApi 真实接口，失败降级提示不白屏）：
//   作业列表（getAssignments，按课程过滤 chip）→
//   作业详情：批阅统计（getAssignmentStats）/ 智能批阅队列（getReviewQueue，点进批阅）/
//             相似度查重（getSimilarity）/ 发布新作业（publishAssignment）→
//   批阅详情：提交内容 + 历史版本（getSubmission）+ AI 辅助批阅（aiPrereview）+
//             打分评语提交（reviewSubmission，approve=打分 / return=打回）。
//
// 视觉与学生端同一套语言：coui 液态玻璃卡 + squircle 行 + M3 语义色 + 错峰凝结入场。

private sealed class TaskDrill {
    data object NONE : TaskDrill()
    data class DETAIL(val assignmentId: String) : TaskDrill()
    data class REVIEW(val submissionId: String) : TaskDrill()
    data class PUBLISH(val courseId: String) : TaskDrill()
}

@Composable
fun TeacherTaskPage(onNavHide: (Boolean) -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    val pageBackdrop = rememberGlassBackdrop()
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    var drill by remember { mutableStateOf<TaskDrill>(TaskDrill.NONE) }
    BackHandler(enabled = drill != TaskDrill.NONE) {
        // 批阅详情回作业详情；其余回列表
        drill = when (val d = drill) {
            is TaskDrill.REVIEW -> TaskDrill.NONE // 无 assignmentId 上下文时直接回列表
            else -> TaskDrill.NONE
        }
    }

    var assignments by remember { mutableStateOf<List<AssignmentInfo>?>(null) }
    var courses by remember { mutableStateOf<List<CourseInfo>?>(null) }
    var courseFilter by remember { mutableStateOf<String?>(null) }
    // ★ 跨课程「待批阅」聚合（2026-10-21）：文档 5.6 的 /api/teacher/assignments/pending 尚未上线，
    //   先用现有接口交叉（每份作业的 submissions 过滤 PENDING）；接口上线后在此替换即可。
    var pendingSubs by remember { mutableStateOf<List<Pair<AssignmentInfo, SubmissionInfo>>?>(null) }

    fun reload() {
        scope.launch {
            if (KewuApi.currentUser == null) KewuApi.me()
            courses = KewuApi.currentUser?.courses?.takeIf { it.isNotEmpty() } ?: KewuApi.getCourses()
            val list = KewuApi.getAssignments(courseFilter)
            assignments = list
            val acc = mutableListOf<Pair<AssignmentInfo, SubmissionInfo>>()
            list?.forEach { a ->
                KewuApi.getSubmissions(a.id)?.let { subs ->
                    subs.filter { it.status == "PENDING" }.forEach { acc += a to it }
                }
            }
            pendingSubs = acc
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
                .background(cs.background),
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
                    pendingSubs = pendingSubs,
                    onFilter = { courseFilter = it },
                    onOpen = { drill = TaskDrill.DETAIL(it) },
                    onOpenPending = { drill = TaskDrill.REVIEW(it) },
                    onPublish = { courseId -> drill = TaskDrill.PUBLISH(courseId) },
                )
                is TaskDrill.DETAIL -> TeacherAssignmentDetail(
                    assignmentId = d.assignmentId,
                    onBack = { drill = TaskDrill.NONE },
                    onReview = { sid -> drill = TaskDrill.REVIEW(sid) },
                )
                is TaskDrill.REVIEW -> ReviewSubmissionScreen(
                    submissionId = d.submissionId,
                    onBack = { drill = TaskDrill.NONE },
                )
                is TaskDrill.PUBLISH -> PublishAssignmentScreen(
                    presetCourseId = d.courseId,
                    courses = courses,
                    onBack = { drill = TaskDrill.NONE },
                    onPublished = { drill = TaskDrill.NONE; reload() },
                )
            }
        }
    }
}

// ───────────────────────── 作业列表 ─────────────────────────

@Composable
private fun TeacherAssignmentList(
    backdrop: LayerBackdrop,
    scrollState: androidx.compose.foundation.ScrollState,
    assignments: List<AssignmentInfo>?,
    courses: List<CourseInfo>?,
    courseFilter: String?,
    pendingSubs: List<Pair<AssignmentInfo, SubmissionInfo>>?,
    onFilter: (String?) -> Unit,
    onOpen: (String) -> Unit,
    onOpenPending: (String) -> Unit,
    onPublish: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(bottom = 118.dp),
    ) {
        Spacer(Modifier.height(12.dp))

        // 发布作业大卡（教师高频入口，置顶）
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
                .border(1.dp, cs.outlineVariant, RoundedCornerShape(20.dp)),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(KewuBlue.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) { Text("＋", color = KewuBlue, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("发布新作业", color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text("一键推送到所有学生的任务页", color = cs.onSurfaceVariant, fontSize = 12.5.sp)
                }
            }
        }

        // ★ 待批阅聚合卡（跨课程，老师一进作业 tab 直接批）
        val pd = pendingSubs
        if (!pd.isNullOrEmpty()) {
            val pp = rememberEnterProgress(1)
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp).riseEnter(pp, 24f)) {
                Box(Modifier.fillMaxWidth().squircleCard(18.dp, cs.surface).padding(14.dp)) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("待批阅", color = cs.error, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(6.dp))
                            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(cs.error.copy(alpha = 0.14f)).padding(horizontal = 7.dp, vertical = 2.dp)) {
                                Text("${pd.size} 份", color = cs.error, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.weight(1f))
                            Text("点任意一条直接批阅", color = cs.onSurfaceVariant, fontSize = 11.5.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        pd.take(3).forEach { (a, s) ->
                            Box(
                                Modifier.fillMaxWidth().padding(bottom = 6.dp)
                                    .squircleCard(12.dp, cs.surfaceVariant)
                                    .hapticClickable { haptic(HapticKind.Tick); onOpenPending(s.id) }
                                    .padding(10.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(s.studentName, color = cs.onSurface, fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Medium, maxLines = 1)
                                        Spacer(Modifier.height(1.dp))
                                        Text("${a.title} · ${courses?.firstOrNull { it.id == a.courseId }?.name ?: a.courseId}",
                                            color = cs.onSurfaceVariant, fontSize = 11.5.sp, maxLines = 1)
                                    }
                                    Text("去批阅 ›", color = cs.primary, fontSize = 12.sp)
                                }
                            }
                        }
                        if (pd.size > 3) {
                            Text("还有 ${pd.size - 3} 份，到对应作业详情里批阅",
                                color = cs.onSurfaceVariant, fontSize = 11.5.sp)
                        }
                    }
                }
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
                Chip(selected = courseFilter == null, onClick = { onFilter(null) }, label = "全部")
                courses.take(3).forEach { c ->
                    Chip(selected = courseFilter == c.id, onClick = { onFilter(c.id) }, label = c.name.take(6))
                }
            }
        }

        GroupLabel("作业列表", Modifier.riseEnter(rememberEnterProgress(2)))
        when {
            assignments == null -> Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = cs.primary, strokeWidth = 3.dp, modifier = Modifier.size(26.dp))
            }
            assignments.isEmpty() -> Text(
                "暂无作业，点上方「发布新作业」开始",
                color = cs.onSurfaceVariant, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            else -> assignments.forEachIndexed { i, a ->
                val p = rememberEnterProgress(3 + i, stepMs = 50L)
                TeacherAssignmentRow(a, p) { onOpen(a.id) }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun TeacherAssignmentRow(a: AssignmentInfo, enterP: Float, onClick: () -> Unit) {
    Box(Modifier.riseEnter(enterP, 24f)) {
        Card(
            onClick = { haptic(HapticKind.Tick); onClick() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.title, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "课程 ${a.courseId} · 截止 ${a.deadline.ifBlank { "未设置" }} · 总分 ${a.totalScore}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1,
                    )
                }
                Text("批阅 ›", color = KewuBlue, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ───────────────────────── 作业详情（教师视角） ─────────────────────────

@Composable
private fun TeacherAssignmentDetail(
    assignmentId: String,
    onBack: () -> Unit,
    onReview: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
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
        Box(Modifier.matchParentSize().layerBackdrop(pageBackdrop).background(cs.background))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
            HubTopBar(assignment?.title ?: "作业详情", onBack)
            Spacer(Modifier.height(4.dp))
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = cs.primary, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                }
            } else {
                assignment?.let { a ->
                    // 头部信息卡
                    KewuGlassCard(
                        backdrop = pageBackdrop, shape = RoundedCornerShape(20.dp),
                        blurRadius = 22.dp, tintAlpha = 0.28f,
                        modifier = Modifier
                            .fillMaxWidth().padding(horizontal = 12.dp)
                            .border(1.dp, cs.outlineVariant, RoundedCornerShape(20.dp)),
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Text(a.title, color = cs.onSurface, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text("截止 ${a.deadline.ifBlank { "未设置" }} · 总分 ${a.totalScore}",
                                color = cs.onSurfaceVariant, fontSize = 13.sp)
                            if (a.knowledgePoints.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    a.knowledgePoints.take(4).forEach {
                                        StatusTag(it, cs.primaryContainer, cs.onPrimaryContainer)
                                    }
                                }
                            }
                            if (a.description.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(a.description, color = cs.onSurfaceVariant, fontSize = 13.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))

                    // 批阅统计
                    HubSectionLabel("批阅统计")
                    TeacherStatsCard(pageBackdrop, stats)
                    Spacer(Modifier.height(14.dp))

                    // 智能批阅队列（点进批阅详情）
                    HubSectionLabel("智能批阅队列（AI 按优先级排序）")
                    TeacherQueueCard(pageBackdrop, queue, onReview)
                    Spacer(Modifier.height(14.dp))

                    // 相似度查重
                    HubSectionLabel("相似片段检测")
                    TeacherSimilarityCard(pageBackdrop, similarity)
                    Spacer(Modifier.height(24.dp))
                } ?: Text("未找到该作业", color = cs.onSurfaceVariant, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun TeacherStatsCard(backdrop: LayerBackdrop, stats: AssignmentStats?) {
    val cs = MaterialTheme.colorScheme
    KewuGlassCard(
        backdrop = backdrop, shape = RoundedCornerShape(16.dp), blurRadius = 18.dp, tintAlpha = 0.30f,
        modifier = Modifier
            .fillMaxWidth().padding(horizontal = 12.dp)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp)),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (stats == null) {
                Text("暂无批阅数据", color = cs.onSurfaceVariant, fontSize = 13.sp)
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TStat("${stats.submittedCount}", "已提交", cs.primaryContainer, cs.onPrimaryContainer)
                TStat("${stats.pendingCount}", "待批阅", cs.secondaryContainer, cs.onSecondaryContainer)
                TStat("${stats.reviewedCount}", "已批阅", KewuGreen.copy(alpha = 0.16f), KewuGreen)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TStat("${stats.avgScore}", "均分", cs.surfaceVariant, cs.onSurface)
                TStat("${stats.highRiskCount}", "高风险",
                    if (stats.highRiskCount > 0) cs.errorContainer else cs.surfaceVariant,
                    if (stats.highRiskCount > 0) cs.onErrorContainer else cs.onSurface)
            }
            if (stats.scoreDistribution.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("分数分布", color = cs.onSurfaceVariant, fontSize = 12.5.sp)
                Spacer(Modifier.height(6.dp))
                val maxCount = (stats.scoreDistribution.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
                stats.scoreDistribution.forEach { b ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Text(b.label, color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.width(64.dp))
                        Box(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(cs.surfaceVariant)) {
                            Box(Modifier.fillMaxWidth(b.count.toFloat() / maxCount).height(12.dp)
                                .clip(RoundedCornerShape(6.dp)).background(KewuBlue))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("${b.count}", color = cs.onSurface, fontSize = 12.sp, modifier = Modifier.width(28.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TStat(value: String, label: String, bg: Color, fg: Color) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(vertical = 10.dp).weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = fg, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(label, color = fg.copy(alpha = 0.75f), fontSize = 11.sp)
    }
}

@Composable
private fun TeacherQueueCard(backdrop: LayerBackdrop, items: List<ReviewQueueItem>?, onReview: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    KewuGlassCard(
        backdrop = backdrop, shape = RoundedCornerShape(16.dp), blurRadius = 18.dp, tintAlpha = 0.30f,
        modifier = Modifier
            .fillMaxWidth().padding(horizontal = 12.dp)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp)),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (items.isNullOrEmpty()) {
                Text("批阅队列为空", color = cs.onSurfaceVariant, fontSize = 13.sp)
                return@Column
            }
            items.forEach { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .hapticClickable { haptic(HapticKind.Tick); onReview(item.submissionId) }
                        .padding(vertical = 7.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.studentName, color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "v${item.version} · 优先级 ${item.priority}${if (item.reasons.isNotEmpty()) " · " + item.reasons.joinToString("；") else ""}",
                            color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 2,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("AI 建议 ${item.aiSuggestedScore}", color = KewuBlue, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun TeacherSimilarityCard(backdrop: LayerBackdrop, pairs: List<SimilarityPair>?) {
    val cs = MaterialTheme.colorScheme
    KewuGlassCard(
        backdrop = backdrop, shape = RoundedCornerShape(16.dp), blurRadius = 18.dp, tintAlpha = 0.30f,
        modifier = Modifier
            .fillMaxWidth().padding(horizontal = 12.dp)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp)),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (pairs.isNullOrEmpty()) {
                Text("未检测到相似片段", color = cs.onSurfaceVariant, fontSize = 13.sp)
                return@Column
            }
            pairs.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${p.studentA} ↔ ${p.studentB}", color = cs.onSurface, fontSize = 14.sp,
                            fontWeight = FontWeight.Medium)
                        if (p.sharedFragments.isNotEmpty()) {
                            Text(p.sharedFragments.joinToString("；").take(60),
                                color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                    val sim = (p.similarity * 100).toInt()
                    Text("$sim%", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        color = if (sim >= 70) cs.error else if (sim >= 40) cs.tertiary else KewuGreen)
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
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val pageBackdrop = rememberGlassBackdrop()

    var sub by remember { mutableStateOf<SubmissionInfo?>(null) }
    var ai by remember { mutableStateOf<AiSuggestion?>(null) }
    var aiLoading by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var scoreText by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var resultMsg by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

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
        resultMsg = null
        scope.launch {
            val err = KewuApi.reviewSubmission(submissionId, score, comment.trim(), reviewer, action)
            submitting = false
            resultMsg = if (err == null) true to if (action == "return") "已打回" else "批阅完成"
            else false to err
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().layerBackdrop(pageBackdrop).background(cs.background))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
            HubTopBar("批阅 · ${sub?.studentName ?: ""}", onBack)
            Spacer(Modifier.height(4.dp))
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = cs.primary, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                }
            } else if (sub == null) {
                Text("未找到该提交", color = cs.onSurfaceVariant, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp))
            } else {
                val s = sub!!
                // 提交内容
                KewuGlassCard(
                    backdrop = pageBackdrop, shape = RoundedCornerShape(18.dp), blurRadius = 20.dp, tintAlpha = 0.30f,
                    modifier = Modifier
                        .fillMaxWidth().padding(horizontal = 12.dp)
                        .border(1.dp, cs.outlineVariant, RoundedCornerShape(18.dp)),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.studentName, color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f))
                            StatusTag("v${s.version}", cs.primaryContainer, cs.onPrimaryContainer)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("提交于 ${s.submittedAt.ifBlank { "—" }} · 状态 ${s.status}",
                            color = cs.onSurfaceVariant, fontSize = 12.5.sp)
                        Spacer(Modifier.height(10.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(cs.surfaceVariant)
                                .padding(12.dp),
                        ) {
                            Text(s.content.ifBlank { "（无文字内容）" }, color = cs.onSurface,
                                fontSize = 13.5.sp, lineHeight = 20.sp)
                        }
                        if (s.history.size > 1) {
                            Spacer(Modifier.height(8.dp))
                            Text("历史版本 ${s.history.size} 个", color = cs.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))

                // AI 辅助批阅
                HubSectionLabel("AI 辅助批阅")
                KewuGlassCard(
                    backdrop = pageBackdrop, shape = RoundedCornerShape(16.dp), blurRadius = 18.dp, tintAlpha = 0.30f,
                    modifier = Modifier
                        .fillMaxWidth().padding(horizontal = 12.dp)
                        .border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp)),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        if (ai == null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("让 AI 先给一份初步评价，老师只需确认或修改",
                                    color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                if (aiLoading) CircularProgressIndicator(strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(18.dp), color = cs.primary)
                            }
                            Spacer(Modifier.height(10.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(KewuBlue)
                                    .hapticClickable {
                                        if (aiLoading) return@hapticClickable
                                        aiLoading = true
                                        scope.launch {
                                            ai = KewuApi.aiPrereview(submissionId)
                                            ai?.let { if (scoreText.isBlank()) scoreText = it.suggestedScore.toString() }
                                            aiLoading = false
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 9.dp),
                            ) { Text(if (aiLoading) "AI 分析中…" else "AI 建议", color = Color.White,
                                fontSize = 13.5.sp, fontWeight = FontWeight.Bold) }
                        } else {
                            val a = ai!!
                            Text("建议分 ${a.suggestedScore} · 置信度 ${(a.confidence * 100).toInt()}% · 风险 ${a.riskLevel}",
                                color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            if (a.summary.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(a.summary, color = cs.onSurfaceVariant, fontSize = 13.sp)
                            }
                            if (a.hitKnowledgePoints.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    a.hitKnowledgePoints.take(4).forEach {
                                        StatusTag(it, KewuGreen.copy(alpha = 0.16f), KewuGreen)
                                    }
                                }
                            }
                            if (a.reasons.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                a.reasons.take(4).forEach { r ->
                                    Text("· ${r.item}：${r.detail}", color = cs.onSurfaceVariant, fontSize = 12.5.sp)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))

                // 打分与评语
                HubSectionLabel("老师批阅")
                ReviewField("分数", scoreText, { scoreText = it }, placeholder = "如 85")
                ReviewField("评语", comment, { comment = it }, singleLine = false, placeholder = "给学生的评语…")
                resultMsg?.let {
                    Text(it.second, color = if (it.first) KewuGreen else cs.error, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { submit("return") },
                        colors = ButtonDefaults.buttonColors(
                            color = cs.errorContainer, contentColor = cs.onErrorContainer),
                        modifier = Modifier.weight(1f),
                    ) { Text("打回", fontWeight = FontWeight.Bold) }
                    Button(
                        onClick = { submit("approve") },
                        enabled = !submitting,
                        colors = ButtonDefaults.buttonColors(color = KewuBlue, contentColor = Color.White),
                        modifier = Modifier.weight(2f),
                    ) { Text(if (submitting) "提交中…" else "确认打分", fontWeight = FontWeight.Bold) }
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
    val cs = MaterialTheme.colorScheme
    val state = rememberTextFieldState(initialText = value)
    // state.text 是输入的唯一事实源，单向同步给外层（外层回写的 value 不回灌 state，避免光标跳动）
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { onValueChange(it) }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
        Text(label, color = cs.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Medium,
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
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val pageBackdrop = rememberGlassBackdrop()

    var courseId by remember { mutableStateOf(presetCourseId) }
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var deadline by remember { mutableStateOf("") }
    var totalScore by remember { mutableStateOf("100") }
    var kps by remember { mutableStateOf("") }
    var publishing by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().layerBackdrop(pageBackdrop).background(cs.background))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
            HubTopBar("发布新作业", onBack)
            Spacer(Modifier.height(4.dp))
            HubSectionLabel("选择课程")
            if (courses.isNullOrEmpty()) {
                Text("暂无可选课程", color = cs.onSurfaceVariant, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp))
            } else courses.forEach { c ->
                val sel = courseId == c.id
                Card(
                    onClick = { haptic(HapticKind.Tick); courseId = c.id },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
                ) {
                    Text(
                        c.name,
                        fontSize = 14.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            ReviewField("作业标题", title, { title = it }, placeholder = "如 第 5 章习题")
            ReviewField("作业描述", desc, { desc = it }, singleLine = false, placeholder = "题目要求、提交方式…")
            ReviewField("截止时间", deadline, { deadline = it }, placeholder = "如 2026-10-25 23:59")
            ReviewField("总分", totalScore, { totalScore = it.filter { ch -> ch.isDigit() } }, placeholder = "100")
            ReviewField("知识点（逗号分隔）", kps, { kps = it }, placeholder = "如 泰勒公式, 洛必达法则")
            msg?.let {
                Text(it.second, color = if (it.first) KewuGreen else cs.error, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            Button(
                onClick = {
                    if (courseId.isBlank() || title.isBlank() || publishing) return@Button
                    publishing = true
                    msg = null
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
                            msg = true to "发布成功，已推送到学生任务页"
                            delay(600)
                            onPublished()
                        } else {
                            msg = false to "发布失败，请检查网络后重试"
                        }
                    }
                },
                enabled = courseId.isNotBlank() && title.isNotBlank() && !publishing,
                colors = ButtonDefaults.buttonColors(color = KewuBlue, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) { Text(if (publishing) "发布中…" else "发布作业", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
