package com.kewu.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import platform.UIKit.UIDevice
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle

class IOSPlatform {
    val platform: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

fun getPlatform(): String = IOSPlatform().platform

actual fun platformHttpClient(): HttpClient = HttpClient(Darwin)

actual fun platformDeviceId(): String = "ios"

actual fun haptic(kind: HapticKind) {
    val style = when (kind) {
        HapticKind.Tick, HapticKind.Light -> UIImpactFeedbackStyle.UIImpactFeedbackStyleLight
        HapticKind.Medium -> UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium
    }
    UIImpactFeedbackGenerator(style = style).impactOccurred()
}

private const val KV_NAME = "kewu_prefs"

actual fun kvGet(key: String): String? {
    val defaults = platform.Foundation.NSUserDefaults(suiteName = KV_NAME)
    val v = defaults?.stringForKey(key)
    return if (v.isNullOrEmpty()) null else v
}

actual fun kvSet(key: String, value: String?) {
    val defaults = platform.Foundation.NSUserDefaults(suiteName = KV_NAME)
    if (value == null) defaults?.removeObjectForKey(key) else defaults?.setObject(value, forKey = key)
}

actual fun requestCameraPermission(activity: Any, onResult: (Boolean) -> Unit) = onResult(false)

// iOS 通知权限走 UNUserNotificationCenter 申请（未实机接入，先按已授予占位）；设置页跳系统设置需 UIApplication，留待 iOS 完善
actual fun isNotificationPermissionGranted(): Boolean = true
actual fun requestNotificationPermission(activity: Any, onResult: (Boolean) -> Unit) = onResult(false)
actual fun isAudioPermissionGranted(): Boolean = true
actual fun isCameraPermissionGranted(): Boolean = true
actual fun openAppSettings() = Unit
actual fun shareText(text: String) = Unit
// iOS 人机指南：App 不应自行退出，双击返回的第二下不做任何事（提示照常消失由调用方收起）
actual fun platformExitApp() = Unit

// iOS 暂未接入文件系统（Windows 无法编译 iOS 目标），先给可编译的保守实现
actual fun platformFileSize(path: String): Long = -1L

actual fun platformFileChannel(path: String): io.ktor.utils.io.ByteReadChannel =
    io.ktor.utils.io.ByteReadChannel(ByteArray(0))

actual fun platformDeleteFile(path: String) = Unit
