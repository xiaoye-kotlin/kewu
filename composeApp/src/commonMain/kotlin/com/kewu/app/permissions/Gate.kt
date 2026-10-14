package com.kewu.app.permissions

// 各端都实现这个接口：Android 真正弹系统授权框，桌面/iOS 直接给过
interface PermissionRequester {
    fun launch(perm: KewuPermission, onResult: (Boolean) -> Unit)
}

// 给各端统一套一层「先授权再进主界面」的门：Android 走权限页，桌面/iOS 直接进
@androidx.compose.runtime.Composable
expect fun AppGate(content: @androidx.compose.runtime.Composable () -> Unit)
