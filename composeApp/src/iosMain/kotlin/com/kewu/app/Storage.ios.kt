package com.kewu.app

// iOS 端尚未接入本地文件（platformDeleteFile 等同为空实现），存储扫描返回空
actual fun platformListFiles(dir: String): List<LocalFile> = emptyList()

actual fun platformCrashDir(): String? = null
