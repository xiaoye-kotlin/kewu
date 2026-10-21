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
    private var lastFailAt = 0L

    companion object {
        // 过期时间（秒）：都是"一节课"粒度的课堂数据，统一给 24 小时。
        // 协同会话虽然叫"会话"，但它是课堂录音协同会话、跟一节课同生命周期，不是登录态——登录 token 归 Java 8080 侧、2 小时，别混
        private const val TTL_CLASS_DATA_SECONDS = 24L * 60 * 60   // 课堂纪要 / 签到 / 录音元数据：24h
        private const val TTL_SESSION_SECONDS = 24L * 60 * 60      // 课堂协同会话：24h（跟一节课同生命周期，防连堂课上掉线）

        // 连崩后隔这么久再给一次重连机会，Redis 恢复了服务能自己活过来，不用重启
        private const val RECONNECT_BACKOFF_MS = 30_000L
    }

    private fun connOrNull(): StatefulRedisConnection<String, String>? {
        // 刚连崩过：冷静一下再试，别每次请求都去撞一遍连不上的 Redis
        if (broken && System.currentTimeMillis() - lastFailAt < RECONNECT_BACKOFF_MS) {
            return null
        }
        if (connection == null) {
            try {
                val uri = if (cfg.password != null)
                    RedisURI.create("redis://:${cfg.password}@${cfg.host}:${cfg.port}")
                else
                    RedisURI.create("redis://${cfg.host}:${cfg.port}")
                connection = RedisClient.create(uri).connect()
                broken = false
            } catch (t: Throwable) {
                markBroken()
                return null
            }
        }
        return connection
    }

    // 记一次崩溃：关旧连接、置脏、记时间，供冷却和重连判断
    private fun markBroken() {
        broken = true
        lastFailAt = System.currentTimeMillis()
        runCatching { connection?.close() }
        connection = null
    }

    // 探活：真去连一次并 PING，能用返回 true。连崩过也强制重试，好让 Redis 恢复后立刻被发现（/health 用）
    suspend fun redisHealthy(): Boolean = withContext(Dispatchers.IO) {
        broken = false
        try {
            val c = connOrNull() ?: return@withContext false
            "PONG".equals(c.sync().ping(), ignoreCase = true)
        } catch (t: Throwable) {
            markBroken()
            false
        }
    }

    // 连不上或中途断线时返回 fallback，调用方无感知
    private suspend fun <T> withRedis(fallback: T, block: (RedisCommands<String, String>) -> T): T =
        withContext(Dispatchers.IO) {
            val c = connOrNull() ?: return@withContext fallback
            try {
                block(c.sync())
            } catch (t: Throwable) {
                markBroken()
                fallback
            }
        }

    // 课堂纪要：整段内容直接存字符串，下课即出结构化要点。设 24h 过期；返回 true=真写进去了，false=Redis 不可用已降级
    suspend fun cacheClassNotes(classId: String, content: String): Boolean =
        withRedis(false) { it.setex("kewu:notes:$classId", TTL_CLASS_DATA_SECONDS, content); true }
    suspend fun getClassNotes(classId: String): String? =
        withRedis(null) { it.get("kewu:notes:$classId") }

    // 签到状态：用哈希存一节课里每个学生的状态。同一学生重复签到只是覆盖、不报错；整份哈希设 24h 过期。返回 true=真写入，false=降级
    suspend fun setCheckIn(classId: String, studentId: String, status: String): Boolean =
        withRedis(false) { c ->
            c.hset("kewu:checkin:$classId", studentId, status)
            c.expire("kewu:checkin:$classId", TTL_CLASS_DATA_SECONDS)
            true
        }
    suspend fun getCheckIns(classId: String): Map<String, String> =
        withRedis(emptyMap()) { it.hgetall("kewu:checkin:$classId") }

    // 协同会话：整段 JSON 存一份，多端来读同一份。跟一节课同生命周期，设 24h 过期。返回 true=真写入，false=降级
    suspend fun saveSession(session: ClassroomSession): Boolean =
        withRedis(false) { it.setex("kewu:session:${session.sessionId}", TTL_SESSION_SECONDS, Json.encodeToString(ClassroomSession.serializer(), session)); true }
    suspend fun getSession(sessionId: String): ClassroomSession? =
        withRedis(null) { it.get("kewu:session:$sessionId") }?.let { Json.decodeFromString(ClassroomSession.serializer(), it) }

    // 录音元数据按会话存一份，上传即落账，融合核对时服务端自己也有账。随课堂数据一并设 24h 过期
    suspend fun saveRecordingMeta(sessionId: String, metaJson: String): Boolean =
        withRedis(false) { c ->
            c.rpush("kewu:recordings:$sessionId", metaJson)
            c.expire("kewu:recordings:$sessionId", TTL_CLASS_DATA_SECONDS)
            true
        }
    suspend fun getRecordingMetas(sessionId: String): List<String> =
        withRedis(emptyList()) { it.lrange("kewu:recordings:$sessionId", 0L, -1L) }

    fun close() = runCatching { connection?.close() }
}
