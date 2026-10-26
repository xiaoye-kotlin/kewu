package com.kewu.plugins

import io.ktor.server.application.*
import io.ktor.server.auth.*
import com.kewu.security.JwtConfig

// 把 JWT 登录校验装到 Ktor 上
fun Application.configureAuthentication() {
    install(Authentication) {
        JwtConfig.configure(this)
    }
}
