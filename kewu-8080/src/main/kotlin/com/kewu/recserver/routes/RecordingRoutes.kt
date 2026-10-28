package com.kewu.recserver.routes

import com.kewu.db.Classes
import com.kewu.db.Recordings
import com.kewu.recserver.RecServicesKey
import com.kewu.recserver.model.DeviceHeartbeat
import com.kewu.recserver.model.DeviceStatus
import com.kewu.recserver.model.FuseRequest
import com.kewu.recserver.model.RecordingMeta
import com.kewu.recserver.model.RecordingUploadResult
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.forEachPart
import io.ktor.http.content.PartData
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.toByteArray
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

// 录音上传 + 融合：多端把音频传上来，存好、算电平/信噪比、打分，教师端优先择优，异常自动回退学生端
fun Route.recordingRoutes() {
    val services = application.attributes[RecServicesKey]

    post("/api/recordings/upload") {
        try {
            // Ktor 3 默认单个表单字段上限 50MB，一节课的 WAV 会超；放到 300MB（与 App 上传超时 600s 配套）
            val multipart = call.receiveMultipart(formFieldLimit = 300L * 1024 * 1024)
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
                            bytes = part.provider().toByteArray()
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
            // 逐条上传落 recordings 表：总控端「查看全部录音」的数据源。失败只告警，绝不影响上传返回
            runCatching {
                val courseId = if (classId.isNotBlank()) {
                    Classes.selectAll().where { Classes.id eq classId }.firstOrNull()?.get(Classes.courseId)
                } else null
                transaction {
                    Recordings.insert {
                        it[Recordings.id] = recordingId
                        it[Recordings.classId] = classId
                        it[Recordings.sessionId] = sessionId.ifBlank { null }
                        it[Recordings.courseId] = courseId
                        it[Recordings.studentId] = null
                        it[Recordings.studentName] = null
                        it[Recordings.role] = role
                        it[Recordings.deviceId] = deviceId.ifBlank { null }
                        it[Recordings.url] = url
                        it[Recordings.score] = score
                        it[Recordings.avgLevelDb] = finalAvgLevel
                        it[Recordings.snrDb] = analysis.snrDb
                        it[Recordings.durationSec] = finalDuration
                        it[Recordings.format] = format ?: ext
                        it[Recordings.sizeBytes] = bytes!!.size.toLong()
                        it[Recordings.createdAt] = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    }
                }
            }.onFailure { println("REC-DB-WARN: $it") }
            // 元数据按会话落一份 Redis，服务端自己有账，不全靠融合时客户端传
            if (sessionId.isNotBlank()) {
                services.redis.saveRecordingMeta(sessionId, Json.encodeToString(RecordingMeta.serializer(), meta.copy(storedUrl = url)))
            }
            if (sessionId.isNotBlank()) com.kewu.recserver.ClassEndService.onUpload?.invoke(sessionId)
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

    // 录音设备雷达与状态监控：Controller 只收 sessionId、调 Service、返回，不含任何 Redis/统计逻辑
    get("/api/teacher/recording/devices") {
        val sessionId = call.request.queryParameters["sessionId"] ?: ""
        call.respond(services.deviceMonitor.getDeviceReport(sessionId))
    }

    // 设备心跳上报入口：App/大屏端录音时定时把自身状态写进 Redis（field=deviceId, value=DeviceStatus JSON）。
    // 这是上面 GET 接口的数据来源——没有心跳，雷达就是空的。Redis 降级时如实报 degraded，不假成功
    post("/api/teacher/recording/devices/heartbeat") {
        try {
            val hb = call.receive<DeviceHeartbeat>()
            if (hb.sessionId.isBlank() || hb.deviceId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "sessionId 和 deviceId 必填"))
                return@post
            }
            val status = DeviceStatus(
                deviceId = hb.deviceId,
                type = hb.type,
                status = hb.status,
                qualityScore = hb.qualityScore,
                lastHeartbeat = System.currentTimeMillis(), // 一律用服务端时间：手机时钟可能偏差，过期判断才准
                name = hb.name.take(32),
                role = hb.role.take(16),
                elapsedSec = hb.elapsedSec
            )
            val json = Json.encodeToString(DeviceStatus.serializer(), status)
            if (services.redis.saveDeviceStatus(hb.sessionId, hb.deviceId, json)) {
                call.respond(mapOf("ok" to true))
            } else {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("ok" to false, "degraded" to true, "error" to "redis unavailable, heartbeat not persisted"))
            }
        } catch (t: Throwable) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (t.message ?: "bad request")))
        }
    }
}
