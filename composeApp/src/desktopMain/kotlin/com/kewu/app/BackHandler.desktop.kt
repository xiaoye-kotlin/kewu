package com.kewu.app

import androidx.compose.runtime.Composable

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Desktop 无系统返回键概念：no-op（窗口关闭由 main.kt 的 Window onCloseRequest 处理）
}
