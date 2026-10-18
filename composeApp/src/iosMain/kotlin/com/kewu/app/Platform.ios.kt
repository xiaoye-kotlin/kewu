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
