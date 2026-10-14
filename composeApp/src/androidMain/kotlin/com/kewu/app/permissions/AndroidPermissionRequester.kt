package com.kewu.app.permissions

import androidx.activity.ComponentActivity
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

// Android 端真正向系统申请权限：危险权限（麦克风、位置、通知）弹窗，普通权限直接给过
class AndroidPermissionRequester(private val activity: ComponentActivity) : PermissionRequester {
    private var pending: ((Boolean) -> Unit)? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> pending?.invoke(granted).also { pending = null } }

    override fun launch(perm: KewuPermission, onResult: (Boolean) -> Unit) {
        val already = ContextCompat.checkSelfPermission(activity, perm.key) == PackageManager.PERMISSION_GRANTED
        if (already) return onResult(true)
        pending = onResult
        launcher.launch(perm.key)
    }
}
