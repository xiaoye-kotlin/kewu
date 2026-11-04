package com.kewu.plugins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*

// 让前端(不同端口/端)能跨域调接口, 测试阶段先放开所有来源
fun Application.configureCors() {
    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        // AI 语音对话把字幕放在响应头里，浏览器端要能读到（App 原生请求不受影响）
        listOf("X-AI-Text", "X-AI-Text-Encoding", "X-AI-User-Text", "X-AI-User-Text-Encoding",
            "X-AI-Stage", "X-AI-Fallback").forEach { exposeHeader(it) }
    }
}
