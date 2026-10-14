package com.kewu.app

import platform.UIKit.UIDevice

class IOSPlatform {
    val platform: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

fun getPlatform(): String = IOSPlatform().platform
