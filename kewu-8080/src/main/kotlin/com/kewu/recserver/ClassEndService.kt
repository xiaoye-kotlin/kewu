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
        val transcript: String? = null
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
            val exists = !ClassRecordings.selectAll().where { ClassRecordings.sessionId eq sessionId }.empty()
            if (exists) {
                ClassRecordings.update({ ClassRecordings.sessionId eq sessionId }) {
                    it[primaryRecordingId] = fused.primaryRecordingId
                    it[primaryUrl] = primary?.storedUrl
                    it[strategy] = fused.strategy.take(128)
                    it[candidateCount] = metas.size
                    it[endReason] = reason
                    it[decidedAt] = now
                    it[transcriptStatus] = if (primary == null) "NO_AUDIO" else "PENDING_ASR"
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

    fun list(courseId: String): List<ClassRecordingView> = transaction {
        ClassRecordings.selectAll().where { ClassRecordings.courseId eq courseId }
            .orderBy(ClassRecordings.decidedAt, SortOrder.DESC)
            .map {
                ClassRecordingView(
                    sessionId = it[ClassRecordings.sessionId], courseId = it[ClassRecordings.courseId],
                    primaryRecordingId = it[ClassRecordings.primaryRecordingId], primaryUrl = it[ClassRecordings.primaryUrl],
                    strategy = it[ClassRecordings.strategy], candidateCount = it[ClassRecordings.candidateCount],
                    endReason = it[ClassRecordings.endReason], decidedAt = it[ClassRecordings.decidedAt],
                    transcriptStatus = it[ClassRecordings.transcriptStatus], transcript = it[ClassRecordings.transcript]
                )
            }
    }
}
