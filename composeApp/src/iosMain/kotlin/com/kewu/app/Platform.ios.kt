package com.kewu.app

import io.ktor.client.HttpClient
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import platform.Foundation.create
import io.ktor.client.engine.darwin.Darwin
import platform.UIKit.UIDevice
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle

class IOSPlatform {
    val platform: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

fun getPlatform(): String = IOSPlatform().platform

actual fun platformHttpClient(): HttpClient = HttpClient(Darwin)

// 稳定设备标识：identifierForVendor（同一开发者的 App 共用，卸载全部后才会变），录音雷达 / 多端择优用
actual fun platformDeviceId(): String =
    "ios-" + (UIDevice.currentDevice.identifierForVendor?.UUIDString ?: "unknown")

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
actual fun isAudioPermissionGranted(): Boolean =
    platform.AVFAudio.AVAudioSession.sharedInstance().recordPermission == platform.AVFAudio.AVAudioSessionRecordPermissionGranted
actual fun isCameraPermissionGranted(): Boolean = true
actual fun openAppSettings() = Unit
actual fun shareText(text: String) = Unit
// iOS 人机指南：App 不应自行退出，双击返回的第二下不做任何事（提示照常消失由调用方收起）
actual fun platformExitApp() = Unit

// ── 文件系统（2026-10-26 首次出 iOS 真机包时接入 NSFileManager / NSData）──

/** iOS 没有 Context / Activity：返回占位对象（录音器 / 权限请求只需要非空）。 */
actual fun platformAppContext(): Any = Unit

actual fun platformActivity(): Any? = Unit

actual fun platformCacheDir(): String = platform.Foundation.NSTemporaryDirectory().removeSuffix("/")

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual fun platformReadFile(path: String): ByteArray? {
    val data = platform.Foundation.NSData.create(contentsOfFile = path) ?: return null
    val len = data.length.toInt()
    if (len == 0) return ByteArray(0)
    val bytes = data.bytes ?: return null
    return bytes.reinterpret<ByteVar>().readBytes(len)
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual fun platformFileSize(path: String): Long {
    val attrs = platform.Foundation.NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null) ?: return -1L
    return (attrs[platform.Foundation.NSFileSize] as? platform.Foundation.NSNumber)?.longLongValue ?: -1L
}

actual fun platformFileChannel(path: String): io.ktor.utils.io.ByteReadChannel =
    io.ktor.utils.io.ByteReadChannel(platformReadFile(path) ?: ByteArray(0))

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual fun platformDeleteFile(path: String) {
    platform.Foundation.NSFileManager.defaultManager.removeItemAtPath(path, error = null)
}
