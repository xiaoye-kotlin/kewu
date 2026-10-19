package com.kewu.server.routes

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import java.time.Instant

// 健康检查，部署到华为云后探针就打这个接口
fun Routing.healthRoutes() {
    get("/health") {
        call.respond(mapOf("status" to "up", "time" to Instant.now().toString()))
    }
}
