package com.kewu.recserver

import com.kewu.db.ClassRecordings
import com.kewu.recserver.model.RecordingMeta
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 下课后的录音收尾（2026-10-26）：一节课结束（老师手动下课，或开课满 90 分钟自动下课）时，
 * 取这节课协同会话里所有端上传的录音元数据，按质量分 + 融合策略（教师端优先、异常回退学生端）选出**最佳录音**，
 * 落到 `class_recordings`，transcriptStatus = PENDING_ASR，留给后续华为云语音转文字任务处理。
 *
 * 会话 id 与 App 一致：`{courseId}-{yyyyMMdd}`（KewuApi.classSessionId）。
 */
class ClassEndService(private val services: RecServices) {

    companion object {
        /** 录音上传接口在每次上传后调用：若这节课已下课择优过，就带上新录音重新择优（晚到的上传不丢）。 */
        @Volatile var onUpload: ((String) -> Unit)? = null
    }

    /** 已下课的会话：新录音到达时重新择优。 */
    suspend fun refreshIfDecided(sessionId: String) {
        val row = transaction {
            ClassRecordings.selectAll().where { ClassRecordings.sessionId eq sessionId }.firstOrNull()
        } ?: return
        onClassEnded(row[ClassRecordings.courseId], sessionId.substringAfterLast('-').let {
            if (it.length == 8) "${it.take(4)}-${it.substring(4, 6)}-${it.takeLast(2)}" else null
        }, row[ClassRecordings.endReason])
    }

    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class ClassRecordingView(
        val sessionId: String,
        val courseId: String,
        val primaryRecordingId: String? = null,
        val primaryUrl: String? = null,
        val strategy: String = "",
        val candidateCount: Int = 0,
        val endReason: String = "",
        val decidedAt: String = "",
        val transcriptStatus: String = "",
        val transcript: String? = null,
        val summary: String? = null,
        val asrError: String? = null
    )

    /** [startedAt] 形如 "2026-10-26 14:00"；取日期部分拼会话 id。 */
    suspend fun onClassEnded(courseId: String, startedAt: String?, reason: String) {
        val date = (startedAt?.take(10) ?: LocalDateTime.now().format(fmt).take(10)).replace("-", "")
        val sessionId = "$courseId-$date"
        val metas = services.redis.getRecordingMetas(sessionId).mapNotNull {
            runCatching { json.decodeFromString(RecordingMeta.serializer(), it) }.getOrNull()
        }.distinctBy { it.recordingId }
        val scored = metas.map { it to services.scorer.score(it).score }
        val fused = services.fusion.fuse(sessionId, scored)
        val primary = metas.firstOrNull { it.recordingId == fused.primaryRecordingId }
        val now = LocalDateTime.now().format(fmt)
        transaction {
            val old = ClassRecordings.selectAll().where { ClassRecordings.sessionId eq sessionId }.firstOrNull()
            val exists = old != null
            // 主录音没变且已在转写 / 转写完：保留转写状态，别把 DONE 冲回 PENDING_ASR
            val keepAsr = old != null && old[ClassRecordings.primaryRecordingId] == fused.primaryRecordingId &&
                old[ClassRecordings.transcriptStatus] in setOf("TRANSCRIBING", "DONE")
            if (exists) {
                ClassRecordings.update({ ClassRecordings.sessionId eq sessionId }) {
                    it[primaryRecordingId] = fused.primaryRecordingId
                    it[primaryUrl] = primary?.storedUrl
                    it[strategy] = fused.strategy.take(128)
                    it[candidateCount] = metas.size
                    it[endReason] = reason
                    it[decidedAt] = now
                    if (!keepAsr) it[transcriptStatus] = if (primary == null) "NO_AUDIO" else "PENDING_ASR"
                }
            } else {
                ClassRecordings.insert {
                    it[ClassRecordings.sessionId] = sessionId
                    it[ClassRecordings.courseId] = courseId
                    it[primaryRecordingId] = fused.primaryRecordingId
                    it[primaryUrl] = primary?.storedUrl
                    it[strategy] = fused.strategy.take(128)
                    it[candidateCount] = metas.size
                    it[endReason] = reason
                    it[decidedAt] = now
                    it[transcriptStatus] = if (primary == null) "NO_AUDIO" else "PENDING_ASR"
                }
            }
        }
        println("CLASS-END: $sessionId reason=$reason candidates=${metas.size} primary=${fused.primaryRecordingId} (${fused.strategy})")
    }

    /**
     * 转写 + AI 课堂纪要（2026-10-26）：取这节课择优出的主录音 → 华为云 SIS 录音文件识别（异步，最长约 10 分钟）
     * → MaaS 生成结构化课堂纪要。状态：PENDING_ASR → TRANSCRIBING → DONE / FAILED。
     * 学生端小悟辅导、AI 错题讲解等通过 /api/courses/{id}/class-recordings 拿到纪要，知道这节课讲了什么。
     * [force] = 失败后手动重试。
     */
    suspend fun transcribe(sessionId: String, force: Boolean = false) {
        val row = transaction { ClassRecordings.selectAll().where { ClassRecordings.sessionId eq sessionId }.firstOrNull() } ?: return
        val url = row[ClassRecordings.primaryUrl] ?: return
        val st = row[ClassRecordings.transcriptStatus]
        if (!force && st != "PENDING_ASR") return
        if (st == "TRANSCRIBING") return
        val courseId = row[ClassRecordings.courseId]
        transaction { ClassRecordings.update({ ClassRecordings.sessionId eq sessionId }) { it[transcriptStatus] = "TRANSCRIBING"; it[asrError] = null } }
        println("ASR-START: $sessionId url=$url")
        try {
            val format = url.substringAfterLast('.', "wav").lowercase().let { if (it in setOf("wav", "mp3", "m4a", "aac", "amr", "ogg")) it else "wav" }
            val text = services.transcription.transcribe(url, format)
            val courseName = transaction {
                com.kewu.db.Courses.selectAll().where { com.kewu.db.Courses.id eq courseId }.firstOrNull()?.get(com.kewu.db.Courses.name)
            } ?: courseId
            val notes = (services.ai as? com.kewu.recserver.services.ai.MaasAiService)?.classNotes(courseName, text).orEmpty()
            transaction {
                ClassRecordings.update({ ClassRecordings.sessionId eq sessionId }) {
                    it[transcriptStatus] = if (text.isBlank()) "EMPTY" else "DONE"
                    it[transcript] = text
                    it[summary] = notes.ifBlank { null }
                }
            }
            println("ASR-DONE: $sessionId chars=${text.length} notes=${notes.length}")
        } catch (t: Throwable) {
            transaction {
                ClassRecordings.update({ ClassRecordings.sessionId eq sessionId }) {
                    it[transcriptStatus] = "FAILED"
                    it[asrError] = (t.message ?: t.javaClass.simpleName).take(500)
                }
            }
            println("ASR-FAILED: $sessionId ${t.message}")
        }
    }

    fun list(courseId: String): List<ClassRecordingView> = transaction {
        ClassRecordings.selectAll().where { ClassRecordings.courseId eq courseId }
            .orderBy(ClassRecordings.decidedAt, SortOrder.DESC)
            .map {
                ClassRecordingView(
                    sessionId = it[ClassRecordings.sessionId], courseId = it[ClassRecordings.courseId],
                    primaryRecordingId = it[ClassRecordings.primaryRecordingId], primaryUrl = it[ClassRecordings.primaryUrl],
                    strategy = it[ClassRecordings.strategy], candidateCount = it[ClassRecordings.candidateCount],
                    endReason = it[ClassRecordings.endReason], decidedAt = it[ClassRecordings.decidedAt],
                    transcriptStatus = it[ClassRecordings.transcriptStatus], transcript = it[ClassRecordings.transcript],
                    summary = it[ClassRecordings.summary], asrError = it[ClassRecordings.asrError]
                )
            }
    }
}
