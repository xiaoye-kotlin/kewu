package com.kewu.app

import android.content.Context
import androidx.core.content.edit
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

// 注意：appContext 由同包 Haptics.android.kt 声明（internal lateinit），此处直接复用，避免重复声明冲突。

actual fun platformHttpClient(): HttpClient = HttpClient(OkHttp) {
    // 明文 HTTP（http://47.99.42.168:8080，非 https）——Android 9+ 需 usesCleartextTraffic，
    // 已在 AndroidManifest 开启；这里不额外配置引擎信任。
    engine {
        // 适当超时，避免弱网卡死
        config {
            connectTimeout(15_000, java.util.concurrent.TimeUnit.MILLISECONDS)
            readTimeout(15_000, java.util.concurrent.TimeUnit.MILLISECONDS)
            writeTimeout(15_000, java.util.concurrent.TimeUnit.MILLISECONDS)
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
