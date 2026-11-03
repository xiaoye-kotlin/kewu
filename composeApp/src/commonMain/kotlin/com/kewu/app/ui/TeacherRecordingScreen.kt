package com.kewu.app.ui

import com.kewu.app.AudioPlayback
import com.kewu.app.BackHandler
import com.kewu.app.ClassRecordingInfo
import com.kewu.app.ClassRoomInfo
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuGreen
import com.kewu.app.SessionRecordingInfo
import com.kewu.app.haptic
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.ProgressIndicatorDefaults
import io.github.suqi8.coui.kmp.basic.Surface
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * 教师端录音管理（2026-11-02）：选班 → 历次课堂（纪要生成进度）→ 单节课：全班上传的录音（可回放）+ 课堂专注。
 * 课堂专注由服务端用每位学生自己录音的转写判断（认真 / 疑似开小差 / 疑似聊天），仅供参考。
 */
@Composable
fun TeacherRecordingScreen(onBack: () -> Unit) {
    val cs = COUITheme.colorScheme
    val backdrop = rememberGlassBackdrop()
    var classes by remember { mutableStateOf<List<ClassRoomInfo>?>(null) }
    var selected by remember { mutableStateOf<ClassRoomInfo?>(null) }
    var sessions by remember { mutableStateOf<List<ClassRecordingInfo>?>(null) }
    var openSession by remember { mutableStateOf<ClassRecordingInfo?>(null) }

    LaunchedEffect(Unit) {
        val list = KewuApi.getTeacherClasses()?.classes.orEmpty()
        classes = list
        if (selected == null) selected = list.firstOrNull()
    }
    LaunchedEffect(selected?.courseId) {
        val c = selected ?: return@LaunchedEffect
        sessions = null
        sessions = KewuApi.getClassRecordings(c.courseId).orEmpty()
    }
    DisposableEffect(Unit) { onDispose { AudioPlayback.stopIfPrefix("trec:") } }
    BackHandler(enabled = openSession == null) { onBack() }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().layerBackdrop(backdrop).background(cs.surface))
        Column(Modifier.fillMaxSize()) {
            KewuTopBar("录音管理", onBack = onBack)
            val list = classes
            when {
                list == null -> Loading()
                list.isEmpty() -> KewuEmpty("还没有班级")
                else -> {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        list.forEach { c ->
                            Chip(
                                selected = selected?.id == c.id,
                                onClick = { haptic(HapticKind.Tick); selected = c },
                                label = c.courseName.ifBlank { c.name },
                            )
                        }
                    }
                    Column(
                        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp).padding(bottom = KewuNavClearance),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val ss = sessions
                        when {
                            ss == null -> Loading()
                            ss.isEmpty() -> KewuEmpty("还没有课堂录音")
                            else -> ss.forEachIndexed { i, s ->
                                SessionCard(s, i) { haptic(HapticKind.Light); openSession = s }
                            }
                        }
                    }
                }
            }
        }
    }

    SlideOverlay(openSession) { s ->
        SessionDetail(s, selected?.courseName.orEmpty()) {
            AudioPlayback.stopIfPrefix("trec:")
            openSession = null
        }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        InfiniteProgressIndicator(size = 24.dp)
    }
}

/** 纪要状态 → 简短文案 + 是否进行中。 */
internal fun ClassRecordingInfo.statusText(): Pair<String, Boolean> = when (transcriptStatus) {
    "DONE" -> "纪要已生成" to false
    "EMPTY" -> "没有识别到讲课内容" to false
    "FAILED" -> "生成失败" to false
    "NO_AUDIO" -> "没有录音" to false
    "TRANSCRIBING" -> (stage ?: "转写中") to true
    else -> (stage ?: "排队中") to true
}

@Composable
private fun SessionCard(s: ClassRecordingInfo, index: Int, onClick: () -> Unit) {
    val cs = COUITheme.colorScheme
    val (text, busy) = s.statusText()
    Card(
        modifier = Modifier.fillMaxWidth().riseEnter(rememberEnterProgress(index)),
        cornerRadius = 18.dp,
        insideMargin = PaddingValues(16.dp),
        colors = kewuCardColors(),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.date, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("${s.candidateCount} 份录音", color = cs.onSurfaceSecondary, fontSize = 12.5.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(text, color = if (s.transcriptStatus == "FAILED") cs.error else cs.onSurfaceSecondary, fontSize = 12.5.sp)
        if (busy) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = ((s.progress ?: 3) / 100f).coerceIn(0.03f, 1f),
                modifier = Modifier.fillMaxWidth(),
                colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = cs.primary),
                height = 6.dp,
            )
        }
    }
}

@Composable
private fun SessionDetail(s: ClassRecordingInfo, courseName: String, onBack: () -> Unit) {
    val cs = COUITheme.colorScheme
    val backdrop = rememberGlassBackdrop()
    var recs by remember(s.sessionId) { mutableStateOf<List<SessionRecordingInfo>?>(null) }
    var failed by remember(s.sessionId) { mutableStateOf<String?>(null) }
    var reload by remember(s.sessionId) { mutableStateOf(0) }
    LaunchedEffect(s.sessionId, reload) {
        recs = null
        failed = null
        KewuApi.getSessionRecordings(s.sessionId)
            .onSuccess { recs = it }
            .onFailure { e ->
                // 显示真实原因（网络 / 权限 / 服务端异常），并可点重试
                failed = e.message?.takeIf { it.isNotBlank() && it.length < 60 } ?: "加载失败"
                recs = emptyList()
            }
    }
    // AI 课堂复盘（2026-11-03）
    var showReview by remember(s.sessionId) { mutableStateOf(false) }
    BackHandler(!showReview) { onBack() }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().layerBackdrop(backdrop).background(cs.surface))
        Column(Modifier.fillMaxSize()) {
            KewuTopBar(courseName.ifBlank { "课堂录音" }, onBack = onBack, subtitle = s.date)
            // AI 课堂复盘入口：读整节转写 + 学生专注结论
            if (s.transcript.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 4.dp, bottom = 6.dp),
                    cornerRadius = 18.dp,
                    insideMargin = PaddingValues(16.dp),
                    colors = kewuCardColors(),
                    onClick = { haptic(HapticKind.Light); showReview = true },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("AI 课堂复盘", color = cs.primary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(2.dp))
                            Text("教学评分 · 课堂脉络 · 易错预警 · 作业草稿", color = cs.onSurfaceSecondary, fontSize = 12.sp)
                        }
                        Text("›", color = cs.primary, fontSize = 22.sp)
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp).padding(bottom = KewuNavClearance),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val list = recs
                when {
                    list == null -> Loading()
                    failed != null -> Column(
                        Modifier.fillMaxWidth().padding(top = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        KewuEmpty(failed!!)
                        Text(
                            "重试",
                            color = cs.primary,
                            fontSize = 15.sp,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp))
                                .clickable { haptic(HapticKind.Light); reload++ }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                        )
                    }
                    list.isEmpty() -> KewuEmpty("这节课还没有录音")
                    else -> {
                        val students = list.filter { !it.role.equals("teacher", true) }
                        val analyzed = students.filter { it.focusVerdict != null }
                        // ── 课堂专注概览（液态玻璃） ──
                        KewuGlassCard(
                            backdrop = backdrop,
                            shape = RoundedCornerShape(20.dp),
                            blurRadius = 20.dp,
                            tintAlpha = 0.30f,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(16.dp)) {
                                FocusCell("认真", analyzed.count { it.focusVerdict == "认真" }, KewuGreen, Modifier.weight(1f))
                                FocusCell("开小差", analyzed.count { it.focusVerdict == "疑似开小差" }, cs.error, Modifier.weight(1f))
                                FocusCell("聊天", analyzed.count { it.focusVerdict == "疑似聊天" }, cs.error, Modifier.weight(1f))
                                FocusCell("未分析", students.size - analyzed.size, cs.onSurfaceSecondary, Modifier.weight(1f))
                            }
                        }
                        // 不认真的排在前面
                        val order = listOf("疑似聊天", "疑似开小差", "认真", null)
                        val sorted = list.sortedWith(
                            compareBy<SessionRecordingInfo> { it.role.equals("teacher", true) }
                                .thenBy { order.indexOf(it.focusVerdict).let { i -> if (i < 0) 3 else i } },
                        )
                        sorted.forEachIndexed { i, r -> StudentRecordingCard(r, i) }
                    }
                }
            }
        }
        SlideOverlay(s.takeIf { showReview }) { sess ->
            val students = recs.orEmpty().filter { !it.role.equals("teacher", true) && it.focusVerdict != null }
            val focusLine = if (students.isEmpty()) "" else
                students.groupingBy { it.focusVerdict!! }.eachCount().entries.joinToString("，") { "${it.key} ${it.value} 人" }
            TeachingReviewScreen(sess, courseName, focusLine) { showReview = false }
        }
    }
}

@Composable
private fun FocusCell(label: String, count: Int, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    val cs = COUITheme.colorScheme
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$count", color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(label, color = cs.onSurfaceSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun StudentRecordingCard(r: SessionRecordingInfo, index: Int) {
    val cs = COUITheme.colorScheme
    val teacher = r.role.equals("teacher", true)
    Card(
        modifier = Modifier.fillMaxWidth().riseEnter(rememberEnterProgress(index)),
        cornerRadius = 18.dp,
        insideMargin = PaddingValues(16.dp),
        colors = kewuCardColors(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                r.name ?: r.username ?: if (teacher) "教师端" else "未知同学",
                color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.weight(1f))
            when {
                teacher -> KewuTag("教师端", cs.tertiaryContainer, cs.onTertiaryContainer)
                r.focusVerdict == "认真" -> KewuTag("认真", KewuGreen.copy(alpha = 0.16f), KewuGreen)
                r.focusVerdict != null -> KewuTag(r.focusVerdict.removePrefix("疑似"), cs.errorContainer, cs.onErrorContainer)
                r.transcriptStatus == null -> KewuTag("未转写", cs.secondaryContainer, cs.onSecondaryVariant)
                else -> KewuTag("未分析", cs.secondaryContainer, cs.onSecondaryVariant)
            }
        }
        if (!r.focusEvidence.isNullOrBlank() && !teacher) {
            Spacer(Modifier.height(6.dp))
            Text(
                r.focusEvidence + (r.focusConfidence?.let { " · 置信 $it%" } ?: ""),
                color = cs.onSurfaceSecondary, fontSize = 12.5.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = cs.secondaryContainer) {
            AudioPlayerBar(
                key = "trec:${r.recordingId}",
                source = r.url.ifBlank { null },
                durationHintSec = r.durationSec ?: 0,
                modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                stopOnDispose = false,
            )
        }
    }
}
