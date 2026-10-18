package com.kewu.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.util.prefs.Preferences

actual fun platformHttpClient(): HttpClient = HttpClient(OkHttp)

actual fun platformDeviceId(): String = "desktop-${System.getProperty("user.name", "user")}"

private val preferences: Preferences
    get() = Preferences.userRoot().node("com.kewu.app")

actual fun kvGet(key: String): String? = preferences.get(key, null)

actual fun kvSet(key: String, value: String?) {
    if (value == null) preferences.remove(key) else preferences.put(key, value)
}

actual fun requestCameraPermission(activity: Any, onResult: (Boolean) -> Unit) = onResult(false)

// Desktop 无运行时权限概念：通知恒「已授予」，申请回调 false，设置页 no-op
actual fun isNotificationPermissionGranted(): Boolean = true
actual fun requestNotificationPermission(activity: Any, onResult: (Boolean) -> Unit) = onResult(false)
actual fun isAudioPermissionGranted(): Boolean = true
actual fun isCameraPermissionGranted(): Boolean = true
actual fun openAppSettings() = Unit
actual fun shareText(text: String) = Unit
actual fun platformExitApp() { exitProcess(0) }
