package com.kewu.db

import io.lettuce.core.RedisClient
import org.slf4j.LoggerFactory

// AI 纪要生成状态：从 Redis 读 `kewu:ai:status:{sessionId}`。
// 和 RadarRedisClient 一样，连接失败要降级；但本模块还要严格区分“key 不存在”和“Redis 出错”——
// 前者前端应显示“未开始(PENDING)”，后者应显示“获取失败(ERROR)”，所以不能把两种都吞成 null。
// 因此这里用密封结果类型把三种情况分开返回，让上层 Service 各走各的逻辑。
sealed interface AiRedisResult {
    data class Value(val json: String) : AiRedisResult
    data object Missing : AiRedisResult            // key 不存在（正常：还没开始生成）
    data class Error(val e: Throwable) : AiRedisResult   // 连接 / 超时 / 读取异常
}

class AiStatusRedis(
    private val host: String = System.getenv("KEWU_REDIS_HOST") ?: "127.0.0.1",
    private val port: Int = System.getenv("KEWU_REDIS_PORT")?.toIntOrNull() ?: 6379
) {
    private val log = LoggerFactory.getLogger(AiStatusRedis::class.java)

    // 连接建在构造里；建不起来(本机没起 Redis)就置空，getRaw 会据此返回 Error（而不是 Missing）
    private val conn = runCatching { RedisClient.create("redis://$host:$port").connect() }
        .onFailure { log.warn("AiStatusRedis: 连不上 Redis($host:$port)，状态查询降级为 ERROR：${it.message}") }
        .getOrNull()

    fun getRaw(key: String): AiRedisResult {
        val c = conn ?: return AiRedisResult.Error(IllegalStateException("Redis 未连接($host:$port)"))
        return try {
            val v = c.sync().get(key)
            if (v == null) AiRedisResult.Missing else AiRedisResult.Value(v)
        } catch (e: Throwable) {
            log.warn("AiStatusRedis: get($key) 异常，降级为 ERROR：${e.message}")
            AiRedisResult.Error(e)
        }
    }

    // 写端：把状态 JSON 存进 kewu:ai:status:{sessionId}。
    // ttlSeconds>0 时顺带设过期（PROCESSING 用短 TTL 做“存活探针”：生成任务若中途挂了，
    // 超过 ttl 没再刷新就自动消失，轮询端会回落成 PENDING，不会永远卡在“处理中”）。
    // 返回 true=写入成功；Redis 没连上 / 写异常都返回 false，上层据此回写失败。
    fun setStatus(sessionId: String, json: String, ttlSeconds: Long): Boolean {
        val c = conn ?: return false
        return try {
            val key = "kewu:ai:status:$sessionId"
            c.sync().set(key, json)
            if (ttlSeconds > 0) c.sync().expire(key, ttlSeconds)
            true
        } catch (e: Throwable) {
            log.warn("AiStatusRedis: set($sessionId) 异常：${e.message}")
            false
        }
    }

    fun close() = runCatching { conn?.close() }
}
