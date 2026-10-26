package com.kewu.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import com.kewu.app.AttachmentInfo
import com.kewu.app.LocalAttachment
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.AiClient
import com.kewu.app.AssignmentInfo
import com.kewu.app.AssignmentStats
import com.kewu.app.Question
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
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import com.kewu.app.hapticClickable
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.ProgressIndicatorDefaults
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.overlay.OverlayDialog
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch

// ───────────────────────── 通用：Hub 顶栏 + 段标题 ─────────────────────────

/**
 * Hub 通用顶栏：coui [KewuTopBar]（SmallTopAppBar + coui 返回图标）。
 * 签名保留（课程 / 签到 / 教师端等十余处调用），只换实现；标题单行截断由 coui 顶栏负责。
 */
@Composable
internal fun HubTopBar(title: String, onBack: () -> Unit) {
    KewuTopBar(title = title, onBack = onBack)
}

/** Hub 段标题：coui `SmallTitle`（经 [KewuSectionTitle]）。签名保留，跨文件共用。 */
@Composable
internal fun HubSectionLabel(text: String) {
    KewuSectionTitle(text)
}

/**
 * Hub 段卡外壳：课悟液态玻璃 [KewuGlassCard]（玻璃件保留，参数沿用改造前：16dp 圆角 / blur 18 / tint 0.30），
 * 卡内 16dp，左右 12dp 与页面对齐；卡内子元素一律 coui。
 * [onClick] 非空时整卡可点（与改造前同为 hapticClickable，不给采样者叠 scale/graphicsLayer）。
 */
@Composable
private fun HubCard(
    backdrop: LayerBackdrop,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val m = modifier.fillMaxWidth().padding(horizontal = 12.dp)
    KewuGlassCard(
        backdrop = backdrop,
        shape = RoundedCornerShape(16.dp), blurRadius = 18.dp, tintAlpha = 0.30f,
        modifier = if (onClick != null) m.hapticClickable { onClick() } else m,
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

/** 横向进度条：coui [LinearProgressIndicator]（分数分布 / 掌握度 / 得分趋势共用）。 */
@Composable
private fun HubBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = fraction.coerceIn(0f, 1f),
        modifier = modifier,
        colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = color),
        height = 8.dp,
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
 * 配色走 [COUITheme] 语义色；卡片外壳保留课悟液态玻璃 ([KewuGlassCard])，卡内文字 / 按钮 / 进度条 /
 * 输入框 / 标签一律 coui 原生件（2026-10-23 全局 coui 化）。玻璃采样层与背景层平级，规避 coui 首帧 SIGSEGV。
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
    val cs = COUITheme.colorScheme
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
    var submitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }
    val snackbar = LocalKewuSnackbar.current

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
        // 背景层：coui 页面灰底，是玻璃卡的采样源（与玻璃卡平级 ✓）
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(pageBackdrop)
                .background(cs.surface),
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
                KewuLoading()
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
                                    kps.forEach { KewuTag(it, cs.tertiaryContainer, cs.onTertiaryContainer) }
                                }
                            }
                            if (a.description.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Text(a.description, color = cs.onSurfaceSecondary, fontSize = 13.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))

                    // 我的提交
                    HubSectionLabel("我的提交")
                    MySubmissionCard(backdrop = pageBackdrop, submission = mySub, onResubmit = { showSubmit = true })
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
                                // 2026-10-24：优先真实大模型（AiClient，作业要求+提交内容）；
                                // 失败回退后端规则引擎 aiPrereview
                                val sub = mySub
                                aiSuggestion = if (sub != null) {
                                    AiClient.gradeSubmission(a, sub) ?: KewuApi.aiPrereview(sub.id)
                                } else null
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
                    KewuEmpty("未找到该作业，请返回后重试")
                }
            }
        }
    }

    // coui OverlayDialog 需常驻组合、由 show 控制显隐（才有进出场动画），不再 if 包裹
    SubmitDialog(
        show = showSubmit,
        title = assignment?.title ?: "提交作业",
        questions = assignment?.questions ?: emptyList(),
        submitting = submitting,
        error = submitError,
        onSubmit = { content, answers, localAtts ->
            if (studentId.isBlank()) {
                submitError = "登录信息已失效，请重新登录后再提交"
                return@SubmitDialog
            }
            submitting = true
            submitError = null
            scope.launch {
                // 附件先逐个上传换成服务端 url；任一失败就停下并说明原因（不静默丢附件）
                val uploaded = mutableListOf<AttachmentInfo>()
                for (a in localAtts) {
                    val (info, err) = KewuApi.uploadAttachment(a)
                    if (info == null) {
                        submitError = "附件「${a.name}」上传失败：${err ?: "未知错误"}"
                        submitting = false
                        return@launch
                    }
                    uploaded += info
                }
                val err = KewuApi.submitAssignment(assignmentId, studentId, studentName, content, answers, uploaded)
                submitting = false
                if (err == null) {
                    showSubmit = false
                    snackbar.toast("提交成功")
                    onSubmitted(assignmentId)
                    reload()
                } else {
                    submitError = err
                }
            }
        },
        onDismiss = { showSubmit = false; submitError = null },
        onToast = { scope.launch { snackbar.toast(it) } },
    )
}

@Composable
private fun MetaRow(label: String, value: String) {
    val cs = COUITheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = cs.onSurfaceSecondary, fontSize = 13.sp, modifier = Modifier.width(56.dp))
        Text(value, color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MySubmissionCard(backdrop: LayerBackdrop, submission: SubmissionInfo?, onResubmit: () -> Unit) {
    val cs = COUITheme.colorScheme
    HubCard(backdrop) {
        if (submission == null) {
            Text("尚未提交", color = cs.onSurfaceSecondary, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            KewuPrimaryButton("去提交", onResubmit)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已提交", color = KewuGreen, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                submission.score?.let { Text("得分 ${it}", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(6.dp))
            MetaRow("版本", "v${submission.version} · ${submission.submittedAt.ifBlank { "—" }}")
            MetaRow("状态", submission.status.ifBlank { "—" })
            if (submission.attachments.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                AttachmentList(submission.attachments)
            }
            submission.comment?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text("老师评语：$it", color = cs.onSurfaceSecondary, fontSize = 13.sp)
            }
            submission.ai?.let { ai ->
                Spacer(Modifier.height(10.dp))
                Text("AI 预批阅建议分 ${ai.suggestedScore}（置信度 ${"%.0f".format(ai.confidence * 100)}%）",
                    color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(10.dp))
            KewuSecondaryButton("重新提交", onResubmit)
        }
    }
}

@Composable
private fun StatsCard(backdrop: LayerBackdrop, stats: AssignmentStats?) {
    val cs = COUITheme.colorScheme
    HubCard(backdrop) {
        if (stats == null) {
            EmptyHint("暂无批阅数据")
            return@HubCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniStat("已提交", "${stats.submittedCount}", cs.tertiaryContainer, cs.onTertiaryContainer)
            MiniStat("已批阅", "${stats.reviewedCount}", KewuGreen.copy(alpha = 0.14f), KewuGreen)
            MiniStat("待批阅", "${stats.pendingCount}", cs.secondaryContainer, cs.onSecondaryVariant)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniStat("均分", "%.1f".format(stats.avgScore), cs.secondaryContainer, cs.onSurface)
            MiniStat("最高", "%.1f".format(stats.maxScore), cs.secondaryContainer, cs.onSurface)
            MiniStat("最低", "%.1f".format(stats.minScore), cs.secondaryContainer, cs.onSurface)
        }
        Spacer(Modifier.height(12.dp))
        Text("分数分布", color = cs.onSurfaceSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        val maxCount = (stats.scoreDistribution.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
        stats.scoreDistribution.forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                Text(b.label, color = cs.onSurfaceSecondary, fontSize = 12.sp, modifier = Modifier.width(64.dp))
                HubBar(b.count.toFloat() / maxCount, KewuBlue, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text("${b.count}", color = cs.onSurface, fontSize = 12.sp, modifier = Modifier.width(28.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniStat("平均版本", "%.1f".format(stats.avgVersions), cs.secondaryContainer, cs.onSurface)
            MiniStat("AI 差距", "%.1f".format(stats.aiGapAvg), cs.secondaryContainer, cs.onSurface)
            MiniStat("高风险", "${stats.highRiskCount}", if (stats.highRiskCount > 0) cs.errorContainer else cs.secondaryContainer,
                if (stats.highRiskCount > 0) cs.onErrorContainer else cs.onSurface)
        }
    }
}

/**
 * 统计小块：大字数值 + 小字标签。
 * ★ 参数顺序修正为 (label, value)：旧签名是 (value, label) 而调用方一直按 (标签, 数值) 传，
 * 导致大字显示「已提交」、小字显示数字，主次颠倒。
 */
@Composable
private fun RowScope.MiniStat(label: String, value: String, background: Color, content: Color) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(background).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = content, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(label, color = content.copy(alpha = 0.75f), fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun AiPrereviewCard(
    backdrop: LayerBackdrop, suggestion: AiSuggestion?, hasSubmission: Boolean, loading: Boolean, onRun: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    HubCard(backdrop) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AI 预批阅", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (loading) InfiniteProgressIndicator(size = 18.dp)
        }
        Spacer(Modifier.height(8.dp))
        if (suggestion == null) {
            Text(if (hasSubmission) "尚未运行 AI 预批阅" else "请先提交作业后再运行", color = cs.onSurfaceSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            KewuPrimaryButton("运行 AI 预批阅", onRun, enabled = hasSubmission && !loading)
        } else {
            Text("建议分 ${suggestion.suggestedScore} · 置信度 ${"%.0f".format(suggestion.confidence * 100)}% · 风险 ${suggestion.riskLevel}",
                color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            if (suggestion.summary.isNotBlank()) Text(suggestion.summary, color = cs.onSurfaceSecondary, fontSize = 13.sp)
            if (suggestion.hitKnowledgePoints.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("命中知识点", color = cs.onSurfaceSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    suggestion.hitKnowledgePoints.forEach { KewuTag(it, KewuGreen.copy(alpha = 0.18f), KewuGreen) }
                }
            }
            if (suggestion.missedKnowledgePoints.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("未覆盖知识点", color = cs.onSurfaceSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    suggestion.missedKnowledgePoints.forEach { KewuTag(it, cs.errorContainer, cs.onErrorContainer) }
                }
            }
            if (suggestion.reasons.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                suggestion.reasons.forEach { r ->
                    Text("· ${r.item}：${r.detail}（${r.score}）", color = cs.onSurfaceSecondary, fontSize = 12.5.sp)
                }
            }
        }
    }
}

@Composable
private fun SimilarityCard(backdrop: LayerBackdrop, pairs: List<SimilarityPair>?) {
    val cs = COUITheme.colorScheme
    HubCard(backdrop) {
        if (pairs.isNullOrEmpty()) {
            EmptyHint("未检测到相似片段")
            return@HubCard
        }
        pairs.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("${p.studentA} ↔ ${p.studentB}", color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    p.sharedFragments.takeIf { it.isNotEmpty() }?.let {
                        Text(it.joinToString("；").take(60), color = cs.onSurfaceSecondary, fontSize = 12.sp, maxLines = 1)
                    }
                }
                val sim = (p.similarity * 100).toInt()
                // coui 色板无 tertiary/警示橙：中档相似度用主色，高档 error，低档品牌绿
                val simColor = when {
                    sim >= 70 -> cs.error
                    sim >= 40 -> cs.primary
                    else -> KewuGreen
                }
                Text("$sim%", color = simColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ReviewQueueCard(backdrop: LayerBackdrop, items: List<ReviewQueueItem>?) {
    val cs = COUITheme.colorScheme
    HubCard(backdrop) {
        if (items.isNullOrEmpty()) {
            EmptyHint("批阅队列为空")
            return@HubCard
        }
        items.forEach { it ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it.studentName, color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text("建议分 ${it.aiSuggestedScore}", color = cs.onSurfaceSecondary, fontSize = 13.sp)
                }
                Text("v${it.version} · 优先级 ${"%.2f".format(it.priority)}", color = cs.onSurfaceSecondary, fontSize = 12.sp)
                if (it.reasons.isNotEmpty()) {
                    Text(it.reasons.joinToString("；"), color = cs.onSurfaceVariantSummary, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun AnalyticsCard(backdrop: LayerBackdrop, analytics: Analytics) {
    val cs = COUITheme.colorScheme
    HubCard(backdrop) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniStat("作业数", "${analytics.assignmentCount}", cs.secondaryContainer, cs.onSurface)
            MiniStat("提交数", "${analytics.submissionCount}", cs.secondaryContainer, cs.onSurface)
        }
        if (analytics.weakPoints.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("薄弱知识点", color = cs.onSurfaceSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                analytics.weakPoints.forEach { KewuTag(it, cs.errorContainer, cs.onErrorContainer) }
            }
        }
        if (analytics.knowledgeMastery.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("知识点掌握度", color = cs.onSurfaceSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            analytics.knowledgeMastery.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(m.point, color = cs.onSurface, fontSize = 13.sp, modifier = Modifier.width(96.dp), maxLines = 1)
                    HubBar(m.avgScoreRate.toFloat(), KewuGreen, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text("${(m.avgScoreRate * 100).toInt()}%", color = cs.onSurface, fontSize = 12.sp, modifier = Modifier.width(36.dp))
                }
            }
        }
        if (analytics.scoreTrend.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("得分趋势", color = cs.onSurfaceSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            val maxScore = (analytics.scoreTrend.maxOfOrNull { it.avgScore } ?: 1.0).coerceAtLeast(1.0)
            analytics.scoreTrend.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(t.label, color = cs.onSurfaceSecondary, fontSize = 12.sp, modifier = Modifier.width(72.dp), maxLines = 1)
                    HubBar((t.avgScore / maxScore).toFloat(), KewuBlue, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text("%.1f".format(t.avgScore), color = cs.onSurface, fontSize = 12.sp, modifier = Modifier.width(40.dp))
                }
            }
        }
    }
}

@Composable
private fun TeacherPublishCard(backdrop: LayerBackdrop, courseId: String, publisher: String, onPublished: () -> Unit) {
    val cs = COUITheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var deadline by remember { mutableStateOf("") }
    var totalScore by remember { mutableStateOf("100") }
    var kps by remember { mutableStateOf("") }
    var questions by remember { mutableStateOf<List<Question>>(emptyList()) }
    var publishing by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 整卡点按展开/收起（与改造前同为 hapticClickable）；输入框自己消费点击，不会误触收起。
    // 展开时页面跟着卡片一起滑到底部（followGrowth，2026-10-26）
    val follow = remember { FollowWindow() }
    HubCard(
        backdrop,
        onClick = { expanded = !expanded; if (expanded) follow.arm() },
        modifier = Modifier.followGrowth { follow.active() },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("老师 · 发布新作业", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(if (expanded) "收起" else "展开", color = cs.onSurfaceSecondary, fontSize = 13.sp)
        }
        AnimatedVisibility(expanded) {
            Column {
                Spacer(Modifier.height(10.dp))
                TextField(title, { title = it }, Modifier.fillMaxWidth(), backgroundMode = TextFieldMode.Rectangle, label = "标题", singleLine = true)
                Spacer(Modifier.height(8.dp))
                TextField(desc, { desc = it }, Modifier.fillMaxWidth(), backgroundMode = TextFieldMode.Rectangle, label = "描述", minLines = 2)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(deadline, { deadline = it }, Modifier.weight(1f), backgroundMode = TextFieldMode.Rectangle, label = "截止", singleLine = true)
                    TextField(totalScore, { totalScore = it }, Modifier.weight(1f), backgroundMode = TextFieldMode.Rectangle, label = "总分", singleLine = true)
                }
                Spacer(Modifier.height(8.dp))
                TextField(kps, { kps = it }, Modifier.fillMaxWidth(), backgroundMode = TextFieldMode.Rectangle, label = "知识点（逗号分隔）", singleLine = true)
                Spacer(Modifier.height(10.dp))
                QuestionEditorCard(
                    questions = questions, onChange = { questions = it },
                    backdrop = backdrop, onToast = { msg = it },
                )
                msg?.let {
                    Text(it, color = if (it.contains("成功")) KewuGreen else cs.error, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                }
                KewuPrimaryButton(
                    text = if (publishing) "发布中…" else "发布作业",
                    onClick = {
                        validateQuestions(questions)?.let { msg = it; return@KewuPrimaryButton }
                        publishing = true; msg = null
                        scope.launch {
                            val info = KewuApi.publishAssignment(
                                courseId = courseId, title = title.ifBlank { "新作业" },
                                description = desc, deadline = deadline,
                                totalScore = totalScore.toIntOrNull() ?: 100,
                                knowledgePoints = kps.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                                publisher = publisher, questions = questions,
                            )
                            publishing = false
                            msg = if (info != null) "发布成功" else "发布失败，请稍后重试"
                            if (info != null) onPublished()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !publishing,
                )
            }
        }
    }
}

/** 卡内空态文字（卡外整块空态用 [KewuEmpty]）。 */
@Composable
private fun EmptyHint(text: String) {
    Text(
        text, color = COUITheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
}

/**
 * 提交作业弹窗：coui [OverlayDialog]（窗口内覆盖层）+ coui [TextField] + [DialogButtonBar]。
 *
 * v17 题目化作业：若 [questions] 非空，弹窗按题型渲染作答控件（选择/判断/填空/简答），答案走原生 `answers`；
 * 无题目时为纯文本答案框。2026-10-26：下方加「附件」区（图片 / 文件 / 录音，[AttachmentPicker]）；
 * 提交中按钮置灰显示「提交中…」，失败原因直接显示在弹窗里（旧版失败无任何反馈 = 用户说的「点了没反应」）。
 * 关闭动画播完才清空输入，避免退场时文字闪没。
 */
@Composable
private fun SubmitDialog(
    show: Boolean,
    title: String,
    questions: List<Question>,
    submitting: Boolean,
    error: String?,
    onSubmit: (content: String, answers: Map<String, String>, attachments: List<LocalAttachment>) -> Unit,
    onDismiss: () -> Unit,
    onToast: (String) -> Unit,
) {
    var content by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val answers = remember { mutableStateMapOf<String, String>() }
    val attachments = remember { mutableStateListOf<LocalAttachment>() }
    OverlayDialog(
        show = show,
        title = title,
        summary = if (questions.isNotEmpty()) "请按题型作答后提交" else "请输入答案后提交",
        onDismissRequest = { if (!submitting) onDismiss() },
        onDismissFinished = { content = ""; note = ""; answers.clear(); attachments.clear() },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (questions.isNotEmpty()) {
                QuestionRenderList(questions = questions, answers = answers)
                Spacer(Modifier.height(8.dp))
                HubSectionLabel("补充说明（可选）")
                TextField(
                    value = note, onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    backgroundMode = TextFieldMode.Rectangle, label = "有想对老师说的？", minLines = 2,
                )
            } else {
                TextField(
                    value = content, onValueChange = { content = it },
                    modifier = Modifier.fillMaxWidth(),
                    backgroundMode = TextFieldMode.Rectangle, label = "答案", minLines = 4,
                )
            }
            Spacer(Modifier.height(10.dp))
            HubSectionLabel("附件（可选）")
            AttachmentPicker(attachments = attachments, enabled = !submitting, onToast = onToast)
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = COUITheme.colorScheme.error, fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
            val hasAnswer = if (questions.isNotEmpty()) note.isNotBlank() || answers.values.any { it.isNotBlank() }
            else content.isNotBlank()
            val canSubmit = !submitting && (hasAnswer || attachments.isNotEmpty())
            DialogButtonBar(
                negative = DialogButtonBarAction("取消", enabled = !submitting) { haptic(HapticKind.Tick); onDismiss() },
                positive = DialogButtonBarAction(if (submitting) "提交中…" else "提交", enabled = canSubmit) {
                    haptic(HapticKind.Light)
                    val ans = if (questions.isNotEmpty()) answers.filterValues { it.isNotBlank() } else emptyMap()
                    // content 必填（文档 3.12 ②）：题目化作业拼成可读文本，纯文本作业即答案本身
                    val payload = if (questions.isNotEmpty()) {
                        buildReadableSubmitContent(questions, ans, note, attachments.isNotEmpty())
                    } else content.trim().ifBlank { if (attachments.isNotEmpty()) "（见附件）" else "" }
                    onSubmit(payload, ans, attachments.toList())
                },
            )
        }
    }
}
