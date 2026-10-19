package com.kewu.app

import androidx.compose.runtime.Composable

/**
 * 跨平台「拦截系统返回」组合项。
 *
 * 背景：commonMain 不能直接依赖 androidx.activity.compose.BackHandler（该包仅 Android 提供，非 KMP），
 * 故在此用 expect/actual 抽象。Android 端代理到系统返回键；Desktop/iOS 无系统返回键概念，no-op
 * （桌面窗口关闭由 main.kt 的 Window onCloseRequest 处理）。
 */
@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)
