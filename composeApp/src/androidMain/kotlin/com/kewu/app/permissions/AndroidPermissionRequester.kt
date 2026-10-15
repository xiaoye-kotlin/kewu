package com.kewu.app.permissions

import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

// Android 端真正向系统申请权限：危险权限（麦克风、位置、通知）弹窗，普通权限安装即授予
// 注意：前台服务、唤醒锁这类普通权限不能走 RequestPermission，否则系统会因「非危险权限」直接抛异常
class AndroidPermissionRequester(private val activity: ComponentActivity) : PermissionRequester {
    private var pending: ((Boolean) -> Unit)? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> pending?.invoke(granted).also { pending = null } }

    override fun launch(perm: KewuPermission, onResult: (Boolean) -> Unit) {
        val name = perm.key

        // 先看这个权限到底是不是需要运行时弹窗的危险权限；拿不到信息（如高版本才有的权限）也按已授予处理
        @Suppress("DEPRECATION")
        val isDangerous = runCatching {
            val level = activity.packageManager.getPermissionInfo(name, 0).protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
            level == PermissionInfo.PROTECTION_DANGEROUS
        }.getOrDefault(false)

        // 普通权限安装时就给了，不必弹框；不然 RequestPermission 会抛 IllegalArgumentException
        if (!isDangerous) {
            onResult(true)
            return
        }

        if (ContextCompat.checkSelfPermission(activity, name) == PackageManager.PERMISSION_GRANTED) {
            onResult(true)
            return
        }

        pending = onResult
        launcher.launch(name)
    }
}
