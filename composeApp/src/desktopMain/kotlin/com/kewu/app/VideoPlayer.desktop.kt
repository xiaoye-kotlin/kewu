package com.kewu.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * Desktop 占位 actual：桌面端暂不支持应用内播放（可后续接 VLCJ/JavaFX 再补），
 * 由上层覆盖层决定兜底（目前是提示文案）。
 */
@Composable
actual fun VideoPlayerView(
    sources: DashVideoSources,
    modifier: Modifier,
    retryToken: Int,
    onError: (String) -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "桌面端暂不支持应用内播放",
            color = COUITheme.colorScheme.onSurface,
        )
    }
}
