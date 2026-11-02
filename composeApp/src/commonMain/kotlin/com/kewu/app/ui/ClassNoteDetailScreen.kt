package com.kewu.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import com.kewu.app.ClassRecordingInfo
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuBlue
import com.kewu.app.TeacherContactInfo
import com.kewu.app.formatPlayTime
import com.kewu.app.haptic
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.ChevronForward
import io.github.suqi8.coui.kmp.icon.extended.ExpandLess
import io.github.suqi8.coui.kmp.icon.extended.ExpandMore
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch

/**
 * 单节课堂纪要详情页（2026-10-31，学生 / 教师通知「录音转写」点击进入，两端共用）。
 *
 * 数据：`GET /api/courses/{courseId}/class-recordings` 里按 [date] 过滤出的那节课
 * （一天可能有多节同名课，按 decidedAt 倒序，可横向切换）。
 *
 * 展示：
 * - 转写状态横幅（PENDING_ASR / TRANSCRIBING / DONE / FAILED / NO_AUDIO / EMPTY）
 * - AI 课堂纪要全文（summary）
 * - 主录音播放条（[AudioPlayerBar]）+ 录音元信息（时长 / 音源数 / 择优策略 / 结束原因 / 生成时间）
 * - 完整转写（transcript，默认折叠）
 * - 任课教师联系方式（受保护接口；未公开 / 无权限统一降级文案，不显示原始错误）
 *
 * 兼容：老后端没有 durationSec / teacher-contact 时接口整体失败或字段缺失，
 * 时长用 OBS HEAD 元数据兜底，联系方式显示「暂不可获取」，不阻断纪要展示。
 */
@Composable
fun ClassNoteDetailScreen(
    courseId: String,
    date: String,
    onBack: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    val pageBackdrop = rememberGlassBackdrop()
    val scrollState = rememberScrollState()

    // null = 还没拉到 / 拉取失败（两者分开标记，失败可重试）
    var recordings by remember { mutableStateOf<List<ClassRecordingInfo>?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var contact by remember { mutableStateOf<TeacherContactInfo?>(null) }
    var contactTried by remember { mutableStateOf(false) }
    var courseName by remember { mutableStateOf("") }
    // 「问小悟 · 本节复习」页内覆盖层
    var showTutor by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            loaded = false
            loadFailed = false
            val r = KewuApi.getClassRecordings(courseId)
            if (r == null) {
                loadFailed = true
            } else {
                recordings = r
            }
            courseName = KewuApi.getCourse(courseId)?.name ?: ""
            loaded = true
        }
        if (!contactTried) {
            contactTried = true
            scope.launch { contact = KewuApi.getTeacherContact(courseId) }
        }
    }

    LaunchedEffect(courseId) { reload() }

    // 当天全部课堂会话（最新在前）
    val daySessions = remember(recordings, date) {
        recordings.orEmpty().filter { it.date == date }.sortedByDescending { it.decidedAt }
    }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val rec = daySessions.firstOrNull { it.sessionId == selectedId } ?: daySessions.firstOrNull()

    // 老数据没有 durationSec：对主录音 OBS HEAD 一次兜底（每换一节课探测一次）
    var probedSec by remember(rec?.sessionId) { mutableStateOf<Int?>(null) }
    LaunchedEffect(rec?.sessionId, rec?.primaryUrl) {
        probedSec = null
        val r = rec ?: return@LaunchedEffect
        if (r.durationSec == null && r.primaryUrl != null) {
            probedSec = KewuApi.probeObsDurationSec(r.primaryUrl)
        }
    }
    val durationSec = rec?.durationSec ?: probedSec

    var transcriptOpen by remember { mutableStateOf(false) }

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
            HubTopBar("$date 课堂纪要", onBack)
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
                ) {
                    Text("点击重试", color = cs.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            } else if (rec == null) {
                KewuEmpty("该日暂无课堂纪要")
            } else {
                // 一天多节：横向小卡切换
                if (daySessions.size > 1) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
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

                // AI 纪要全文
                if (rec.summary.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    HubSectionLabel("AI 课堂纪要")
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        cornerRadius = 16.dp,
                        insideMargin = PaddingValues(16.dp),
                        colors = kewuCardColors(),
                    ) {
                        Text(rec.summary, color = cs.onSurface, fontSize = 14.sp, lineHeight = 21.sp)
                    }
                }

                // 主录音播放 + 元信息
                if (rec.primaryUrl != null || durationSec != null) {
                    Spacer(Modifier.height(14.dp))
                    HubSectionLabel("课堂录音")
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        cornerRadius = 16.dp,
                        insideMargin = PaddingValues(16.dp),
                        colors = kewuCardColors(),
                    ) {
                        AudioPlayerBar(
                            key = "classnote:${rec.sessionId}",
                            source = rec.primaryUrl,
                            durationHintSec = durationSec ?: 0,
                        )
                        if (durationSec != null && durationSec > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "时长 ${formatPlayTime(durationSec * 1000L)}",
                                color = cs.onSurfaceSecondary, fontSize = 12.sp,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        NoteMetaLine("音源数量", "${rec.candidateCount}")
                        NoteMetaLine("择优策略", rec.strategy.ifBlank { "—" })
                        NoteMetaLine("结束原因", rec.endReason.ifBlank { "—" })
                        NoteMetaLine("生成时间", rec.decidedAt.take(16).replace("T", " "))
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
                        AnimatedVisibility(transcriptOpen) {
                            Column {
                                Spacer(Modifier.height(10.dp))
                                Text(rec.transcript, color = cs.onSurfaceSecondary, fontSize = 13.sp, lineHeight = 20.sp)
                            }
                        }
                    }
                }

                // 教师联系方式（受保护接口，403 / 未公开都走降级文案）
                Spacer(Modifier.height(14.dp))
                HubSectionLabel("任课教师")
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    cornerRadius = 16.dp,
                    insideMargin = PaddingValues(16.dp),
                    colors = kewuCardColors(),
                ) {
                    val c = contact
                    if (c == null) {
                        Text("联系方式暂不可获取", color = cs.onSurfaceSecondary, fontSize = 13.sp)
                    } else {
                        Text(
                            c.teacherName ?: "任课教师",
                            color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (c.phone.isNullOrBlank() && c.email.isNullOrBlank()) {
                            Text("教师未公开联系方式", color = cs.onSurfaceSecondary, fontSize = 13.sp)
                        } else {
                            if (!c.phone.isNullOrBlank()) NoteMetaLine("电话", c.phone)
                            if (!c.email.isNullOrBlank()) NoteMetaLine("邮箱", c.email)
                        }
                    }
                }
                // 「问小悟 · 本节复习」入口：只有这节课有纪要 / 转写内容才出现
                if (rec.summary.isNotBlank() || rec.transcript.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        cornerRadius = 18.dp,
                        insideMargin = PaddingValues(16.dp),
                        colors = kewuCardColors(),
                        onClick = { haptic(HapticKind.Light); showTutor = true },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "问小悟 · 本节复习",
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
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "小悟已读本节纪要和转写，可连续追问",
                            color = cs.onSurfaceSecondary, fontSize = 12.sp,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        // 「问小悟 · 本节复习」页内全屏覆盖层（自带 BackHandler，优先消费返回键）
        SlideOverlay(rec?.takeIf { showTutor }) { activeRec ->
            ClassNoteTutorScreen(
                sessionId = activeRec.sessionId,
                courseName = courseName,
                teacherName = activeRec.teacherName ?: contact?.teacherName ?: "",
                date = date,
                durationSec = durationSec,
                summary = activeRec.summary,
                transcript = activeRec.transcript,
                onBack = { showTutor = false },
            )
        }
    }
}

/** 转写状态 → 顶部横幅（非 DONE 时解释为什么看不到纪要）。 */
@Composable
private fun StatusBanner(rec: ClassRecordingInfo) {
    val cs = COUITheme.colorScheme
    val (text, tint) = when (rec.transcriptStatus) {
        "DONE" -> return
        "PENDING_ASR", "TRANSCRIBING" -> "录音转写进行中，纪要生成后会推送到通知" to KewuBlue
        "FAILED" -> "本节转写失败，纪要暂不可用" to androidx.compose.ui.graphics.Color(0xFFFF9500)
        "NO_AUDIO" -> "本节没有可用录音，未生成纪要" to cs.onSurfaceSecondary
        "EMPTY" -> "本节录音为空，未生成纪要" to cs.onSurfaceSecondary
        else -> "纪要状态未知，请稍后重试" to cs.onSurfaceSecondary
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        colors = kewuCardColors(),
    ) {
        Text(text, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun NoteMetaLine(label: String, value: String) {
    val cs = COUITheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = cs.onSurfaceSecondary, fontSize = 13.sp, modifier = Modifier.width(72.dp))
        Text(value, color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
