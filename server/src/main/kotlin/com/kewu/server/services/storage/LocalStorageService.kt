package com.kewu.server.services.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// 本地磁盘兜底存储：没配 OBS 时开发用，部署时切到 ObsStorageService 即可
class LocalStorageService(private val dir: String) : AudioStorage {
    private val root = File(dir).apply { if (!exists()) mkdirs() }

    override suspend fun save(recordingId: String, bytes: ByteArray, ext: String): String {
        val file = File(root, "$recordingId.$ext")
        withContext(Dispatchers.IO) { file.writeBytes(bytes) }
        return "local://${file.absolutePath}"
    }

    override suspend fun get(url: String): ByteArray? {
        val file = File(url.removePrefix("local://"))
        if (!file.exists()) return null
        return withContext(Dispatchers.IO) { file.readBytes() }
    }
}
