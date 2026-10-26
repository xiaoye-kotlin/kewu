package com.kewu.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*
import org.slf4j.event.Level

// 每个请求一行日志（状态码 + 方法 + 路径 + 耗时），原 8081 就有，排查慢请求全靠它
fun Application.configureCallLogging() {
    install(CallLogging) {
        level = Level.INFO
        filter { it.request.path() != "/health" }
    }
}
