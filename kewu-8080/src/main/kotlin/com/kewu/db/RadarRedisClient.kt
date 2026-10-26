package com.kewu.db

import io.lettuce.core.RedisClient
import io.lettuce.core.SetArgs
import org.slf4j.LoggerFactory

// 学情雷达专用 Redis 客户端：只封装缓存读写 + 一把分布式锁(SETNX)。
// 设计原则：任何一步异常都吞掉并降级（返回 null/false），绝不把异常抛给上层——
// 缓存挂了只是少一层加速，不影响接口返回数据。
class RadarRedisClient(
    private val host: String = System.getenv("KEWU_REDIS_HOST") ?: "127.0.0.1",
    private val port: Int = (System.getenv("KEWU_REDIS_PORT")?.toIntOrNull() ?: 6379)
) {
    private val log = LoggerFactory.getLogger(RadarRedisClient::class.java)

    // 连接建在构造里；建不起来(比如本机没起 Redis)就置空，所有操作直接降级
    private val client: RedisClient? = runCatching {
        RedisClient.create("redis://$host:$port")
    }.onFailure { log.warn("RadarRedis: 连不上 Redis($host:$port)，缓存/锁功能降级：${it.message}") }
        .getOrNull()

    private val conn = runCatching { client?.connect() }
        .onFailure { log.warn("RadarRedis: connect 失败，降级：${it.message}") }
        .getOrNull()

    // 读缓存；异常或无值都返回 null（上层据此走“未命中”流程）
    fun get(key: String): String? = try {
        conn?.sync()?.get(key)
    } catch (e: Throwable) {
        log.warn("RadarRedis: get($key) 异常，降级：${e.message}")
        null
    }

    // 写缓存并设过期；失败返回 false，但上层仍然返回数据（缓存只是加速）
    fun setex(key: String, ttlSeconds: Long, value: String): Boolean = try {
        conn?.sync()?.setex(key, ttlSeconds, value)
        true
    } catch (e: Throwable) {
        log.warn("RadarRedis: setex($key) 异常，降级：${e.message}")
        false
    }

    // 分布式锁：SET key value NX EX ttl。返回 true 表示“抢到锁”，false 表示锁已被别人持有或异常
    // 用 NX(只在不存在时写入) 保证同一时刻只有一个请求去打 AI，EX 防止持锁进程崩溃后锁永不释放
    fun tryLock(key: String, ttlSeconds: Long): Boolean = try {
        val ok = conn?.sync()?.set(key, "1", SetArgs().nx().ex(ttlSeconds))
        ok != null // Lettuce 返回 "OK" 表示写入成功(抢到)，null 表示 key 已存在(没抢到)
    } catch (e: Throwable) {
        log.warn("RadarRedis: tryLock($key) 异常，降级(视为没抢到锁)：${e.message}")
        false
    }

    // 释放锁；用不到的值校验，简单 del 即可（锁本身只防并发重算，误删也只是下次多算一次）
    fun releaseLock(key: String): Boolean = del(key)

    // 通用删除（清坏缓存 key 也用它）
    fun del(key: String): Boolean = try {
        conn?.sync()?.del(key)
        true
    } catch (e: Throwable) {
        log.warn("RadarRedis: del($key) 异常：${e.message}")
        false
    }

    fun close() = runCatching { conn?.close(); client?.shutdown() }
}
