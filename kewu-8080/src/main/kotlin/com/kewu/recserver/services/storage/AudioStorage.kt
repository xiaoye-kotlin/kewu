package com.kewu.recserver.services.storage

// 音频落地方式的统一接口，本地和 OBS 互相替换不影响上层；metadata 会随对象一起存（OBS 对象元数据/本地 sidecar）
interface AudioStorage {
    suspend fun save(recordingId: String, bytes: ByteArray, ext: String, metadata: Map<String, String> = emptyMap()): String
    suspend fun get(url: String): ByteArray?
    // 通用对象存储（作业附件 / 课前资料用，2026-10-26）：按给定 key 存，返回可直接打开的 URL
    suspend fun saveObject(key: String, bytes: ByteArray, contentType: String): String
}
