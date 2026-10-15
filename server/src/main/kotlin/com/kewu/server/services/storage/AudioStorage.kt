package com.kewu.server.services.storage

// 音频落地方式的统一接口，本地和 OBS 互相替换不影响上层
interface AudioStorage {
    suspend fun save(recordingId: String, bytes: ByteArray, ext: String): String
    suspend fun get(url: String): ByteArray?
}
