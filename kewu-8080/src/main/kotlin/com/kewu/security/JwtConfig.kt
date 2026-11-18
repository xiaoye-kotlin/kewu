package com.kewu.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import com.kewu.models.ApiResponse
import com.kewu.modules.user.repository.UserSessionStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import java.util.Date

// 负责生成和校验登录用的 JWT 令牌
object JwtConfig {
    // 安全修复（2026-11-19）：不再回退到公开硬编码密钥（任何人都能据此伪造 ADMIN token 越权）。
    // 策略（保证现有部署不中断、token 跨重启有效）：
    //   1) 优先环境变量 JWT_SECRET（长度 >= 16）；
    //   2) 否则读写工作目录下的 .kewu_jwt_secret —— 首次用 SecureRandom 生成 32 字节随机密钥并持久化，
    //      之后重启直接复用。密钥是随机且不在代码/文档里公开的，攻击者无法伪造签名。
    private val SECRET: String = resolveSecret()

    private fun resolveSecret(): String {
        System.getenv("JWT_SECRET")?.takeIf { it.length >= 16 }?.let { return it }
        val file = java.io.File(System.getProperty("user.dir"), ".kewu_jwt_secret")
        return runCatching {
            if (file.exists() && file.length() > 0) {
                file.readText().trim().takeIf { it.length >= 16 }
            } else {
                val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                val key = java.util.Base64.getEncoder().encodeToString(bytes)
                file.writeText(key)
                runCatching { file.setReadable(false, false); file.setWritable(false, false) }
                println("🔐 已生成并持久化随机 JWT 密钥：${file.absolutePath}（请勿删除，否则所有用户需重新登录）")
                key
            }
        }.getOrNull() ?: error(
            "无法生成/读取 JWT 密钥，且未设置 JWT_SECRET 环境变量。" +
                "请设置 JWT_SECRET 或确保工作目录可写。"
        )
    }
    private const val ISSUER = "kewu"
    private const val AUDIENCE = "kewu-app"
    const val REALM = "kewu"
    const val NAME = "jwt"
    const val CLAIM_USER_ID = "userId"
    const val CLAIM_USERNAME = "username"
    const val CLAIM_SID = "sid"
    const val CLAIM_PLATFORM = "plat"
    /** 被同类设备挤下线时 401 响应体里的 message（客户端据此提示「账号已在其他设备登录」）。 */
    const val KICKED = "KICKED"
    private const val EXPIRE_MS = 7L * 24 * 60 * 60 * 1000 // 令牌有效期 7 天

    /** [sid]/[platform] 为空 = 旧版客户端登录，不参与单设备互斥。 */
    fun createToken(userId: String, username: String, sid: String? = null, platform: String? = null): String = JWT.create()
        .withAudience(AUDIENCE)
        .withIssuer(ISSUER)
        .withClaim(CLAIM_USER_ID, userId)
        .withClaim(CLAIM_USERNAME, username)
        .apply { if (sid != null && platform != null) withClaim(CLAIM_SID, sid).withClaim(CLAIM_PLATFORM, platform) }
        .withExpiresAt(Date(System.currentTimeMillis() + EXPIRE_MS))
        .sign(Algorithm.HMAC256(SECRET))

    /** token 签名有效、但 sid 已被同类设备的新登录顶替。 */
    fun isKicked(token: String): Boolean = runCatching {
        val d = JWT.require(Algorithm.HMAC256(SECRET)).withAudience(AUDIENCE).withIssuer(ISSUER).build().verify(token)
        !UserSessionStore.isValid(d.getClaim(CLAIM_USER_ID).asString(), d.getClaim(CLAIM_PLATFORM).asString(), d.getClaim(CLAIM_SID).asString())
    }.getOrDefault(false)

    // 仅校验签名/过期，返回 userId；失败返回 null。总控端用 Bearer Token 鉴权（token 里不含 role，需回查 users 表）
    fun verifyToken(token: String): String? = runCatching {
        val decoded = JWT.require(Algorithm.HMAC256(SECRET))
            .withAudience(AUDIENCE)
            .withIssuer(ISSUER)
            .build()
            .verify(token)
        val userId = decoded.getClaim(CLAIM_USER_ID).asString()
        // 在线状态（§83）：总控端等走手动 verifyToken 的调用也要记录活跃，
        // 否则只走 JWT 插件的端用户才有在线状态。
        if (userId.isNotBlank()) com.kewu.modules.user.repository.UserPresence.touch(userId)
        userId.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun configure(config: AuthenticationConfig) {
        config.jwt(NAME) {
            realm = REALM
            verifier(
                JWT.require(Algorithm.HMAC256(SECRET))
                    .withAudience(AUDIENCE)
                    .withIssuer(ISSUER)
                    .build()
            )
            validate { credential ->
                val userId = credential.payload.getClaim(CLAIM_USER_ID).asString()
                val username = credential.payload.getClaim(CLAIM_USERNAME).asString()
                val sid = credential.payload.getClaim(CLAIM_SID).asString()
                val plat = credential.payload.getClaim(CLAIM_PLATFORM).asString()
                if (userId.isNotBlank() && username.isNotBlank() && UserSessionStore.isValid(userId, plat, sid)) {
                    // 在线状态（§83）：任何受认证请求都刷新该用户的「最后活跃」，无需各端改心跳。
                    com.kewu.modules.user.repository.UserPresence.touch(userId)
                    JWTPrincipal(credential.payload)
                } else null
            }
            challenge { _, _ ->
                val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim().orEmpty()
                val msg = if (token.isNotEmpty() && isKicked(token)) KICKED else "登录已过期"
                call.respond(HttpStatusCode.Unauthorized, ApiResponse<String>(401, msg))
            }
        }
    }
}
