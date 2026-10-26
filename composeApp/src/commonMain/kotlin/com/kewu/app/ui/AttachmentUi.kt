package com.kewu.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.ATTACHMENT_MAX_BYTES
import com.kewu.app.ATTACHMENT_MAX_COUNT
import com.kewu.app.AttachmentInfo
import com.kewu.app.AttachmentKind
import com.kewu.app.AudioRecorder
import com.kewu.app.HapticKind
import com.kewu.app.LocalAttachment
import com.kewu.app.formatFileSize
import com.kewu.app.haptic
import com.kewu.app.openUrl
import com.kewu.app.pickFiles
import com.kewu.app.platformActivity
import com.kewu.app.platformAppContext
import com.kewu.app.platformCacheDir
import com.kewu.app.platformReadFile
import com.kewu.app.requestAudioPermission
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.ChipDefaults
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 作业附件 UI（2026-10-26，提交作业可带 图片 / 文件 / 录音）。全部 coui 原生件：
 *   - [AttachmentPicker]：提交弹窗里的「＋ 图片 / ＋ 文件 / ● 录音」按钮行 + 已选附件列表（可移除），
 *     录音中显示计时与「停止」；
 *   - [AttachmentList]：已提交附件的只读列表（学生「我的提交」、教师批阅页），点一行用系统应用打开。
 */
@Composable
fun AttachmentPicker(
    attachments: SnapshotStateList<LocalAttachment>,
    enabled: Boolean,
    onToast: (String) -> Unit,
) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    val recorder = remember { runCatching { AudioRecorder(platformAppContext()) }.getOrNull() }
    var recording by remember { mutableStateOf(false) }
    var recSecs by remember { mutableIntStateOf(0) }

    LaunchedEffect(recording) {
        recSecs = 0
        while (recording) {
            delay(1000)
            recSecs++
        }
    }
    // 弹窗关闭 / 页面销毁时若还在录，停掉麦克风（录音文件丢弃）
    // ★ 2026-10-26：页面销毁时 rememberCoroutineScope 紧接着被取消，旧版在这里 scope.launch 停录音大概率不执行，
    //   麦克风一直开着。改用进程级 GlobalScope 停止并删掉这段录音文件。
    DisposableEffect(Unit) {
        onDispose {
            if (recorder?.isRecording() == true) {
                @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
                kotlinx.coroutines.GlobalScope.launch {
                    runCatching { recorder.stopRecording()?.let { com.kewu.app.platformDeleteFile(it) } }
                }
            }
        }
    }

    fun newId() = "att${kotlin.time.Clock.System.now().toEpochMilliseconds()}${Random.nextInt(100, 999)}"

    fun addPicked(images: Boolean) {
        if (attachments.size >= ATTACHMENT_MAX_COUNT) {
            onToast("最多添加 $ATTACHMENT_MAX_COUNT 个附件")
            return
        }
        pickFiles(images) { files ->
            var skipped = 0
            files.forEach { f ->
                if (attachments.size >= ATTACHMENT_MAX_COUNT) return@forEach
                if (f.size > ATTACHMENT_MAX_BYTES) {
                    skipped++
                    return@forEach
                }
                val kind = if (images || f.mime.startsWith("image/")) AttachmentKind.IMAGE else AttachmentKind.FILE
                attachments += LocalAttachment(newId(), kind, f.name, f.path, f.mime, f.size)
            }
            if (skipped > 0) onToast("有 $skipped 个文件超过 ${formatFileSize(ATTACHMENT_MAX_BYTES)}，已跳过")
        }
    }

    fun toggleRecord() {
        val rec = recorder ?: run { onToast("当前设备不支持录音"); return }
        if (recording) {
            recording = false
            val secs = recSecs
            scope.launch {
                val path = runCatching { rec.stopRecording() }.getOrNull()
                if (path == null) {
                    onToast("录音保存失败")
                    return@launch
                }
                val size = platformReadFile(path)?.size?.toLong() ?: 0L
                attachments += LocalAttachment(
                    newId(), AttachmentKind.AUDIO, "录音 ${secs}秒.wav", path, "audio/wav", size,
                    durationSec = secs.coerceAtLeast(1),
                )
            }
            return
        }
        if (attachments.size >= ATTACHMENT_MAX_COUNT) {
            onToast("最多添加 $ATTACHMENT_MAX_COUNT 个附件")
            return
        }
        val start: () -> Unit = {
            scope.launch {
                val out = "${platformCacheDir()}/attach/rec_${kotlin.time.Clock.System.now().toEpochMilliseconds()}.pcm"
                val ok = runCatching { rec.startRecording(out) }.getOrDefault(false)
                if (ok) {
                    haptic(HapticKind.Light)
                    recording = true
                } else {
                    onToast("无法开始录音（麦克风可能被占用或未授权）")
                }
            }
        }
        val activity = platformActivity()
        if (activity != null) requestAudioPermission(activity) { granted ->
            if (granted) start() else onToast("需要麦克风权限才能录音")
        } else start()
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chip(selected = false, enabled = enabled && !recording, label = "＋ 图片",
                onClick = { haptic(HapticKind.Tick); addPicked(images = true) })
            Chip(selected = false, enabled = enabled && !recording, label = "＋ 文件",
                onClick = { haptic(HapticKind.Tick); addPicked(images = false) })
            Chip(
                selected = recording, enabled = enabled,
                label = if (recording) "■ 停止 ${recSecs / 60}:${(recSecs % 60).toString().padStart(2, '0')}" else "● 录音",
                onClick = { haptic(HapticKind.Tick); toggleRecord() },
                colors = if (recording) ChipDefaults.chipColors(containerColor = cs.error.copy(alpha = 0.16f))
                else ChipDefaults.chipColors(),
            )
        }
        AnimatedVisibility(
            visible = attachments.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
                Spacer(Modifier.height(6.dp))
                attachments.toList().forEach { a ->
                    BasicComponent(
                        title = a.name,
                        summary = buildString {
                            append(a.kind.label)
                            a.durationSec?.let { append(" · ${it}秒") }
                            append(" · ${formatFileSize(a.size)}")
                        },
                        endActions = {
                            TextButton(
                                text = "移除",
                                enabled = enabled,
                                onClick = { haptic(HapticKind.Tick); attachments.remove(a) },
                                colors = ButtonDefaults.textButtonColorsPrimary(
                                    color = cs.error, textColor = cs.onErrorContainer,
                                ),
                            )
                        },
                    )
                }
            }
        }
        Text(
            "可附图片、文件或录音，单个不超过 ${formatFileSize(ATTACHMENT_MAX_BYTES)}，最多 $ATTACHMENT_MAX_COUNT 个",
            color = cs.onSurfaceVariantSummary, fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 已提交附件的只读列表：一行一个 coui [BasicComponent]，点击用系统应用 / 浏览器打开。 */
@Composable
fun AttachmentList(attachments: List<AttachmentInfo>, modifier: Modifier = Modifier) {
    if (attachments.isEmpty()) return
    val cs = COUITheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        Text("附件（${attachments.size}）", color = cs.onSurfaceSecondary, fontSize = 13.sp,
            fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(4.dp))
        attachments.forEach { a ->
            BasicComponent(
                title = a.name,
                summary = buildString {
                    append(a.kind.label)
                    a.durationSec?.let { append(" · ${it}秒") }
                    if (a.size > 0) append(" · ${formatFileSize(a.size)}")
                    append(" · 点击查看")
                },
                onClick = { haptic(HapticKind.Tick); openUrl(a.url) },
            )
        }
    }
}
