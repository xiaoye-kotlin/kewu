package com.kewu.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import platform.UIKit.UIDevice

class IOSPlatform {
    val platform: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

fun getPlatform(): String = IOSPlatform().platform

actual fun platformHttpClient(): HttpClient = HttpClient(Darwin)

actual fun platformDeviceId(): String = "ios"

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
