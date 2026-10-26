package com.kewu.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.AudioPlayback
import com.kewu.app.HapticKind
import com.kewu.app.formatPlayTime
import com.kewu.app.haptic
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.basic.Slider
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Pause
import io.github.suqi8.coui.kmp.icon.extended.Play
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * App 内录音播放条（2026-10-26，coui 原生件）：圆形播放 / 暂停键 + coui [Slider] 进度 + 已播 / 总时长。
 * 全局同一时刻只播一条（[AudioPlayback]），点别的条会自动停掉这条；这条离开界面时自动停止。
 *
 * @param key 播放会话标识（如 `rec:<localId>`），用来判断「正在播的是不是我」
 * @param source http 链接或本机文件路径；null = 无法播放（显示 [unavailableText]）
 * @param durationHintSec 未开始播放时显示的总时长（录音记录 / 附件自带）
 */
@Composable
fun AudioPlayerBar(
    key: String,
    source: String?,
    durationHintSec: Int,
    modifier: Modifier = Modifier,
    unavailableText: String = "本机文件已清理，无法播放",
) {
    val cs = COUITheme.colorScheme
    val st by AudioPlayback.state.collectAsState()
    val active = st.key == key
    DisposableEffect(key) { onDispose { AudioPlayback.stopIf(key) } }

    val totalMs = if (active && st.durationMs > 0) st.durationMs else durationHintSec * 1000L
    // 拖动中先只改本地值，松手再 seek（避免每帧都让播放器跳转）
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (active && totalMs > 0) (st.positionMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PlayPauseButton(
            playing = active && st.phase == AudioPlayback.Phase.PLAYING,
            loading = active && st.phase == AudioPlayback.Phase.LOADING,
            enabled = source != null,
            size = 40.dp,
        ) { source?.let { AudioPlayback.toggle(key, it) } }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Slider(
                value = fraction,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { AudioPlayback.seek(key, (it * totalMs).toLong()) }
                    dragging = null
                },
                enabled = active && totalMs > 0 && st.phase != AudioPlayback.Phase.LOADING && st.phase != AudioPlayback.Phase.ERROR,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val err = if (active && st.phase == AudioPlayback.Phase.ERROR) st.error else null
                val left = when {
                    source == null -> unavailableText
                    err != null -> err
                    active && st.phase == AudioPlayback.Phase.LOADING -> "正在加载…"
                    else -> formatPlayTime(if (dragging != null) (fraction * totalMs).toLong() else if (active) st.positionMs else 0)
                }
                Text(left, color = if (err != null) cs.error else cs.onSurfaceSecondary, fontSize = 11.5.sp, maxLines = 1)
                if (totalMs > 0) Text(formatPlayTime(totalMs), color = cs.onSurfaceVariantSummary, fontSize = 11.5.sp, maxLines = 1)
            }
        }
    }
}

/** 圆形播放 / 暂停键（coui IconButton，主色实心）。加载中显示转圈。 */
@Composable
fun PlayPauseButton(
    playing: Boolean,
    loading: Boolean,
    enabled: Boolean = true,
    size: Dp = 36.dp,
    onClick: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    IconButton(
        onClick = { haptic(HapticKind.Tick); onClick() },
        enabled = enabled,
        backgroundColor = if (enabled) cs.primary else cs.secondaryContainer,
        cornerRadius = size / 2,
        minWidth = size,
        minHeight = size,
    ) {
        if (loading) {
            InfiniteProgressIndicator(color = Color.White, size = size * 0.45f)
        } else {
            Icon(
                if (playing) COUIIcons.Pause else COUIIcons.Play,
                contentDescription = if (playing) "暂停" else "播放",
                tint = if (enabled) Color.White else cs.onSurfaceVariantSummary,
                modifier = Modifier.size(size * 0.48f),
            )
        }
    }
}

/** 只有一个播放键的紧凑版（附件选择列表里的本地录音试听）。 */
@Composable
fun AudioPlayButton(key: String, source: String, size: Dp = 32.dp) {
    val st by AudioPlayback.state.collectAsState()
    val active = st.key == key
    DisposableEffect(key) { onDispose { AudioPlayback.stopIf(key) } }
    PlayPauseButton(
        playing = active && st.phase == AudioPlayback.Phase.PLAYING,
        loading = active && st.phase == AudioPlayback.Phase.LOADING,
        size = size,
    ) { AudioPlayback.toggle(key, source) }
}
