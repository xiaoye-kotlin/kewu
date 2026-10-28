package com.kewu.app

import java.io.File

actual fun platformListFiles(dir: String): List<LocalFile> = try {
    File(dir).walkTopDown().filter { it.isFile }.map { LocalFile(it.absolutePath, it.length()) }.toList()
} catch (_: Exception) {
    emptyList()
}

actual fun platformCrashDir(): String? = null
