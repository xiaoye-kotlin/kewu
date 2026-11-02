package com.kewu.modules.bilibili

import com.kewu.models.ApiResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("BilibiliRoutes")

// 学科 -> B站搜索关键词。不在表里的学科直接用 subject 本身当关键词搜。
private val SUBJECT_KEYWORDS = mapOf(
    "初中数学" to "初中数学 系统课",
    "高中数学" to "高中数学 系统课",
    "大学数学" to "高等数学 考研 系统课",
    "初中物理" to "初中物理 系统课",
    "高中物理" to "高中物理 系统课",
    "初中语文" to "初中语文 系统课",
    "高中语文" to "高中语文 系统课",
    "初中英语" to "初中英语 系统课",
    "高中英语" to "高中英语 系统课"
)

// B站视频转课堂笔记路由：
//   POST /api/bilibili/note/generate  { "bvid": "BVxxxx" } -> 立即返回 {"status":"PROCESSING"}，流水线后台跑
//   GET  /api/bilibili/note/{bvid}                       -> 落库后返回笔记；未完成返回 Redis 状态（兼做进度轮询）
//   GET  /api/bilibili/videos?subject=初中数学           -> 返回 B站视频搜索列表 [{bvid,title,cover,videoUrl,audioUrl}]
//
// 解析前端入参用 Json.parseToJsonElement（项目未开 @Serializable 数据类，按统一约定走手解）。
fun Route.bilibiliRoutes(service: BilibiliNoteService, fetcher: BilibiliFetcher, scope: CoroutineScope) {
    // 触发生成：立刻返回，不阻塞前端
    post("/api/bilibili/note/generate") {
        val raw = runCatching { call.receiveText() }.getOrNull() ?: ""
        val el = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
        val bvid = (el?.jsonObject?.get("bvid") as? JsonPrimitive)?.content?.trim() ?: ""
        if (bvid.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiResponse<Unit>(1, "bvid 必填（传 B站视频号，如 BV1xx411c7mD）"))
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
            call.respond(HttpStatusCode.BadRequest, ApiResponse<Unit>(1, "bvid 必填"))
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
                "createdAt" to note.createdAt,
                "transcript" to note.transcript
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

    // 按学科获取 B站视频搜索列表（含 App 内播放所需的 DASH 直链）
    //   GET /api/bilibili/videos?subject=初中数学
    //   成功返回 JSON 数组 [{bvid,title,cover,videoUrl,audioUrl},...]；B站接口异常时降级为 [] + HTTP 200，绝不 500
    get("/api/bilibili/videos") {
        val subject = call.request.queryParameters["subject"]?.trim() ?: ""
        if (subject.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiResponse<Unit>(1, "subject 必填（如 初中数学 / 高中数学 / 大学数学）"))
            return@get
        }
        try {
            // subject -> B站搜索关键词；不在映射表里的学科直接用 subject 本身搜
            val keyword = SUBJECT_KEYWORDS[subject] ?: subject
            val items = fetcher.searchVideos(keyword)
            val result = buildJsonArray {
                items.forEach { it ->
                    add(buildJsonObject {
                        put("bvid", it.bvid)
                        put("title", it.title)
                        put("cover", it.cover)
                        put("videoUrl", it.videoUrl)
                        put("audioUrl", it.audioUrl)
                    })
                }
            }
            call.respondText(result.toString(), ContentType.Application.Json)
        } catch (e: Throwable) {
            // 任何 B站接口异常（412 风控 / 超时 / 解析失败）都降级为空数组 + 200，绝不 500
            log.warn("B站视频搜索失败 subject=$subject：${e.message}")
            call.respondText("[]", ContentType.Application.Json)
        }
    }
}
