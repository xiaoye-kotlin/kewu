package com.kewu.modules.bilibili

import com.kewu.models.ApiResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

// B站视频转课堂笔记路由：
//   POST /api/bilibili/note/generate  { "bvid": "BVxxxx" } -> 立即返回 {"status":"PROCESSING"}，流水线后台跑
//   GET  /api/bilibili/note/{bvid}                       -> 落库后返回笔记；未完成返回 Redis 状态（兼做进度轮询）
//
// 解析前端入参用 Json.parseToJsonElement（项目未开 @Serializable 数据类，按统一约定走手解）。
fun Route.bilibiliRoutes(service: BilibiliNoteService, scope: CoroutineScope) {
    // 触发生成：立刻返回，不阻塞前端
    post("/api/bilibili/note/generate") {
        val raw = runCatching { call.receiveText() }.getOrNull() ?: ""
        val el = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
        val bvid = (el?.jsonObject?.get("bvid") as? JsonPrimitive)?.content?.trim() ?: ""
        if (bvid.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiResponse<Any>(1, "bvid 必填（传 B站视频号，如 BV1xx411c7mD）"))
            return@post
        }
        // 丢到后台协程执行整条流水线，接口立即返回 PROCESSING
        scope.launch {
            runCatching { service.generate(bvid) }.onFailure { e -> println("BILIBILI-ERROR: $e") }
        }
        call.respond(ApiResponse(0, "已开始生成，请稍后用 GET 接口轮询结果", mapOf("status" to "PROCESSING")))
    }

    // 查询笔记 / 进度轮询：同一个接口既返回成品笔记，也返回处理中/失败状态
    get("/api/bilibili/note/{bvid}") {
        val bvid = call.parameters["bvid"]?.trim() ?: ""
        if (bvid.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiResponse<Any>(1, "bvid 必填"))
            return@get
        }
        // 已落库：直接返回笔记
        val note = service.getNoteByBvid(bvid)
        if (note != null) {
            call.respond(ApiResponse(0, "ok", mapOf(
                "status" to "FINISHED",
                "bvid" to note.bvid,
                "title" to note.title,
                "content" to note.content,
                "createdAt" to note.createdAt
            )))
            return@get
        }
        // 未落库：读 Redis 里的任务状态（PROCESSING / FAILED+reason），没有就当 PENDING
        val statusJson = service.getStatus(bvid)
        val statusMap = if (statusJson != null) {
            runCatching {
                Json.parseToJsonElement(statusJson).jsonObject.mapValues { (_, v) ->
                    (v as? JsonPrimitive)?.content ?: v.toString()
                }
            }.getOrNull() ?: mapOf("status" to "PROCESSING")
        } else {
            mapOf("status" to "PENDING")
        }
        call.respond(ApiResponse(0, "ok", statusMap))
    }
}
