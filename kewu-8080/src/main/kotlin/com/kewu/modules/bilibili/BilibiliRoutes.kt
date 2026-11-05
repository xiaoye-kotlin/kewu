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
//   GET  /api/bilibili/videos?subject=初中数学           -> 返回 B站视频搜索列表 [{bvid,title,cover,videoUrl,audioUrl,duration}]
//   GET  /api/bilibili/danmaku?bvid=…&segmentIndex=1    -> 该 6 分钟分段的弹幕 [{progress,mode,fontsize,color,content}]
//
// 解析前端入参用 Json.parseToJsonElement（项目未开 @Serializable 数据类，按统一约定走手解）。
fun Route.bilibiliRoutes(service: BilibiliNoteService, fetcher: BilibiliFetcher, scope: CoroutineScope) {
    // 路由组只在服务启动时注册一次，等于单例：cid 缓存 / 分段缓存都活在整个进程里
    val danmakuService = BilibiliDanmakuService(fetcher)
    // 触发生成：立刻返回，不阻塞前端
    post("/api/bilibili/note/generate") {
        val raw = runCatching { call.receiveText() }.getOrNull() ?: ""
        val el = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
        val bvid = (el?.jsonObject?.get("bvid") as? JsonPrimitive)?.content?.trim() ?: ""
        if (bvid.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiResponse<Unit>(1, "bvid 必填（传 B站视频号，如 BV1xx411c7mD）"))
            return@post
        }
        // 丢到后台协程执行整条流水线，接口立即返回 PROCESSING；已在跑就不重复起（FAILED / 已完成都可重新生成）
        if (service.tryStart(bvid)) {
            scope.launch {
                runCatching { service.generate(bvid) }.onFailure { e -> println("BILIBILI-ERROR: $e") }
            }
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

    // 按学科获取 B站视频搜索列表（含 App 内播放所需的 DASH 直链接口）
    //   GET /api/bilibili/videos?subject=初中数学[&withUrls=false]
    //   withUrls 默认 true（兼容旧客户端仍返回直链）；传 false 时视频/音频直链为空串，
    //   只回 bvid/title/cover/duration 与合集元数据，推荐页从 ~8s 降到 ~0.6s，点播走 /api/bilibili/play。
    //   成功返回 JSON 数组 [{bvid,title,cover,videoUrl,audioUrl,duration,seasonId,seasonTitle,seasonCover,seasonCount,pageCount,episodes[]}]
    //   属于 UGC 合集 / 多分P 的视频带 episodes 分集元数据（客户端发合集卡、点进去看全部分集）；B站接口异常时降级为 [] + HTTP 200，绝不 500
    get("/api/bilibili/videos") {
        val subject = call.request.queryParameters["subject"]?.trim() ?: ""
        if (subject.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiResponse<Unit>(1, "subject 必填（如 初中数学 / 高中数学 / 大学数学）"))
            return@get
        }
        val withUrls = call.request.queryParameters["withUrls"]?.equals("true", ignoreCase = true) ?: true
        try {
            // subject -> B站搜索关键词；不在映射表里的学科直接用 subject 本身搜
            val keyword = SUBJECT_KEYWORDS[subject] ?: subject
            // 不带直链时只打 view（12s 封顶），带直链要再打 playurl（30s 封顶）；超时都降级空数组
            val items = kotlinx.coroutines.withTimeoutOrNull(if (withUrls) 30_000L else 12_000L) {
                fetcher.searchVideos(keyword, withUrls)
            }.orEmpty()
            val result = buildJsonArray {
                items.forEach { it ->
                    add(buildJsonObject {
                        put("bvid", it.bvid)
                        put("title", it.title)
                        put("cover", it.cover)
                        put("videoUrl", it.videoUrl)
                        put("audioUrl", it.audioUrl)
                        put("duration", it.duration)
                        put("seasonId", it.seasonId)
                        put("seasonTitle", it.seasonTitle)
                        put("seasonCover", it.seasonCover)
                        put("seasonCount", it.seasonCount)
                        put("pageCount", it.pageCount)
                        put("episodes", buildJsonArray {
                            it.episodes.forEach { ep ->
                                add(buildJsonObject {
                                    put("bvid", ep.bvid)
                                    put("cid", ep.cid)
                                    put("title", ep.title)
                                    put("duration", ep.duration)
                                })
                            }
                        })
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

    // 单集播放直链（2026-11-04）：合集分集 / 某分P 点击时现取，不依赖搜索结果里可能已过期的直链。
    //   GET /api/bilibili/play?bvid=BVxxx&cid=123（cid 可省略 = 第一集/第一P）
    //   成功 {code:0,data:{bvid,cid,title,cover,videoUrl,audioUrl}}；失败 {code:1,message:原因}（HTTP 200）
    get("/api/bilibili/play") {
        val bvid = call.request.queryParameters["bvid"]?.trim() ?: ""
        val cid = call.request.queryParameters["cid"]?.toLongOrNull() ?: 0L
        if (bvid.isBlank()) {
            call.respond(ApiResponse<Unit>(1, "bvid 必填"))
            return@get
        }
        try {
            val info = fetcher.viewInfo(bvid)
            val useCid = if (cid > 0) cid else info.cid
            val urls = fetcher.dashUrlsFor(bvid, useCid)
            // 2026-11-04 修复：不能 respond(ApiResponse(0,"ok",mapOf(...))) —— map 里混 String/Long 会擦成
            // Map<String,Any>，运行期找不到序列化器抛异常（线上表现为点击分集永远失败）。显式 buildJsonObject。
            call.respondText(buildJsonObject {
                put("code", 0)
                put("message", "ok")
                put("data", buildJsonObject {
                    put("bvid", bvid)
                    put("cid", useCid)
                    put("title", info.title)
                    put("cover", info.cover)
                    put("videoUrl", urls.first)
                    put("audioUrl", urls.second)
                })
            }.toString(), ContentType.Application.Json)
        } catch (e: Throwable) {
            log.warn("B站单集直链获取失败 bvid=$bvid cid=$cid：${e.message}")
            call.respond(ApiResponse<Unit>(1, "取播放直链失败：${e.message}"))
        }
    }

    // 视频弹幕（2026-11-05）：按 6 分钟分段拉取，长视频不用一次把整部弹幕灌进内存。
    //   GET /api/bilibili/danmaku?bvid=BVxxx&segmentIndex=1[&cid=123]
    //   segmentIndex = currentTimeMs / 360000 + 1（从 1 开始）；cid 省略 = 第一P/第一集，分P 由前端传对应 cid。
    //   返回裸 JSON 数组 [{progress(ms),mode,fontsize,color(0xRRGGBB),content}]；
    //   B站风控 / 超时 / protobuf 解析失败一律降级为 [] + HTTP 200，绝不 500。
    get("/api/bilibili/danmaku") {
        val bvid = call.request.queryParameters["bvid"]?.trim().orEmpty()
        val segmentIndex = call.request.queryParameters["segmentIndex"]?.toIntOrNull() ?: 1
        val cid = call.request.queryParameters["cid"]?.toLongOrNull() ?: 0L
        when {
            bvid.isBlank() -> {
                call.respond(HttpStatusCode.BadRequest, ApiResponse<Unit>(1, "bvid 必填"))
                return@get
            }
            segmentIndex !in 1..10_000 -> {
                call.respond(HttpStatusCode.BadRequest, ApiResponse<Unit>(1, "segmentIndex 只能是 1..10000（每段 6 分钟）"))
                return@get
            }
        }
        try {
            val arr = danmakuService.segment(bvid, cid, segmentIndex)
            call.respondText(arr.toString(), ContentType.Application.Json)
        } catch (e: Throwable) {
            // 服务内部已经兜了一层，这里再兜一次确保连序列化异常也不会变成 500
            log.warn("B站弹幕段异常 bvid=$bvid seg=$segmentIndex：${e.message}")
            call.respondText("[]", ContentType.Application.Json)
        }
    }
}
