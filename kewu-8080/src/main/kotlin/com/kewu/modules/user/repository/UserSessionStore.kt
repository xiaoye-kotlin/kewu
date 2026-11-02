package com.kewu.modules.user.repository

import com.kewu.db.UserSessions
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 单设备登录（2026-11-02）：(userId, 平台类) → 当前有效 sid。
 * 登录时换新 sid，旧 token 里的 sid 对不上就判为「被挤下线」。内存缓存 + MySQL 落库（重启不丢）。
 */
object UserSessionStore {
    private val cache = ConcurrentHashMap<String, String>()

    /** 客户端上报的平台归一成三类；认不出的按 android 处理（手机端是大头）。 */
    fun normalize(platform: String?): String = when (platform?.lowercase()?.trim()) {
        "desktop", "windows", "mac", "linux", "pc" -> "desktop"
        "ios", "iphone", "ipad" -> "ios"
        else -> "android"
    }

    fun newSession(userId: String, platform: String): String {
        val sid = UUID.randomUUID().toString()
        transaction {
            UserSessions.deleteWhere { (UserSessions.userId eq userId) and (UserSessions.platform eq platform) }
            UserSessions.insert {
                it[UserSessions.userId] = userId
                it[UserSessions.platform] = platform
                it[UserSessions.sid] = sid
                it[updatedAt] = LocalDateTime.now().toString().take(19)
            }
        }
        cache["$userId|$platform"] = sid
        return sid
    }

    /** 当前有效 sid；查库失败返回 null（放行，宁可不踢也别误踢）。 */
    fun current(userId: String, platform: String): String? = cache["$userId|$platform"] ?: runCatching {
        transaction {
            UserSessions.selectAll().where { (UserSessions.userId eq userId) and (UserSessions.platform eq platform) }
                .firstOrNull()?.get(UserSessions.sid)
        }
    }.getOrNull()?.also { cache["$userId|$platform"] = it }

    /** token 里的 sid 是否仍有效。没有 sid（旧版客户端 / 旧 token）一律放行。 */
    fun isValid(userId: String, platform: String?, sid: String?): Boolean {
        if (sid.isNullOrBlank() || platform.isNullOrBlank()) return true
        val cur = current(userId, platform) ?: return true
        return cur == sid
    }
}
