package com.kewu.server.services

import com.kewu.server.config.RedisConfig
import com.kewu.server.model.ClassroomSession
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

// Redis 缓存层：课堂纪要、签到状态、协同会话都走这里，连不上就 gracefully 降级，接口不会挂
class RedisService(private val cfg: RedisConfig) {
    private var connection: StatefulRedisConnection<String, String>? = null
    private var broken = false

    private fun connOrNull(): StatefulRedisConnection<String, String>? {
        if (broken) return null
        if (connection == null) {
            try {
                val uri = if (cfg.password != null)
                    RedisURI.create("redis://:${cfg.password}@${cfg.host}:${cfg.port}")
                else
                    RedisURI.create("redis://${cfg.host}:${cfg.port}")
                connection = RedisClient.create(uri).connect()
            } catch (t: Throwable) {
                broken = true
                return null
            }
        }
        return connection
    }

    // 连不上或中途断线时返回 fallback，调用方无感知
    private suspend fun <T> withRedis(fallback: T, block: (RedisCommands<String, String>) -> T): T =
        withContext(Dispatchers.IO) {
            val c = connOrNull() ?: return@withContext fallback
            try {
                block(c.sync())
            } catch (t: Throwable) {
                broken = true
                fallback
            }
        }

    // 课堂纪要：整段内容直接存字符串，下课即出结构化要点
    suspend fun cacheClassNotes(classId: String, content: String) =
        withRedis("OK") { it.set("kewu:notes:$classId", content) }
    suspend fun getClassNotes(classId: String): String? =
        withRedis(null) { it.get("kewu:notes:$classId") }

    // 签到状态：用哈希存一节课里每个学生的状态
    suspend fun setCheckIn(classId: String, studentId: String, status: String) =
        withRedis(false) { it.hset("kewu:checkin:$classId", studentId, status) }
    suspend fun getCheckIns(classId: String): Map<String, String> =
        withRedis(emptyMap()) { it.hgetall("kewu:checkin:$classId") }

    // 协同会话：整段 JSON 存一份，多端来读同一份
    suspend fun saveSession(session: ClassroomSession) =
        withRedis("OK") { it.set("kewu:session:${session.sessionId}", Json.encodeToString(ClassroomSession.serializer(), session)) }
    suspend fun getSession(sessionId: String): ClassroomSession? =
        withRedis(null) { it.get("kewu:session:$sessionId") }?.let { Json.decodeFromString(ClassroomSession.serializer(), it) }

    // 录音元数据按会话存一份，上传即落账，融合核对时服务端自己也有账
    suspend fun saveRecordingMeta(sessionId: String, metaJson: String) =
        withRedis(false) { it.rpush("kewu:recordings:$sessionId", metaJson) }
    suspend fun getRecordingMetas(sessionId: String): List<String> =
        withRedis(emptyList()) { it.lrange("kewu:recordings:$sessionId", 0L, -1L) }

    fun close() = runCatching { connection?.close() }
}
