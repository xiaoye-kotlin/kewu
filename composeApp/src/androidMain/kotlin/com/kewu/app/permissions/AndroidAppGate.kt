package com.kewu.app.permissions

import androidx.compose.runtime.Composable
import com.kewu.app.MainActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

// Android 端：先弹出权限页逐条授权，全部给完再进主界面
@Composable
actual fun AppGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = context as? MainActivity

    // 还没授权就停在权限页，点完「开始使用」再切到主界面
    var authed by remember { mutableStateOf(false) }
    if (activity != null && !authed) {
        PermissionFlow(requester = AndroidPermissionRequester(activity), onGranted = { authed = true })
    } else {
        content()
    }
}
