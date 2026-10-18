package com.kewu.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.AssignmentInfo
import com.kewu.app.AssignmentStats
import com.kewu.app.AiSuggestion
import com.kewu.app.Analytics
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
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import kotlinx.coroutines.launch

// ───────────────────────── 通用：Hub 顶栏 + 段标题 ─────────────────────────

/**
 * Hub 通用顶栏：左侧返回胶囊（液态玻璃同款的轻触反馈）+ 标题。
 * 返回键走 [HapticKind.Light] 轻确认；标题单行截断。
 */
@Composable
internal fun HubTopBar(title: String, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(cs.surfaceVariant)
                .hapticClickable { haptic(HapticKind.Light); onBack() },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(20.dp)) {
                val w = size.width
                val p = Path().apply {
                    moveTo(w * 0.62f, w * 0.18f)
                    lineTo(w * 0.30f, w * 0.5f)
                    lineTo(w * 0.62f, w * 0.82f)
                }
                drawPath(p, cs.onSurfaceVariant, style = Stroke(width = w * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            title, color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun HubSectionLabel(text: String) {
    Text(
        text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
        fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

// ───────────────────────── 作业详情 Hub ─────────────────────────

/**
 * 作业中心 · 作业详情页。
 *
 * 接入的 API（全部来自 [KewuApi]）：
 * - `getAssignment` 作业元信息
 * - `getMySubmissions` 我的提交（含版本轨迹 / AI 预批阅结果）
 * - `getAssignmentStats` 批阅统计（提交/已批/待批、均分、分数分布…）
 * - `aiPrereview` AI 预批阅（点按钮触发，针对我的提交）
 * - `getSimilarity` 相似片段 / 查重
 * - `getReviewQueue` 智能批阅队列
 * - `getAnalytics` 知识点学情画像（按 courseId）
 * - `submitAssignment` / `reviewSubmission` 提交与批阅（老师入口）
 * - `publishAssignment` 老师发布新作业（底部折叠入口）
 *
 * 配色走 [MaterialTheme] 语义色；主卡用 coui 液态玻璃 ([KewuGlassCard])，列表项用 squircle
 * （与课堂/任务页同一套视觉语言）。玻璃采样层与背景层平级，规避 coui 首帧 SIGSEGV。
 *
 * @param assignmentId 作业 id
 * @param onBack 返回
 * @param onSubmitted 成功提交后回调（任务页用其把作业标为已交）
 */
@Composable
fun AssignmentDetailScreen(
    assignmentId: String,
    onBack: () -> Unit,
    onSubmitted: (String) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val pageBackdrop = rememberGlassBackdrop()
    val scrollState = rememberScrollState()

    var assignment by remember { mutableStateOf<AssignmentInfo?>(null) }
    var mySub by remember { mutableStateOf<SubmissionInfo?>(null) }
    var stats by remember { mutableStateOf<AssignmentStats?>(null) }
    var similarity by remember { mutableStateOf<List<SimilarityPair>?>(null) }
    var reviewQueue by remember { mutableStateOf<List<ReviewQueueItem>?>(null) }
    var analytics by remember { mutableStateOf<Analytics?>(null) }
    var aiSuggestion by remember { mutableStateOf<AiSuggestion?>(null) }
    var aiLoading by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var showSubmit by remember { mutableStateOf(false) }

    val studentId = KewuApi.username ?: ""
    val studentName = KewuApi.currentUser?.user?.nickname ?: "学生"

    fun reload() {
        loading = true
        scope.launch {
            assignment = KewuApi.getAssignment(assignmentId)
            mySub = if (studentId.isNotEmpty()) KewuApi.getMySubmissions(assignmentId, studentId) else null
            stats = KewuApi.getAssignmentStats(assignmentId)
            similarity = KewuApi.getSimilarity(assignmentId)
            reviewQueue = KewuApi.getReviewQueue(assignmentId)
            assignment?.courseId?.let { cid -> analytics = KewuApi.getAnalytics(cid) }
            loading = false
        }
    }
    LaunchedEffect(assignmentId) { reload() }

    Box(Modifier.fillMaxSize()) {
        // 背景层：M3 语义色，是玻璃卡的采样源（与玻璃卡平级 ✓）
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(pageBackdrop)
                .background(cs.background),
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 96.dp),
        ) {
            HubTopBar(assignment?.title ?: "作业详情", onBack)
            Spacer(Modifier.height(4.dp))

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = cs.primary, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                }
            } else {
                // 头部玻璃卡：标题 / 课程 / 截止 / 总分 / 知识点
                assignment?.let { a ->
                    KewuGlassCard(
                        backdrop = pageBackdrop, shape = RoundedCornerShape(20.dp),
                        blurRadius = 22.dp, tintAlpha = 0.28f,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Text(a.title, color = cs.onSurface, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            MetaRow("课程", a.courseId)
                            MetaRow("截止", a.deadline.ifBlank { "未设置" })
                            MetaRow("总分", "${a.totalScore}")
                            MetaRow("发布人", a.publisher.ifBlank { "—" })
                            a.knowledgePoints.takeIf { it.isNotEmpty() }?.let { kps ->
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    kps.forEach { StatusTag(it, cs.primaryContainer, cs.onPrimaryContainer) }
                                }
                            }
                            if (a.description.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Text(a.description, color = cs.onSurfaceVariant, fontSize = 13.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))

                    // 我的提交
                    HubSectionLabel("我的提交")
                    MySubmissionCard(
                        backdrop = pageBackdrop, submission = mySub,
                        onResubmit = { showSubmit = true },
                    )
                    Spacer(Modifier.height(14.dp))

                    // 批阅统计
                    HubSectionLabel("批阅统计")
                    StatsCard(backdrop = pageBackdrop, stats = stats)
                    Spacer(Modifier.height(14.dp))

                    // AI 预批阅
                    HubSectionLabel("AI 预批阅")
                    AiPrereviewCard(
                        backdrop = pageBackdrop, suggestion = aiSuggestion,
                        hasSubmission = mySub != null, loading = aiLoading,
                        onRun = {
                            aiLoading = true
                            scope.launch {
                                aiSuggestion = mySub?.id?.let { KewuApi.aiPrereview(it) }
                                aiLoading = false
                            }
                        },
                    )
                    Spacer(Modifier.height(14.dp))

                    // 相似片段 / 查重
                    HubSectionLabel("相似片段检测")
                    SimilarityCard(backdrop = pageBackdrop, pairs = similarity)
                    Spacer(Modifier.height(14.dp))

                    // 智能批阅队列
                    HubSectionLabel("智能批阅队列")
                    ReviewQueueCard(backdrop = pageBackdrop, items = reviewQueue)
                    Spacer(Modifier.height(14.dp))

                    // 知识点学情画像
                    analytics?.let { an ->
                        HubSectionLabel("知识点学情画像")
                        AnalyticsCard(backdrop = pageBackdrop, analytics = an)
                        Spacer(Modifier.height(14.dp))
                    }

                    // 老师：发布新作业（折叠入口，接入 publishAssignment）
                    TeacherPublishCard(
                        backdrop = pageBackdrop, courseId = a.courseId,
                        publisher = studentName, onPublished = { reload() },
                    )
                    Spacer(Modifier.height(24.dp))
                } ?: run {
                    EmptyHint("未找到该作业，请返回后重试")
                }
            }
        }
    }

    if (showSubmit) {
        SubmitDialog(
            title = assignment?.title ?: "提交作业",
            onSubmit = { content ->
                scope.launch {
                    val err = KewuApi.submitAssignment(assignmentId, studentId, studentName, content)
                    showSubmit = false
                    if (err == null) {
                        onSubmitted(assignmentId)
                        reload()
                    }
                }
            },
            onDismiss = { showSubmit = false },
        )
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.width(56.dp))
        Text(value, color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MySubmissionCard(backdrop: LayerBackdrop, submission: SubmissionInfo?, onResubmit: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    KewuGlassCard(
        backdrop = backdrop,
        shape = RoundedCornerShape(16.dp), blurRadius = 18.dp, tintAlpha = 0.30f,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (submission == null) {
                Text("尚未提交", color = cs.onSurfaceVariant, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Button(onClick = onResubmit, colors = ButtonDefaults.buttonColors(containerColor = KewuBlue)) {
                    Text("去提交")
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已提交", color = KewuGreen, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    submission.score?.let { Text("得分 ${it}", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.height(6.dp))
                MetaRow("版本", "v${submission.version} · ${submission.submittedAt.ifBlank { "—" }}")
                MetaRow("状态", submission.status.ifBlank { "—" })
                submission.comment?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("老师评语：$it", color = cs.onSurfaceVariant, fontSize = 13.sp)
                }
                submission.ai?.let { ai ->
                    Spacer(Modifier.height(10.dp))
                    Text("AI 预批阅建议分 ${ai.suggestedScore}（置信度 ${"%.0f".format(ai.confidence * 100)}%）",
                        color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = onResubmit, colors = ButtonDefaults.buttonColors(containerColor = cs.secondaryContainer, contentColor = cs.onSecondaryContainer)) {
                    Text("重新提交")
                }
            }
        }
    }
}

@Composable
private fun StatsCard(backdrop: LayerBackdrop, stats: AssignmentStats?) {
    val cs = MaterialTheme.colorScheme
    KewuGlassCard(
        backdrop = backdrop,
        shape = RoundedCornerShape(16.dp), blurRadius = 18.dp, tintAlpha = 0.30f,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (stats == null) {
                EmptyHint("暂无批阅数据")
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("已提交", "${stats.submittedCount}", cs.primaryContainer, cs.onPrimaryContainer)
                MiniStat("已批阅", "${stats.reviewedCount}", cs.tertiaryContainer, cs.onTertiaryContainer)
                MiniStat("待批阅", "${stats.pendingCount}", cs.secondaryContainer, cs.onSecondaryContainer)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("均分", "%.1f".format(stats.avgScore), cs.surfaceVariant, cs.onSurface)
                MiniStat("最高", "%.1f".format(stats.maxScore), cs.surfaceVariant, cs.onSurface)
                MiniStat("最低", "%.1f".format(stats.minScore), cs.surfaceVariant, cs.onSurface)
            }
            Spacer(Modifier.height(12.dp))
            Text("分数分布", color = cs.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            val maxCount = (stats.scoreDistribution.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
            stats.scoreDistribution.forEach { b ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(b.label, color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.width(64.dp))
                    Box(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(cs.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth(b.count.toFloat() / maxCount).height(12.dp).clip(RoundedCornerShape(6.dp)).background(KewuBlue))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("${b.count}", color = cs.onSurface, fontSize = 12.sp, modifier = Modifier.width(28.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("平均版本", "%.1f".format(stats.avgVersions), cs.surfaceVariant, cs.onSurface)
                MiniStat("AI 差距", "%.1f".format(stats.aiGapAvg), cs.surfaceVariant, cs.onSurface)
                MiniStat("高风险", "${stats.highRiskCount}", if (stats.highRiskCount > 0) cs.errorContainer else cs.surfaceVariant,
                    if (stats.highRiskCount > 0) cs.onErrorContainer else cs.onSurface)
            }
        }
    }
}

@Composable
private fun RowScope.MiniStat(value: String, label: String, background: Color, content: Color) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).background(background).padding(10.dp).weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = content, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(label, color = content.copy(alpha = 0.75f), fontSize = 11.sp)
    }
}

@Composable
private fun AiPrereviewCard(
    backdrop: LayerBackdrop, suggestion: AiSuggestion?, hasSubmission: Boolean, loading: Boolean, onRun: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    KewuGlassCard(
        backdrop = backdrop,
        shape = RoundedCornerShape(16.dp), blurRadius = 18.dp, tintAlpha = 0.30f,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("AI 预批阅", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (loading) CircularProgressIndicator(strokeWidth = 2.5.dp, modifier = Modifier.size(18.dp), color = cs.primary)
            }
            Spacer(Modifier.height(8.dp))
            if (suggestion == null) {
                Text(if (hasSubmission) "尚未运行 AI 预批阅" else "请先提交作业后再运行", color = cs.onSurfaceVariant, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onRun, enabled = hasSubmission && !loading,
                    colors = ButtonDefaults.buttonColors(containerColor = KewuBlue),
                ) { Text("运行 AI 预批阅") }
            } else {
                Text("建议分 ${suggestion.suggestedScore} · 置信度 ${"%.0f".format(suggestion.confidence * 100)}% · 风险 ${suggestion.riskLevel}",
                    color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                if (suggestion.summary.isNotBlank()) Text(suggestion.summary, color = cs.onSurfaceVariant, fontSize = 13.sp)
                if (suggestion.hitKnowledgePoints.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("命中知识点", color = cs.onSurfaceVariant, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        suggestion.hitKnowledgePoints.forEach { StatusTag(it, KewuGreen.copy(alpha = 0.18f), KewuGreen) }
                    }
                }
                if (suggestion.missedKnowledgePoints.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("未覆盖知识点", color = cs.onSurfaceVariant, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        suggestion.missedKnowledgePoints.forEach { StatusTag(it, cs.errorContainer, cs.onErrorContainer) }
                    }
                }
                if (suggestion.reasons.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    suggestion.reasons.forEach { r ->
                        Text("· ${r.item}：${r.detail}（${r.score}）", color = cs.onSurfaceVariant, fontSize = 12.5.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SimilarityCard(backdrop: LayerBackdrop, pairs: List<SimilarityPair>?) {
    val cs = MaterialTheme.colorScheme
    KewuGlassCard(
        backdrop = backdrop,
        shape = RoundedCornerShape(16.dp), blurRadius = 18.dp, tintAlpha = 0.30f,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (pairs.isNullOrEmpty()) {
                EmptyHint("未检测到相似片段")
                return@Column
            }
            pairs.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${p.studentA} ↔ ${p.studentB}", color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        p.sharedFragments.takeIf { it.isNotEmpty() }?.let {
                            Text(it.joinToString("；").take(60), color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                    val sim = (p.similarity * 100).toInt()
                    val simColor = when {
                        sim >= 70 -> cs.error
                        sim >= 40 -> cs.tertiary
                        else -> KewuGreen
                    }
                    Text("$sim%", color = simColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ReviewQueueCard(backdrop: LayerBackdrop, items: List<ReviewQueueItem>?) {
    val cs = MaterialTheme.colorScheme
    KewuGlassCard(
        backdrop = backdrop,
        shape = RoundedCornerShape(16.dp), blurRadius = 18.dp, tintAlpha = 0.30f,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (items.isNullOrEmpty()) {
                EmptyHint("批阅队列为空")
                return@Column
            }
            items.forEach { it ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(it.studentName, color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text("建议分 ${it.aiSuggestedScore}", color = cs.onSurfaceVariant, fontSize = 13.sp)
                    }
                    Text("v${it.version} · 优先级 ${"%.2f".format(it.priority)}", color = cs.onSurfaceVariant, fontSize = 12.sp)
                    if (it.reasons.isNotEmpty()) {
                        Text(it.reasons.joinToString("；"), color = cs.onSurfaceVariant.copy(alpha = 0.85f), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalyticsCard(backdrop: LayerBackdrop, analytics: Analytics) {
    val cs = MaterialTheme.colorScheme
    KewuGlassCard(
        backdrop = backdrop,
        shape = RoundedCornerShape(16.dp), blurRadius = 18.dp, tintAlpha = 0.30f,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("作业数", "${analytics.assignmentCount}", cs.surfaceVariant, cs.onSurface)
                MiniStat("提交数", "${analytics.submissionCount}", cs.surfaceVariant, cs.onSurface)
            }
            if (analytics.weakPoints.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("薄弱知识点", color = cs.onSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    analytics.weakPoints.forEach { StatusTag(it, cs.errorContainer, cs.onErrorContainer) }
                }
            }
            if (analytics.knowledgeMastery.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("知识点掌握度", color = cs.onSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                analytics.knowledgeMastery.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Text(m.point, color = cs.onSurface, fontSize = 13.sp, modifier = Modifier.width(96.dp))
                        Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(cs.surfaceVariant)) {
                            Box(Modifier.fillMaxWidth(m.avgScoreRate.toFloat().coerceIn(0f, 1f)).height(10.dp).clip(RoundedCornerShape(5.dp)).background(KewuGreen))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("${(m.avgScoreRate * 100).toInt()}%", color = cs.onSurface, fontSize = 12.sp, modifier = Modifier.width(36.dp))
                    }
                }
            }
            if (analytics.scoreTrend.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("得分趋势", color = cs.onSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                val maxScore = (analytics.scoreTrend.maxOfOrNull { it.avgScore } ?: 1.0).coerceAtLeast(1.0)
                analytics.scoreTrend.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Text(t.label, color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.width(72.dp))
                        Box(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(cs.surfaceVariant)) {
                            Box(Modifier.fillMaxWidth((t.avgScore / maxScore).toFloat().coerceIn(0f, 1f)).height(12.dp).clip(RoundedCornerShape(6.dp)).background(KewuBlue))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("%.1f".format(t.avgScore), color = cs.onSurface, fontSize = 12.sp, modifier = Modifier.width(40.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TeacherPublishCard(backdrop: LayerBackdrop, courseId: String, publisher: String, onPublished: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var deadline by remember { mutableStateOf("") }
    var totalScore by remember { mutableStateOf("100") }
    var kps by remember { mutableStateOf("") }
    var publishing by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    KewuGlassCard(
        backdrop = backdrop,
        shape = RoundedCornerShape(16.dp), blurRadius = 18.dp, tintAlpha = 0.30f,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .hapticClickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("老师 · 发布新作业", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (expanded) "收起" else "展开", color = cs.onSurfaceVariant, fontSize = 13.sp)
            }
            AnimatedVisibility(expanded) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("标题") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(desc, { desc = it }, Modifier.fillMaxWidth(), label = { Text("描述") }, minLines = 2)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(deadline, { deadline = it }, Modifier.weight(1f), label = { Text("截止") }, singleLine = true)
                        OutlinedTextField(totalScore, { totalScore = it }, Modifier.weight(1f), label = { Text("总分") }, singleLine = true)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(kps, { kps = it }, Modifier.fillMaxWidth(), label = { Text("知识点（逗号分隔）") }, singleLine = true)
                    Spacer(Modifier.height(10.dp))
                    msg?.let { Text(it, color = if (it.contains("成功")) KewuGreen else cs.error, fontSize = 13.sp) }
                    Button(
                        onClick = {
                            publishing = true; msg = null
                            scope.launch {
                                val info = KewuApi.publishAssignment(
                                    courseId = courseId, title = title.ifBlank { "新作业" },
                                    description = desc, deadline = deadline,
                                    totalScore = totalScore.toIntOrNull() ?: 100,
                                    knowledgePoints = kps.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                                    publisher = publisher,
                                )
                                publishing = false
                                msg = if (info != null) "发布成功" else "发布失败，请稍后重试"
                                if (info != null) onPublished()
                            }
                        },
                        enabled = !publishing, colors = ButtonDefaults.buttonColors(containerColor = KewuBlue),
                    ) { Text(if (publishing) "发布中…" else "发布作业") }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    val cs = MaterialTheme.colorScheme
    Text(text, color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
}

@Composable
private fun SubmitDialog(title: String, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var content by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("请输入答案后提交（接入 submitAssignment）")
                OutlinedTextField(value = content, onValueChange = { content = it }, Modifier.fillMaxWidth(), minLines = 4, label = { Text("答案") })
            }
        },
        confirmButton = {
            Button(enabled = content.isNotBlank(), onClick = { onSubmit(content.trim()) }) { Text("提交") }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("取消") }
        },
    )
}
