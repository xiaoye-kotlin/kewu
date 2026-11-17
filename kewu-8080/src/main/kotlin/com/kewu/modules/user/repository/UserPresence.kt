package com.kewu.modules.user.repository

import java.util.concurrent.ConcurrentHashMap

/**
 * 用户在线状态（2026-11-17 §83）。
 *
 * 不要求各端改造上报心跳：直接在 JWT 鉴权通过时记录「最近一次受认证请求时间」，
 * 只要用户还在用 App（浏览课程、交作业、录音等）就会不断刷新。
 *
 * 判定：最后活跃时间在 [WINDOW_MS] 内视为在线。
 * 存储：进程内 ConcurrentHashMap（后端单实例且总控端与后端同机，重启后自然清零可接受）。
 * 写入节流：同一用户 [TOUCH_INTERVAL_MS] 内重复请求不刷新，避免高并发下无谓写map。
 */
object UserPresence {
    /** 在线窗口：3 分钟无任何受认证请求即视为离线。 */
    const val WINDOW_MS: Long = 3 * 60 * 1000
    private const val TOUCH_INTERVAL_MS: Long = 20 * 1000

    private val lastSeen = ConcurrentHashMap<String, Long>()

    /** 鉴权通过时调用；内部节流，开销可忽略。 */
    fun touch(userId: String) {
        if (userId.isBlank()) return
        val now = System.currentTimeMillis()
        val prev = lastSeen[userId]
        if (prev == null || now - prev >= TOUCH_INTERVAL_MS) lastSeen[userId] = now
    }

    fun isOnline(userId: String): Boolean {
        val t = lastSeen[userId] ?: return false
        return System.currentTimeMillis() - t <= WINDOW_MS
    }

    /** 最近活跃时间（epoch ms）；从未活跃返回 null。 */
    fun lastSeenAt(userId: String): Long? = lastSeen[userId]

    fun onlineCount(): Int {
        val now = System.currentTimeMillis()
        return lastSeen.count { now - it.value <= WINDOW_MS }
    }

    /** 清理过期记录，避免长期运行内存增长（由定时任务或接口顺带调用）。 */
    fun prune() {
        val now = System.currentTimeMillis()
        lastSeen.entries.removeIf { now - it.value > WINDOW_MS * 4 }
    }
}
