package com.kewu.app.permissions

import androidx.compose.runtime.Composable

// 桌面端没有系统权限这套，直接进主界面
@Composable
actual fun AppGate(content: @Composable () -> Unit) {
    content()
}
