package com.kewu.modules.audio

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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("AudioFusionRoutes")

// 多路录音并发转写 + 文本大融合（2026-11-03）：
//   POST /api/audio/transcribe-and-summarize  [{obsUrl,role},...] -> 立即返回 {"status":"PROCESSING","taskId":"xxx"}，流水线后台跑
//   GET  /api/audio/fusion/{taskId}                              -> 落库后返回终极笔记；未完成返回 Redis 进度（兼做轮询）
//
// 解析前端入参用 Json.parseToJsonElement（项目未开 @Serializable 数据类，按统一约定走手解）。
fun Route.audioFusionRoutes(service: TranscribeFusionService, scope: CoroutineScope) {
    // 受理：立刻返回 taskId，不阻塞前端
    post("/api/audio/transcribe-and-summarize") {
        val raw = runCatching { call.receiveText() }.getOrNull() ?: ""
        val sources = parseSources(runCatching { Json.parseToJsonElement(raw) }.getOrNull())
        if (sources.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiResponse<Unit>(1, "请提供至少一个有效的录音：JSON 数组，每项含 obsUrl（OBS 音频地址）与 role（如 teacher/student）"))
            return@post
        }
        val taskId = service.start(sources)
        // 丢到后台协程执行整条流水线，接口立即返回 PROCESSING
        scope.launch {
            runCatching { service.run(taskId, sources) }.onFailure { e -> log.error("FUSION-ERROR: taskId=$taskId $e") }
        }
        call.respond(ApiResponse(0, "已开始处理，请稍后用 GET 接口轮询结果", mapOf("status" to "PROCESSING", "taskId" to taskId)))
    }

    // 查询笔记 / 进度轮询：落库后返回终极笔记；否则返回 Redis 里的阶段进度
    get("/api/audio/fusion/{taskId}") {
        val taskId = call.parameters["taskId"]?.trim() ?: ""
        if (taskId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiResponse<Unit>(1, "taskId 必填"))
            return@get
        }
        // 已落库：直接返回笔记
        val note = service.getNoteByTaskId(taskId)
        if (note != null) {
            call.respond(ApiResponse(0, "ok", mapOf(
                "status" to "FINISHED",
                "taskId" to note.taskId,
                "sourceCount" to note.sourceCount.toString(),
                "content" to note.content,
                "transcript" to note.transcript,
                "createdAt" to note.createdAt
            )))
            return@get
        }
        // 未落库：读 Redis 里的任务状态（PROCESSING/阶段 + FAILED+reason），没有就当 PENDING
        val statusJson = service.getStatus(taskId)
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

// 把请求体 JSON 数组解析成录音来源列表：每项取 obsUrl（兼容 obs_url）+ role；缺 obsUrl 的整条跳过
private fun parseSources(el: Any?): List<TranscribeFusionService.AudioSource> {
    val arr = (el as? JsonArray) ?: return emptyList()
    return arr.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val obsUrl = ((obj["obsUrl"] as? JsonPrimitive)?.content?.trim())
            ?: (obj["obs_url"] as? JsonPrimitive)?.content?.trim()
        if (obsUrl.isNullOrBlank()) return@mapNotNull null
        val role = (obj["role"] as? JsonPrimitive)?.content?.trim().orEmpty()
        TranscribeFusionService.AudioSource(obsUrl, role)
    }
}
