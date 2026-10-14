package com.kewu.app.permissions

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

// Android 端：先弹出权限页逐条授权，全部给完再进主界面
@Composable
actual fun AppGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = (context as? android.content.ContextWrapper)?.let { ctx ->
        var c: android.content.Context? = ctx
        while (c is android.content.ContextWrapper) {
            if (c is ComponentActivity) return@let c
            c = c.baseContext
        }
        null
    }
    val requester = remember(activity) { activity?.let { AndroidPermissionRequester(it) } }

    // 还没授权就停在权限页，点完「开始使用」再切到主界面
    var authed by remember { mutableStateOf(false) }
    if (requester != null && !authed) {
        PermissionFlow(requester = requester, onGranted = { authed = true })
    } else {
        content()
    }
}
