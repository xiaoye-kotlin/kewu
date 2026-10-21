package com.kewu.server.routes

import com.kewu.server.ServicesKey
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import java.time.Instant

// 健康检查，部署到华为云后探针就打这个接口；顺带暴露 Redis 状态，缓存挂了能一眼看出（服务本身仍是 up）
fun Routing.healthRoutes() {
    get("/health") {
        val redisUp = call.application.attributes[ServicesKey].redis.redisHealthy()
        call.respond(mapOf(
            "status" to "up",
            "redis" to if (redisUp) "up" else "down",
            "time" to Instant.now().toString()
        ))
    }
}
