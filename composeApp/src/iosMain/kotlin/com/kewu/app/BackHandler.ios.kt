package com.kewu.app

import androidx.compose.runtime.Composable

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    // iOS 无系统返回键概念：no-op（返回由平台导航栈管理）
}
