package com.kewu.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.AssignmentInfo
import com.kewu.app.AttachmentInfo
import com.kewu.app.CourseRepo
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuBlue
import com.kewu.app.KewuGreen
import com.kewu.app.LocalAttachment
import com.kewu.app.QuestionType
import com.kewu.app.SubmissionInfo
import com.kewu.app.haptic
import com.kewu.app.todayCn
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Surface
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch
import kotlin.time.Clock

// ───────────────────────── 通用：Hub 顶栏 + 段标题（签到 / 课程详情等多处共用） ─────────────────────────

/**
 * Hub 通用顶栏：coui [KewuTopBar]（SmallTopAppBar + coui 返回图标）。
 * 签名保留（课程 / 签到 / 教师端等多处调用），标题单行截断由 coui 顶栏负责。
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

// ───────────────────────── 学生端：作业详情（2026-10-26 重构） ─────────────────────────

/**
 * 学生端作业详情 + 作答 + 提交（2026-10-26 按用户要求重构为「正式」学生页）。
 *
 * 只保留学生该看到的东西，全部来自后端：
 *   1. 作业信息卡（液态玻璃）：标题、状态、课程、发布教师、发布时间、截止时间（已截止标红）、总分、题量、知识点、作业要求；
 *   2. 我的提交（有提交时）：版本、提交时间、状态、得分 / 总分、客观题自动判分、老师评语、批阅信息、附件；被打回时醒目提示；
 *   3. 题目：一题一张 [QuestionAnswerCard]（coui 白卡 + 液态玻璃选项），直接在页面里作答，不再弹窗；
 *      已提交后展示后端 `questionResults` 的逐题判定、参考答案、解析；无题目的老作业为文字作答框；
 *   4. 补充说明 + 附件（图片 / 文件 / 录音）+ 底部提交按钮（有未作答题时先确认）。
 * 已移除（属于老师 / 内部调试，不该出现在学生端）：老师发布新作业、AI 预批阅、批阅统计、相似片段、
 * 智能批阅队列、知识点学情画像。
 *
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
    val snackbar = LocalKewuSnackbar.current

    var assignment by remember { mutableStateOf<AssignmentInfo?>(null) }
    var mySub by remember { mutableStateOf<SubmissionInfo?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }

    // 作答状态
    val answers = remember { mutableStateMapOf<String, String>() }
    var textAnswer by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val attachments = remember { mutableStateListOf<LocalAttachment>() }
    var editing by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }
    var confirmUnanswered by remember { mutableIntStateOf(0) }
    var showConfirm by remember { mutableStateOf(false) }
    var submitAfterConfirm by remember { mutableStateOf(false) }

    val studentId = KewuApi.username ?: ""
    val studentName = KewuApi.displayName("学生")

    /** 用某次提交回填作答（重新提交时在上一版基础上改）。 */
    fun prefillFrom(sub: SubmissionInfo?) {
        answers.clear()
        sub?.answers?.forEach { answers[it.questionId] = it.value }
        textAnswer = if (assignment?.questions.isNullOrEmpty()) sub?.content.orEmpty() else ""
        note = ""
        attachments.clear()
    }

    fun reload() {
        loading = true
        scope.launch {
            val a = KewuApi.getAssignment(assignmentId)
            assignment = a
            loadFailed = a == null
            val sub = if (studentId.isNotEmpty()) KewuApi.getMySubmissions(assignmentId, studentId) else null
            mySub = sub
            // 未提交 / 被打回 → 直接进入作答态；已提交待批阅 / 已批阅 → 只读展示结果
            editing = sub == null || sub.status == "RETURNED"
            prefillFrom(sub)
            loading = false
        }
    }
    LaunchedEffect(assignmentId) { reload() }

    fun doSubmit() {
        val a = assignment ?: return
        if (studentId.isBlank()) {
            submitError = "登录信息已失效，请重新登录后再提交"
            return
        }
        submitting = true
        submitError = null
        scope.launch {
            val ans = answers.filterValues { it.isNotBlank() }
            val content = if (a.questions.isNotEmpty()) {
                buildReadableSubmitContent(a.questions, ans, note, attachments.isNotEmpty())
            } else {
                buildString {
                    append(textAnswer.trim())
                    if (note.isNotBlank()) append("\n\n补充说明：").append(note.trim())
                }.ifBlank { if (attachments.isNotEmpty()) "（见附件）" else "" }
            }
            // 附件先逐个上传换成服务端 url；任一失败就停下并说明原因（不静默丢附件）
            val uploaded = mutableListOf<AttachmentInfo>()
            for (att in attachments.toList()) {
                val (info, err) = KewuApi.uploadAttachment(att)
                if (info == null) {
                    submitError = "附件「${att.name}」上传失败：${err ?: "未知错误"}"
                    submitting = false
                    return@launch
                }
                uploaded += info
            }
            val err = KewuApi.submitAssignment(assignmentId, studentId, studentName, content, ans, uploaded)
            submitting = false
            if (err == null) {
                haptic(HapticKind.Light)
                snackbar.toast("提交成功")
                onSubmitted(assignmentId)
                reload()
            } else {
                submitError = err
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 背景层：coui 页面灰底，是信息卡液态玻璃的采样源（与玻璃卡平级 ✓）
        Box(Modifier.matchParentSize().layerBackdrop(pageBackdrop).background(cs.surface))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
            HubTopBar(assignment?.title ?: "作业详情", onBack)
            Spacer(Modifier.height(4.dp))

            val a = assignment
            when {
                loading && a == null -> KewuLoading(text = "正在加载作业…")
                a == null -> {
                    KewuEmpty(if (loadFailed) "作业加载失败，请检查网络" else "未找到该作业")
                    KewuSecondaryButton("重新加载", { reload() }, Modifier.padding(horizontal = 16.dp))
                }
                else -> {
                    AssignmentInfoCard(a, mySub, pageBackdrop)
                    Spacer(Modifier.height(12.dp))

                    mySub?.let { sub ->
                        HubSectionLabel("我的提交")
                        MySubmissionCard(sub, a.totalScore)
                        Spacer(Modifier.height(12.dp))
                    }

                    val readOnly = !editing
                    if (a.questions.isNotEmpty()) {
                        val total = a.questions.sumOf { it.score }
                        HubSectionLabel("题目（共 ${a.questions.size} 题" + (if (total > 0) " · $total 分" else "") + "）")
                        val results = mySub?.questionResults.orEmpty().associateBy { it.questionId }
                        a.questions.forEachIndexed { i, q ->
                            QuestionAnswerCard(
                                q = q, index = i + 1,
                                value = answers[q.id].orEmpty(),
                                onValue = { answers[q.id] = it },
                                readOnly = readOnly,
                                result = if (readOnly) results[q.id] else null,
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                    } else {
                        HubSectionLabel("作答")
                        KewuCardGroup {
                            Column(Modifier.padding(16.dp)) {
                                if (readOnly) {
                                    Text(mySub?.content?.ifBlank { "（无文字内容）" } ?: "", color = cs.onSurface,
                                        fontSize = 14.5.sp, lineHeight = 21.sp)
                                } else {
                                    TextField(textAnswer, { textAnswer = it }, Modifier.fillMaxWidth(),
                                        backgroundMode = TextFieldMode.Rectangle, label = "在此输入你的答案…", minLines = 6)
                                }
                            }
                        }
                    }

                    if (editing) {
                        HubSectionLabel("补充说明与附件（选填）")
                        KewuCardGroup {
                            Column(Modifier.padding(16.dp)) {
                                TextField(note, { note = it }, Modifier.fillMaxWidth(),
                                    backgroundMode = TextFieldMode.Rectangle, label = "有想对老师说的？", minLines = 2,
                                    enabled = !submitting)
                                Spacer(Modifier.height(12.dp))
                                AttachmentPicker(attachments = attachments, enabled = !submitting,
                                    onToast = { scope.launch { snackbar.toast(it) } })
                            }
                        }
                        submitError?.let {
                            Text(it, color = cs.error, fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                        }
                        val unanswered = a.questions.count { answers[it.id].isNullOrBlank() }
                        val hasAnything = if (a.questions.isNotEmpty()) unanswered < a.questions.size || note.isNotBlank()
                        else textAnswer.isNotBlank()
                        KewuPrimaryButton(
                            text = when {
                                submitting -> "提交中…"
                                mySub != null -> "重新提交（第 ${(mySub?.version ?: 0) + 1} 版）"
                                else -> "提交作业"
                            },
                            onClick = {
                                if (submitting) return@KewuPrimaryButton
                                if (a.questions.isNotEmpty() && unanswered > 0) {
                                    confirmUnanswered = unanswered
                                    showConfirm = true
                                } else doSubmit()
                            },
                            enabled = !submitting && (hasAnything || attachments.isNotEmpty()),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        if (mySub != null) {
                            KewuSecondaryButton("取消修改", {
                                editing = false
                                submitError = null
                                prefillFrom(mySub)
                            }, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                        }
                    } else {
                        // 已提交待批阅 / 已批阅：后端允许多版本提交，给「修改并重新提交」入口
                        KewuSecondaryButton(
                            "修改并重新提交",
                            { editing = true; prefillFrom(mySub) },
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        // 有未作答题时二次确认；确认弹窗完全退场后再发请求（与「结束课程」同一套解耦写法）
        KewuConfirmDialog(
            show = showConfirm,
            title = "还有 $confirmUnanswered 题未作答",
            summary = "未作答的题目将按 0 分处理，确定现在提交吗？",
            confirmText = "仍然提交",
            cancelText = "继续作答",
            onConfirm = { submitAfterConfirm = true; showConfirm = false },
            onDismiss = { showConfirm = false },
            onDismissFinished = {
                if (submitAfterConfirm) {
                    submitAfterConfirm = false
                    doSubmit()
                }
            },
        )
    }
}

// ───────────────────────── 作业信息卡 ─────────────────────────

/** 作业信息（液态玻璃卡，卡内全 coui 文字 / 标签）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssignmentInfoCard(a: AssignmentInfo, sub: SubmissionInfo?, backdrop: LayerBackdrop) {
    val cs = COUITheme.colorScheme
    val courseName = CourseRepo.courses.value?.firstOrNull { it.id == a.courseId }?.name ?: a.courseId
    val overdue = a.deadline.isNotBlank() && isPast(a.deadline)
    KewuGlassCard(
        backdrop = backdrop, shape = RoundedCornerShape(20.dp), blurRadius = 22.dp, tintAlpha = 0.30f,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.title, color = cs.onSurface, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                StatusTagFor(sub)
            }
            Spacer(Modifier.height(10.dp))
            InfoRow("课程", courseName.ifBlank { "—" })
            InfoRow("发布教师", a.publisher.ifBlank { "—" })
            if (a.createdAt.isNotBlank()) InfoRow("发布时间", a.createdAt)
            InfoRow(
                "截止时间", a.deadline.ifBlank { "未设置" } + if (overdue) "（已截止）" else "",
                valueColor = if (overdue) cs.error else null,
            )
            InfoRow("总分", "${a.totalScore} 分")
            if (a.questions.isNotEmpty()) {
                val byType = a.questions.groupBy { it.type }
                InfoRow("题目", "${a.questions.size} 题 · " +
                    QuestionType.entries.filter { byType.containsKey(it) }
                        .joinToString("、") { "${it.label} ${byType[it]!!.size}" })
            }
            if (a.knowledgePoints.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    a.knowledgePoints.forEach { KewuTag(it, cs.tertiaryContainer, cs.onTertiaryContainer) }
                }
            }
            if (a.description.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text("作业要求", color = cs.onSurfaceSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(a.description, color = cs.onSurface, fontSize = 14.sp, lineHeight = 21.sp)
            }
        }
    }
}

@Composable
private fun StatusTagFor(sub: SubmissionInfo?) {
    val cs = COUITheme.colorScheme
    when (sub?.status) {
        null -> KewuTag("未提交", cs.secondaryContainer, cs.onSurfaceSecondary)
        "REVIEWED" -> KewuTag("已批阅", KewuGreen.copy(alpha = 0.18f), KewuGreen)
        "RETURNED" -> KewuTag("已打回", cs.error.copy(alpha = 0.14f), cs.error)
        else -> KewuTag("待批阅", KewuBlue.copy(alpha = 0.14f), KewuBlue)
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color? = null) {
    val cs = COUITheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = cs.onSurfaceSecondary, fontSize = 13.5.sp, modifier = Modifier.width(72.dp))
        Text(value, color = valueColor ?: cs.onSurface, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
    }
}

// ───────────────────────── 我的提交 ─────────────────────────

/** 我的提交（coui 白卡）：状态、得分、评语、批阅信息、附件；被打回时顶部醒目提示。 */
@Composable
private fun MySubmissionCard(sub: SubmissionInfo, totalScore: Int) {
    val cs = COUITheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        cornerRadius = 18.dp, colors = kewuCardColors(),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (sub.status == "RETURNED") {
                Surface(shape = RoundedCornerShape(12.dp), color = cs.error.copy(alpha = 0.10f),
                    modifier = Modifier.fillMaxWidth()) {
                    Text("老师已将本次作业打回，请根据评语修改后重新提交。（助教小悟也给你发了修改建议）",
                        color = cs.error, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(12.dp))
                }
                Spacer(Modifier.height(12.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("第 ${sub.version} 版", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("提交于 ${sub.submittedAt.ifBlank { "—" }}", color = cs.onSurfaceSecondary, fontSize = 12.5.sp)
                }
                val score = sub.score
                if (score != null && sub.status == "REVIEWED") {
                    Text(fmtScoreNum(score), color = KewuGreen, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(" / $totalScore", color = cs.onSurfaceSecondary, fontSize = 14.sp)
                } else {
                    StatusTagFor(sub)
                }
            }
            val objective = sub.ai?.takeIf { it.objectiveFullScore > 0 }
            if (objective != null) {
                Spacer(Modifier.height(8.dp))
                Text("客观题自动判分：${fmtScoreNum(objective.objectiveScore)} / ${fmtScoreNum(objective.objectiveFullScore)}",
                    color = cs.onSurfaceSecondary, fontSize = 13.sp)
            }
            sub.comment?.takeIf { it.isNotBlank() }?.let { c ->
                Spacer(Modifier.height(10.dp))
                Text("老师评语", color = cs.onSurfaceSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(c, color = cs.onSurface, fontSize = 14.sp, lineHeight = 21.sp)
            }
            val reviewMeta = listOfNotNull(
                sub.reviewer?.takeIf { it.isNotBlank() }?.let { "批阅：$it" },
                sub.reviewedAt?.takeIf { it.isNotBlank() },
            )
            if (reviewMeta.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(reviewMeta.joinToString(" · "), color = cs.onSurfaceVariantSummary, fontSize = 12.sp)
            }
            if (sub.attachments.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                AttachmentList(sub.attachments)
            }
            if (sub.history.size > 1) {
                Spacer(Modifier.height(6.dp))
                Text("共提交 ${sub.history.size} 个版本，以上为最新版本", color = cs.onSurfaceVariantSummary, fontSize = 12.sp)
            }
        }
    }
}

private fun fmtScoreNum(v: Double): String =
    if (v % 1.0 == 0.0) v.toInt().toString() else ((v * 10).toInt() / 10.0).toString()

/** `yyyy-MM-dd HH:mm`（东八区）是否已过：同格式字符串可直接按字典序比较。 */
private fun isPast(deadline: String): Boolean {
    val (d, t) = parseDeadline(deadline) ?: return false
    val ms = Clock.System.now().toEpochMilliseconds()
    val minuteOfDay = ((ms + 8 * 3_600_000L) % 86_400_000L) / 60_000L
    val now = "${todayCn(ms)} ${two(minuteOfDay / 60)}:${two(minuteOfDay % 60)}"
    return formatDeadline(d, t) < now
}

private fun two(v: Long) = if (v < 10) "0$v" else "$v"
