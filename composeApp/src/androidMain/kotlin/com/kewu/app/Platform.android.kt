package com.kewu.app

import io.ktor.util.cio.readChannel

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

// 注意：appContext 由同包 Haptics.android.kt 声明（internal lateinit），此处直接复用，避免重复声明冲突。

/** 当前 Activity 实例（MainActivity），供录音权限请求等需要 Activity 的运行时调用。 */
internal var appActivity: MainActivity? = null

actual fun platformAppContext(): Any = appContext
actual fun platformActivity(): Any? = appActivity
actual fun platformCacheDir(): String = appContext.cacheDir.absolutePath
actual fun platformReadFile(path: String): ByteArray? = try {
    java.io.File(path).readBytes()
} catch (_: Exception) {
    null
}

actual fun platformHttpClient(): HttpClient = HttpClient(OkHttp) {
    // 明文 HTTP（http://47.99.42.168:8080，非 https）——Android 9+ 需 usesCleartextTraffic，
    // 已在 AndroidManifest 开启；这里不额外配置引擎信任。
    engine {
        // 适当超时，避免弱网卡死；read 放宽到 120s 兼容大模型推理耗时（AiClient），
        // 业务接口响应快不受影响（超时只是上限）
        config {
            connectTimeout(15_000, java.util.concurrent.TimeUnit.MILLISECONDS)
            readTimeout(120_000, java.util.concurrent.TimeUnit.MILLISECONDS)
            writeTimeout(30_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }
}

actual fun platformDeviceId(): String {
    // 取 Android ID 作为稳定设备标识（录音上传 deviceId 用）
    return try {
        val cr = appContext.contentResolver
        android.provider.Settings.Secure.getString(cr, android.provider.Settings.Secure.ANDROID_ID)
            ?: "android-unknown"
    } catch (_: Exception) {
        "android-unknown"
    }
}

private const val KV_NAME = "kewu_prefs"

actual fun kvGet(key: String): String? {
    val prefs = appContext.getSharedPreferences(KV_NAME, Context.MODE_PRIVATE)
    return prefs.getString(key, null)
}

actual fun kvSet(key: String, value: String?) {
    val prefs = appContext.getSharedPreferences(KV_NAME, Context.MODE_PRIVATE)
    prefs.edit { if (value == null) remove(key) else putString(key, value) }
}

// ── 权限状态查询 / 申请 / 系统设置（通知设置页用） ──

private fun hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

actual fun isNotificationPermissionGranted(): Boolean = hasPermission(android.Manifest.permission.POST_NOTIFICATIONS)

actual fun requestNotificationPermission(activity: Any, onResult: (Boolean) -> Unit) {
    if (activity is MainActivity) {
        activity.requestNotificationPermission { granted ->
            onResult(granted)
        }
    } else {
        onResult(false)
    }
}

actual fun isAudioPermissionGranted(): Boolean = hasPermission(android.Manifest.permission.RECORD_AUDIO)

actual fun isCameraPermissionGranted(): Boolean = hasPermission(android.Manifest.permission.CAMERA)

actual fun openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", appContext.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    appContext.startActivity(intent)
}

actual fun shareText(text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    appContext.startActivity(Intent.createChooser(intent, "分享到").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

actual fun platformExitApp() {
    // 一级界面双击返回键的第二下：finish 当前 Activity（回到桌面 = 退出）。
    // 兜底：appActivity 为 null（理论上不会）时不崩。
    appActivity?.finish()
}

actual fun platformFileSize(path: String): Long = try {
    java.io.File(path).let { if (it.exists()) it.length() else -1L }
} catch (_: Exception) {
    -1L
}

actual fun platformFileChannel(path: String): io.ktor.utils.io.ByteReadChannel =
    java.io.File(path).readChannel()

actual fun platformDeleteFile(path: String) {
    runCatching { java.io.File(path).delete() }
}
