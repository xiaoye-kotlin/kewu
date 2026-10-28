package com.kewu.recserver.routes

import com.kewu.recserver.RecServicesKey
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.Instant

// 健康检查，部署到华为云后探针就打这个接口；顺带暴露 Redis 状态，缓存挂了能一眼看出（服务本身仍是 up）
fun Route.healthRoutes() {
    get("/health") {
        val redisUp = call.application.attributes[RecServicesKey].redis.redisHealthy()
        call.respond(mapOf(
            "status" to "up",
            "redis" to if (redisUp) "up" else "down",
            "time" to Instant.now().toString()
        ))
    }
}
