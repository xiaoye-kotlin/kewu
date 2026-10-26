package com.kewu.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import java.util.Date

// 负责生成和校验登录用的 JWT 令牌
object JwtConfig {
    private val SECRET = System.getenv("JWT_SECRET") ?: "kewu-dev-secret-change-me"
    private const val ISSUER = "kewu"
    private const val AUDIENCE = "kewu-app"
    const val REALM = "kewu"
    const val NAME = "jwt"
    const val CLAIM_USER_ID = "userId"
    const val CLAIM_USERNAME = "username"
    private const val EXPIRE_MS = 7L * 24 * 60 * 60 * 1000 // 令牌有效期 7 天

    fun createToken(userId: String, username: String): String = JWT.create()
        .withAudience(AUDIENCE)
        .withIssuer(ISSUER)
        .withClaim(CLAIM_USER_ID, userId)
        .withClaim(CLAIM_USERNAME, username)
        .withExpiresAt(Date(System.currentTimeMillis() + EXPIRE_MS))
        .sign(Algorithm.HMAC256(SECRET))

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
                if (userId.isNotBlank() && username.isNotBlank()) JWTPrincipal(credential.payload) else null
            }
        }
    }
}
