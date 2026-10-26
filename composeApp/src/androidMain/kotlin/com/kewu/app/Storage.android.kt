package com.kewu.app

import java.io.File

actual fun platformListFiles(dir: String): List<LocalFile> = try {
    File(dir).walkTopDown().filter { it.isFile }.map { LocalFile(it.absolutePath, it.length()) }.toList()
} catch (_: Exception) {
    emptyList()
}

// 与 CrashLog 写入位置一致
actual fun platformCrashDir(): String? = try {
    val ctx = appContext
    File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "crash").absolutePath
} catch (_: Exception) {
    null
}
