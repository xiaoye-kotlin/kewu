package com.kewu.modules.aistatus.routes

import com.kewu.modules.aistatus.service.AiStatusService
import io.ktor.http.ContentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

// AI 纪要生成状态轮询接口（裸 JSON，前端驱动进度条用）。
// GET  /api/teacher/session/{sessionId}/ai-status  —— 读（轮询端）
// POST /api/teacher/session/{sessionId}/ai-status  —— 写（AI 生成任务的写端）
fun Routing.aiStatusRoutes(service: AiStatusService) {
    get("/api/teacher/session/{sessionId}/ai-status") {
        val sessionId = call.parameters["sessionId"]
        // 最外层兜底：Service 内部已层层容错，这里再兜一层，任何意外都绝不漏成 500
        val json = runCatching { service.getStatusJson(sessionId) }
            .getOrDefault(AiStatusService.ERROR_JSON)
        call.respondText(json, ContentType.Application.Json)
    }

    post("/api/teacher/session/{sessionId}/ai-status") {
        val sessionId = call.parameters["sessionId"]
        // 读请求体；读不到也兜底成空串，交给 Service 做校验（不会因空 body 抛 500）
        val body = runCatching { call.receiveText() }.getOrNull() ?: ""
        val json = runCatching { service.updateStatus(sessionId, body) }
            .getOrDefault("""{"ok":false,"message":"状态写入异常"}""")
        call.respondText(json, ContentType.Application.Json)
    }
}
