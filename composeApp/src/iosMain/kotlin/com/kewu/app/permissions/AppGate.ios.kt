package com.kewu.app.permissions

import androidx.compose.runtime.Composable

// iOS 端同样跳过权限页，直接进主界面
@Composable
actual fun AppGate(content: @Composable () -> Unit) {
    content()
}
