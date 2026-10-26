package com.kewu.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

// 让接口能自动收 JSON、回 JSON
fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
            // 默认值的字段也要输出(比如签到的 mode/state), 不然前端拿不到字段会解析报错
            encodeDefaults = true
        })
    }
}
