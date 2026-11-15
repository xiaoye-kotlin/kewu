package com.kewu.modules.focus

import com.kewu.infra.SecretStore
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import org.slf4j.LoggerFactory

// 专注状态的 Redis 层。三条铁律：
// 1) 任何一步 Redis 异常都吞掉并降级到进程内存镜像，start/finish 绝不报 500；
// 2) finish/cancel 用「读 + 原子 del」抢会话，狂点也只有一个请求拿得到，其余静默成功；
// 3) 这里只存服务端给的时间戳，不自己算业务时长。
//
// 内存镜像是刻意的：Redis 挂掉时这一轮专注不会凭空消失，重启进程才会丢。
class FocusRedis {
    companion object {
        const val ACTIVE_TTL_SECONDS = 2L * 60 * 60   // 进行中的专注最多挂 2 小时
        const val REST_TTL_SECONDS = 300L             // 完成后的 5 分钟休息倒计时
        private const val RECONNECT_BACKOFF_MS = 30_000L

        private fun activeKey(userId: String) = "kewu:focus:active:$userId"
        private fun restKey(userId: String) = "kewu:focus:rest:$userId"
    }

    private val log = LoggerFactory.getLogger(FocusRedis::class.java)
    private val host = SecretStore.get("KEWU_REDIS_HOST") ?: "127.0.0.1"
    private val port = SecretStore.get("KEWU_REDIS_PORT")?.toIntOrNull() ?: 6379
    private val password = SecretStore.get("REDIS_PASSWORD")

    @Volatile
    private var conn: StatefulRedisConnection<String, String>? = null

    @Volatile
    private var lastFailAt = 0L

    // Redis 挂掉时顶上的内存镜像：active 存会话，rest 存「休息到期的服务端毫秒时间戳」
    private val localActive = java.util.concurrent.ConcurrentHashMap<String, ActiveFocus>()
    private val localRestExpireAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    init {
        conn = connect()
    }

    private fun connect(): StatefulRedisConnection<String, String>? {
        lastFailAt = System.currentTimeMillis()
        return runCatching {
            val builder = RedisURI.builder().withHost(host).withPort(port)
            if (!password.isNullOrBlank()) builder.withPassword(password.toCharArray())
            RedisClient.create(builder.build()).connect()
        }.onFailure {
            log.warn("FocusRedis: 连不上 Redis($host:$port)，专注状态降级到进程内存：${it.message}")
        }.getOrNull()
    }

    // 断线后冷却 30 秒再试一次，Redis 恢复了服务能自己活过来，不用重启后端
    @Synchronized
    private fun commands(): RedisCommands<String, String>? {
        if (conn == null && System.currentTimeMillis() - lastFailAt >= RECONNECT_BACKOFF_MS) {
            conn = connect()
        }
        return runCatching { conn?.sync() }.getOrNull()
    }

    private fun markBroken(t: Throwable) {
        log.warn("FocusRedis: Redis 操作异常，暂降级到内存：${t.message}")
        runCatching { conn?.close() }
        conn = null
        lastFailAt = System.currentTimeMillis()
    }

    // 开始专注：Hash 写 startTime/plannedMinutes/taskId/distractionCount，TTL 2 小时。
    // 不动 rest key —— 要不要结束休息由 /skip-rest 说了算，别在这里替前端做决定。
    // 返回 Redis 是否真的写成功（false 只表示降级，不是失败）。
    fun start(userId: String, session: ActiveFocus): Boolean {
        localActive[userId] = session
        val c = commands() ?: return false
        return try {
            val key = activeKey(userId)
            c.hset(
                key,
                mapOf(
                    "startTime" to session.startTime.toString(),
                    "plannedMinutes" to session.plannedMinutes.toString(),
                    "taskId" to (session.taskId ?: ""),
                    "distractionCount" to session.distractionCount.toString(),
                )
            )
            c.expire(key, ACTIVE_TTL_SECONDS)
            true
        } catch (t: Throwable) {
            markBroken(t)
            false
        }
    }

    // 上报分心：先看 active key 在不在，在才 HINCRBY +1。没有进行中的专注就返回 null，静默忽略。
    fun distraction(userId: String): Int? {
        val redisCount = commands()?.let { c ->
            try {
                val key = activeKey(userId)
                if (c.exists(key) > 0L) c.hincrby(key, "distractionCount", 1L).toInt() else null
            } catch (t: Throwable) {
                markBroken(t)
                null
            }
        }
        val localCount = localActive.computeIfPresent(userId) { _, s ->
            s.copy(distractionCount = s.distractionCount + 1)
        }?.distractionCount
        return redisCount ?: localCount
    }

    // 原子「取走」当前专注会话：谁先把 active key 删掉谁拿得到，其余并发请求拿到 null（幂等）。
    // 这一步是 finish/cancel 防重复落库的关键，别改成先读后删的两段式。
    fun consumeActive(userId: String): ActiveFocus? {
        val fromRedis = commands()?.let { c ->
            try {
                val key = activeKey(userId)
                val fields = c.hgetall(key)
                if (fields.isNotEmpty() && c.del(key) > 0L) fields.toActiveOrNull() else null
            } catch (t: Throwable) {
                markBroken(t)
                null
            }
        }
        val fromLocal = localActive.remove(userId)
        return fromRedis ?: fromLocal
    }

    // 只看不动，供 /status 用
    fun active(userId: String): ActiveFocus? {
        commands()?.let { c ->
            try {
                val fields = c.hgetall(activeKey(userId))
                if (fields.isNotEmpty()) fields.toActiveOrNull()?.let { return it }
            } catch (t: Throwable) {
                markBroken(t)
            }
        }
        return localActive[userId]
    }

    // 完成专注后写休息倒计时：String key = "1"，TTL 300 秒。cancel 绝不能调这个方法。
    fun setRest(userId: String): Boolean {
        localRestExpireAt[userId] = System.currentTimeMillis() + REST_TTL_SECONDS * 1000L
        val c = commands() ?: return false
        return try {
            c.setex(restKey(userId), REST_TTL_SECONDS, "1")
            true
        } catch (t: Throwable) {
            markBroken(t)
            false
        }
    }

    // 跳过休息：key 不存在也当成功，重复点不报错
    fun skipRest(userId: String): Boolean {
        localRestExpireAt.remove(userId)
        val c = commands() ?: return false
        return try {
            c.del(restKey(userId))
            true
        } catch (t: Throwable) {
            markBroken(t)
            false
        }
    }

    fun restRemainingSeconds(userId: String): Long? {
        commands()?.let { c ->
            try {
                val ttl = c.ttl(restKey(userId))
                if (ttl > 0) return ttl
            } catch (t: Throwable) {
                markBroken(t)
            }
        }
        val expireAt = localRestExpireAt[userId] ?: return null
        val left = (expireAt - System.currentTimeMillis()) / 1000L
        return if (left > 0) left else {
            localRestExpireAt.remove(userId)
            null
        }
    }

    private fun Map<String, String>.toActiveOrNull(): ActiveFocus? {
        val start = this["startTime"]?.toLongOrNull() ?: return null
        return ActiveFocus(
            taskId = this["taskId"]?.takeIf { it.isNotEmpty() },
            startTime = start,
            plannedMinutes = this["plannedMinutes"]?.toIntOrNull() ?: 25,
            distractionCount = this["distractionCount"]?.toIntOrNull() ?: 0,
        )
    }

    fun close() = runCatching { conn?.close() }
}
