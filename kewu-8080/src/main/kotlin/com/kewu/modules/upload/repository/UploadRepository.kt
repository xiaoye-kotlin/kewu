package com.kewu.modules.upload.repository

import com.kewu.db.CourseMaterials
import com.kewu.db.Uploads
import com.kewu.db.kewuJson
import com.kewu.modules.upload.models.Attachment
import com.kewu.modules.upload.models.CourseMaterial
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

// 上传记录 + 课前资料的 MySQL 存取
class UploadRepository {

    fun save(a: Attachment, uploader: String, createdAt: String) {
        transaction {
            Uploads.insert {
                it[id] = a.id
                it[kind] = a.kind
                it[name] = a.name
                it[url] = a.url
                it[mime] = a.mime
                it[size] = a.size
                it[durationSec] = a.durationSec
                it[Uploads.uploader] = uploader
                it[Uploads.createdAt] = createdAt
            }
        }
    }

    fun find(id: String): Attachment? = transaction {
        Uploads.selectAll().where { Uploads.id eq id }.firstOrNull()?.let {
            Attachment(
                id = it[Uploads.id], kind = it[Uploads.kind], name = it[Uploads.name], url = it[Uploads.url],
                mime = it[Uploads.mime], size = it[Uploads.size], durationSec = it[Uploads.durationSec]
            )
        }
    }

    fun saveMaterial(m: CourseMaterial) {
        transaction {
            CourseMaterials.insert {
                it[id] = m.id
                it[courseId] = m.courseId
                it[sessionId] = m.sessionId
                it[title] = m.title
                it[note] = m.note
                it[attachments] = kewuJson.encodeToString(m.attachments)
                it[uploader] = m.uploader
                it[createdAt] = m.createdAt
            }
        }
    }

    fun listMaterials(courseId: String): List<CourseMaterial> = transaction {
        CourseMaterials.selectAll().where { CourseMaterials.courseId eq courseId }
            .orderBy(CourseMaterials.createdAt, SortOrder.DESC)
            .map { it.toMaterial() }
    }

    private fun ResultRow.toMaterial() = CourseMaterial(
        id = this[CourseMaterials.id],
        courseId = this[CourseMaterials.courseId],
        sessionId = this[CourseMaterials.sessionId],
        title = this[CourseMaterials.title],
        note = this[CourseMaterials.note],
        attachments = runCatching { kewuJson.decodeFromString<List<Attachment>>(this[CourseMaterials.attachments]) }
            .getOrElse { emptyList() },
        uploader = this[CourseMaterials.uploader],
        createdAt = this[CourseMaterials.createdAt]
    )
}
