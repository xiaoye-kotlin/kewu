package com.kewu.app.ui

import com.kewu.app.BackHandler
import com.kewu.app.ClassRecordingInfo
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuGreen
import com.kewu.app.SessionRecordingInfo
import com.kewu.app.haptic
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.ChevronForward
import io.github.suqi8.coui.kmp.icon.extended.ExpandLess
import io.github.suqi8.coui.kmp.icon.extended.ExpandMore
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch

/**
 * ★ E1（2026-11-05）：教师端课堂纪要 —— 与学生端完全不同的重构页。
 *
 * 学生端 `ClassNoteDetailScreen` 是「学习视角」（纪要全文 / 复习 / 问小悟）；
 * 本页是「教学视角」，核心回答老师的问题：**这堂课学生们听得怎么样**。
 *
 * 内容：
 * 1. 转写状态横幅（复用 [StatusBanner]）；
 * 2. **全班听课状态总览**（液态玻璃卡：认真 / 疑似开小差 / 疑似聊天 / 未分析 四格计数）——
 *    数据来自 `GET /api/class-recordings/{sid}/recordings`（教师 token）的每端
 *    `focusVerdict / focusEvidence / focusConfidence`（服务端用每位学生自己录音的转写判断，
 *    录音转写内容已告诉 AI → 谁开小差、谁讲小话都有依据）；
 * 3. **逐学生听课状态卡**（[StudentRecordingCard]）：姓名 + 结论标签 + 判断依据（含置信度）+
 *    本人录音回放；不认真的排最前，老师一眼看到该关注谁；
 * 4. AI 课堂纪要全文 + 完整转写（折叠）；
 * 5. **AI 课堂复盘**入口（[TeachingReviewScreen]，注入本节课专注结论）。
 *
 * 进入路径：教师端首页「AI 课堂纪要」进度卡 / 通知点击 → ConsoleDrill.CLASS_NOTE（本页）。
 */
@Composable
fun TeacherClassNoteScreen(courseId: String, date: String, onBack: () -> Unit) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    val pageBackdrop = rememberGlassBackdrop()
    val scrollState = rememberScrollState()

    var recordings by remember { mutableStateOf<List<ClassRecordingInfo>?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var courseName by remember { mutableStateOf("") }
    // 每端录音 + 专注结论（教师 token）
    var recs by remember { mutableStateOf<List<SessionRecordingInfo>?>(null) }
    var recsFailed by remember { mutableStateOf(false) }
    var showReview by remember { mutableStateOf(false) }
    var transcriptOpen by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            loaded = false
            loadFailed = false
            val r = KewuApi.getClassRecordings(courseId)
            if (r == null) loadFailed = true else recordings = r
            courseName = KewuApi.getCourse(courseId)?.name ?: ""
            loaded = true
        }
    }
    LaunchedEffect(courseId) { reload() }

    // 当天全部课堂会话（最新在前）
    val daySessions = remember(recordings, date) {
        recordings.orEmpty().filter { it.date == date }.sortedByDescending { it.decidedAt }
    }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val rec = daySessions.firstOrNull { it.sessionId == selectedId } ?: daySessions.firstOrNull()

    // 换节次 → 重拉全班录音与专注结论
    LaunchedEffect(rec?.sessionId) {
        val r = rec ?: return@LaunchedEffect
        recs = null
        recsFailed = false
        KewuApi.getSessionRecordings(r.sessionId)
            .onSuccess { recs = it }
            .onFailure { recsFailed = true }
    }

    Box(Modifier.fillMaxSize()) {
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
                .padding(bottom = KewuNavClearance),
        ) {
            HubTopBar(if (courseName.isBlank()) "$date 课堂纪要" else "$courseName · $date", onBack = onBack)
            Spacer(Modifier.height(4.dp))

            if (!loaded) {
                KewuLoading()
            } else if (loadFailed) {
                KewuEmpty("课堂纪要加载失败，请检查网络后重试")
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    cornerRadius = 16.dp,
                    insideMargin = PaddingValues(14.dp),
                    colors = kewuCardColors(),
                    onClick = { haptic(HapticKind.Tick); reload() },
                ) { Text("点击重试", color = cs.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            } else if (rec == null) {
                KewuEmpty("该日暂无课堂纪要")
            } else {
                // 一天多节：横向小卡切换
                if (daySessions.size > 1) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        daySessions.forEach { s ->
                            val sel = s.sessionId == rec.sessionId
                            Card(
                                modifier = Modifier.weight(1f),
                                cornerRadius = 12.dp,
                                insideMargin = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                colors = kewuCardColors(),
                                onClick = { haptic(HapticKind.Tick); selectedId = s.sessionId },
                            ) {
                                Text(
                                    s.decidedAt.takeLast(8).replace(":", "·"),
                                    color = if (sel) cs.primary else cs.onSurfaceSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // 转写状态横幅
                StatusBanner(rec)

                // ── ★ 全班听课状态（E1 核心，与学生端的差异点） ──
                Spacer(Modifier.height(14.dp))
                HubSectionLabel("全班听课状态")
                val list = recs
                when {
                    list == null -> Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        cornerRadius = 16.dp,
                        insideMargin = PaddingValues(16.dp),
                        colors = kewuCardColors(),
                    ) { KewuLoading(text = "正在加载每位同学的录音分析…") }
                    recsFailed -> Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        cornerRadius = 16.dp,
                        insideMargin = PaddingValues(16.dp),
                        colors = kewuCardColors(),
                    ) { Text("听课状态加载失败，下拉重试或检查网络", color = cs.error, fontSize = 13.sp) }
                    list.isEmpty() -> Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        cornerRadius = 16.dp,
                        insideMargin = PaddingValues(16.dp),
                        colors = kewuCardColors(),
                    ) { Text("这节课还没有学生上传录音，暂无听课状态", color = cs.onSurfaceSecondary, fontSize = 13.sp) }
                    else -> Column(Modifier.padding(horizontal = 12.dp)) {
                        val analyzed = list.filter { it.focusVerdict != null }
                        // 专注概览（液态玻璃四格）
                        KewuGlassCard(
                            backdrop = pageBackdrop,
                            shape = RoundedCornerShape(20.dp),
                            blurRadius = 20.dp,
                            tintAlpha = 0.30f,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(16.dp)) {
                                FocusCell("认真", analyzed.count { it.focusVerdict == "认真" }, KewuGreen, Modifier.weight(1f))
                                FocusCell("开小差", analyzed.count { it.focusVerdict == "疑似开小差" }, cs.error, Modifier.weight(1f))
                                FocusCell("聊天", analyzed.count { it.focusVerdict == "疑似聊天" }, cs.error, Modifier.weight(1f))
                                FocusCell("未分析", list.size - analyzed.size, cs.onSurfaceSecondary, Modifier.weight(1f))
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "录音转写已交给 AI 逐人分析：谁开小差、谁讲了小话都有依据，点卡片听本人录音回放。",
                            color = cs.onSurfaceSecondary, fontSize = 12.sp, lineHeight = 18.sp,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        // 不认真的排前面，老师先看到要关注的人
                        val order = listOf("疑似聊天", "疑似开小差", "认真", null)
                        val sorted = list.sortedWith(
                            compareBy<SessionRecordingInfo> { it.role.equals("teacher", true) }
                                .thenBy { order.indexOf(it.focusVerdict).let { i -> if (i < 0) 3 else i } },
                        )
                        sorted.forEachIndexed { i, r -> StudentRecordingCard(r, i) }
                        Spacer(Modifier.height(4.dp))
                    }
                }

                // AI 课堂纪要全文
                if (rec.summary.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    HubSectionLabel("AI 课堂纪要")
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        cornerRadius = 16.dp,
                        insideMargin = PaddingValues(16.dp),
                        colors = kewuCardColors(),
                    ) {
                        MarkdownText(rec.summary, color = cs.onSurface, fontSize = 14.sp, lineHeight = 21.sp)
                    }
                }

                // 完整转写（默认折叠）
                if (rec.transcript.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    HubSectionLabel("完整转写")
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        cornerRadius = 16.dp,
                        insideMargin = PaddingValues(16.dp),
                        colors = kewuCardColors(),
                        onClick = { haptic(HapticKind.Tick); transcriptOpen = !transcriptOpen },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (transcriptOpen) "点击收起" else "点击展开全文",
                                color = cs.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                if (transcriptOpen) COUIIcons.ExpandLess else COUIIcons.ExpandMore,
                                contentDescription = if (transcriptOpen) "收起" else "展开",
                                tint = cs.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        AnimatedVisibility(
                            transcriptOpen,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            Column {
                                Spacer(Modifier.height(10.dp))
                                Text(rec.transcript, color = cs.onSurfaceSecondary, fontSize = 13.sp, lineHeight = 20.sp)
                            }
                        }
                    }
                }

                // AI 课堂复盘：注入本节课专注结论，给老师教学建议
                if (rec.summary.isNotBlank() || rec.transcript.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        cornerRadius = 18.dp,
                        insideMargin = PaddingValues(16.dp),
                        colors = kewuCardColors(),
                        onClick = { haptic(HapticKind.Light); showReview = true },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "AI 课堂复盘（教学建议）",
                                color = cs.primary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                COUIIcons.ChevronForward,
                                contentDescription = null,
                                tint = cs.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }

        // AI 课堂复盘全屏层
        SlideOverlay(rec.takeIf { showReview }) { sess ->
            val students = recs.orEmpty().filter { !it.role.equals("teacher", true) && it.focusVerdict != null }
            val focusLine = if (students.isEmpty()) "" else
                students.groupingBy { it.focusVerdict!! }.eachCount().entries.joinToString("，") { "${it.key} ${it.value} 人" }
            TeachingReviewScreen(sess, courseName, focusLine) { showReview = false }
        }
    }
    BackHandler { onBack() }
}
