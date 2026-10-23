package com.kewu.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** iOS 扫码占位（未接 AVCapture；当前走暗号签到兜底）。 */
@Composable
actual fun QrScannerView(onScanned: (String) -> Unit, modifier: Modifier, enabled: Boolean) {
    Box(modifier.background(COUITheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Text("iOS 端暂不支持相机扫码", color = COUITheme.colorScheme.onSurfaceSecondary)
    }
}
