package com.kewu.modules.analytics.routes

import com.kewu.modules.analytics.service.RadarService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

// 学情雷达接口：教师端“学生提问知识点热力”用。
// 注意：这个接口按需求返回【裸 JSON 数组】，不走 8080 统一的 {code,message,data} 壳——
// 前端直接拿到 [{keyword,count}] 画雷达/词云即可。
//
// Controller 只做两件事：收 courseId 参数、把 Service 算好的 JSON 文本原样回出去。
// 所有缓存/锁/查库/调 AI 的逻辑都在 RadarService，这里一个字都不碰。
fun Routing.analyticsRoutes(service: RadarService) {
    get("/api/teacher/analytics/radar") {
        val courseId = call.request.queryParameters["courseId"]
        if (courseId.isNullOrBlank()) {
            return@get call.respondText(
                "{\"error\":\"缺少必填参数 courseId\"}",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
        }
        // 最外层再兜一层：Service 内部已经容错了，这里防止任何意外（序列化等）把异常漏成 500
        val json = runCatching { service.getRadarJson(courseId) }.getOrDefault("[]")
        call.respondText(json, ContentType.Application.Json)
    }
}
