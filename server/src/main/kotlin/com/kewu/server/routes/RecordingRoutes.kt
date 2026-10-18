package com.kewu.server.routes

import com.kewu.server.ServicesKey
import com.kewu.server.model.FuseRequest
import com.kewu.server.model.RecordingMeta
import com.kewu.server.model.RecordingUploadResult
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.forEachPart
import io.ktor.http.content.PartData
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import io.ktor.utils.io.core.readBytes
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// 录音上传 + 融合：多端把音频传上来，存好、算电平/信噪比、打分，教师端优先择优，异常自动回退学生端
fun Routing.recordingRoutes() {
    val services = application.attributes[ServicesKey]

    post("/api/recordings/upload") {
        try {
            val multipart = call.receiveMultipart()
            var classId = ""; var sessionId = ""; var deviceId = ""; var role = "student"
            var format: String? = null; var sampleRate: Int? = null; var durationSec: Double? = null
            var avgLevelDb: Double? = null
            var bytes: ByteArray? = null; var ext = "bin"
            var readError: String? = null

            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> when (part.name) {
                        "classId" -> classId = part.value
                        "sessionId" -> sessionId = part.value
                        "deviceId" -> deviceId = part.value
                        "role" -> if (part.value.isNotBlank()) role = part.value
                        "sampleRate" -> part.value.toIntOrNull()?.let { sampleRate = it }
                        "durationSec" -> part.value.toDoubleOrNull()?.let { durationSec = it }
                        "avgLevel", "avgLevelDb" -> part.value.toDoubleOrNull()?.let { avgLevelDb = it }
                        "format" -> if (format == null) format = part.value
                    }
                    is PartData.FileItem -> if (part.name == "audio") {
                        try {
                            val input = part.provider()
                            bytes = input.readBytes()
                        } catch (t: Throwable) {
                            readError = t.message ?: t.toString()
                        }
                        val fn = part.originalFileName ?: ""
                        ext = fn.substringAfterLast('.', "bin")
                        if (format == null) format = ext
                    }
                    else -> {}
                }
                part.dispose()
            }

            if (readError != null) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("readError" to readError))
                return@post
            }

            if (bytes == null || classId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "缺少 audio 文件或 classId"))
                return@post
            }

            val recordingId = UUID.randomUUID().toString()
            // WAV/PCM 服务端自己解析算电平和信噪比；m4a 这类解不了的，用客户端上报的 avgLevel
            val analysis = services.analyzer.analyze(bytes!!, format)
            val finalAvgLevel = analysis.avgLevelDb ?: avgLevelDb
            val finalDuration = durationSec ?: analysis.durationSec
            val meta = RecordingMeta(
                recordingId = recordingId,
                classId = classId,
                sessionId = sessionId,
                deviceId = deviceId,
                role = role,
                format = format,
                sampleRate = sampleRate,
                durationSec = finalDuration,
                sizeBytes = bytes!!.size.toLong(),
                avgLevelDb = finalAvgLevel,
                snrDb = analysis.snrDb
            )
            val url = services.storage.save(
                recordingId, bytes!!, ext,
                mapOf(
                    "classId" to classId,
                    "sessionId" to sessionId,
                    "deviceId" to deviceId,
                    "role" to role,
                    "format" to (format ?: ext),
                    "sampleRate" to (sampleRate?.toString() ?: ""),
                    "durationSec" to (finalDuration?.toString() ?: ""),
                    "sizeBytes" to bytes!!.size.toString(),
                    "avgLevelDb" to (finalAvgLevel?.toString() ?: ""),
                    "snrDb" to (analysis.snrDb?.toString() ?: "")
                )
            )
            val score = services.scorer.score(meta).score
            // 元数据按会话落一份 Redis，服务端自己有账，不全靠融合时客户端传
            if (sessionId.isNotBlank()) {
                services.redis.saveRecordingMeta(sessionId, Json.encodeToString(RecordingMeta.serializer(), meta))
            }
            call.respond(
                HttpStatusCode.Created,
                RecordingUploadResult(recordingId, url, score, Instant.now().toString(), role, finalAvgLevel, analysis.snrDb)
            )
        } catch (t: Throwable) {
            println("UPLOAD-ERROR: " + t.stackTraceToString())
            call.respondText("error: ${t.message}", status = HttpStatusCode.InternalServerError)
        }
    }

    post("/api/recordings/fuse") {
        val req = call.receive<FuseRequest>()
        val scored = req.recordings.map { it to services.scorer.score(it).score }
        call.respond(services.fusion.fuse(req.sessionId, scored))
    }
}
