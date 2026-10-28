package com.kewu.modules.upload.routes

import com.kewu.models.ApiResponse
import com.kewu.modules.upload.models.Attachment
import com.kewu.modules.upload.models.AttachmentKind
import com.kewu.modules.upload.models.CourseMaterial
import com.kewu.modules.upload.models.CreateMaterialRequest
import com.kewu.modules.upload.repository.UploadRepository
import com.kewu.recserver.RecServicesKey
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.toByteArray
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

private const val MAX_UPLOAD_BYTES = 20L * 1024 * 1024
private const val MAX_ATTACHMENTS = 9
private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

// 通用附件上传 + 课前资料（2026-10-26，对应仓库根「作业附件-后端对接需求.md」）
fun Route.uploadRoutes(repo: UploadRepository) {
    val storage = application.attributes[RecServicesKey].storage

    // POST /api/uploads  multipart: file(必填) kind(IMAGE/FILE/AUDIO) uploader durationSec
    post("/api/uploads") {
        var kind = AttachmentKind.FILE
        var uploader = ""
        var durationSec: Int? = null
        var bytes: ByteArray? = null
        var fileName = ""
        var mime = "application/octet-stream"
        var tooLarge = false
        // 字段上限比 20MB 略大：超限时给人话提示，而不是让 Ktor 直接断连接
        call.receiveMultipart(formFieldLimit = MAX_UPLOAD_BYTES + 1024 * 1024).forEachPart { part ->
            when (part) {
                is PartData.FormItem -> when (part.name) {
                    "kind" -> part.value.uppercase().takeIf { it in AttachmentKind.ALL }?.let { kind = it }
                    "uploader" -> uploader = part.value.take(64)
                    "durationSec" -> durationSec = part.value.toIntOrNull()
                }
                is PartData.FileItem -> if (part.name == "file") {
                    val b = part.provider().toByteArray()
                    if (b.size > MAX_UPLOAD_BYTES) tooLarge = true else bytes = b
                    fileName = part.originalFileName.orEmpty()
                    part.contentType?.toString()?.takeIf { it.isNotBlank() }?.let { mime = it }
                }
                else -> {}
            }
            part.dispose()
        }
        if (tooLarge) {
            call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(1, "文件不能超过 20MB"))
            return@post
        }
        val data = bytes
        if (data == null || data.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(1, "没有收到文件"))
            return@post
        }
        // 文件名只留最后一段、去掉控制字符；存储 key 用随机 id，不拿原文件名当 key
        val safeName = fileName.substringAfterLast('/').substringAfterLast('\\')
            .filter { it.code >= 0x20 }.take(200).ifBlank { "附件" }
        val ext = safeName.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(8).ifBlank { "bin" }
        val id = "att_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val key = "attachments/" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMM")) + "/$id.$ext"
        val url = try {
            storage.saveObject(key, data, mime)
        } catch (t: Throwable) {
            println("UPLOAD-ERROR: " + t.stackTraceToString())
            call.respond(HttpStatusCode.InternalServerError, ApiResponse<String>(1, "文件保存失败，请稍后重试"))
            return@post
        }
        val att = Attachment(
            id, kind, safeName, url, mime, data.size.toLong(),
            durationSec?.takeIf { kind == AttachmentKind.AUDIO && it >= 0 }
        )
        repo.save(att, uploader, LocalDateTime.now().format(fmt))
        call.respond(ApiResponse(0, "上传成功", att))
    }

    // 课前资料：POST /api/courses/{courseId}/materials  { title, note, sessionId?, uploader, attachments[] }
    post("/api/courses/{courseId}/materials") {
        val courseId = call.parameters["courseId"]!!
        val req = call.receive<CreateMaterialRequest>()
        if (req.attachments.isEmpty() && req.note.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(1, "请至少上传一个文件或填写说明"))
            return@post
        }
        if (req.attachments.size > MAX_ATTACHMENTS) {
            call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(1, "资料最多 $MAX_ATTACHMENTS 个"))
            return@post
        }
        // 只认 /api/uploads 真存过的文件，以服务端记录为准
        val atts = ArrayList<Attachment>()
        for (a in req.attachments) {
            val found = repo.find(a.id)
            if (found == null) {
                call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(1, "资料「${a.name.ifBlank { a.id }}」无效，请重新上传"))
                return@post
            }
            atts += found
        }
        val now = LocalDateTime.now().format(fmt)
        val m = CourseMaterial(
            id = "m_" + UUID.randomUUID().toString().replace("-", "").take(12),
            courseId = courseId,
            sessionId = req.sessionId?.takeIf { it.isNotBlank() },
            title = req.title.ifBlank { "课前资料 " + now.take(10) }.take(200),
            note = req.note.take(4000),
            attachments = atts,
            uploader = req.uploader.take(64),
            createdAt = now
        )
        repo.saveMaterial(m)
        call.respond(ApiResponse(0, "资料已保存", m))
    }

    // GET /api/courses/{courseId}/materials  最新在前
    get("/api/courses/{courseId}/materials") {
        call.respond(ApiResponse(0, "ok", repo.listMaterials(call.parameters["courseId"]!!)))
    }
}
