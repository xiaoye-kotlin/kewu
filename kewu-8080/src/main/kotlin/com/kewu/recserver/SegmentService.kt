package com.kewu.recserver

import com.kewu.db.Classes
import com.kewu.db.Recordings
import com.kewu.recserver.model.RecordingMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 录音段拼接（2026-11-04，文档六十六节任务 #15/#16）。
 *
 * 客户端一节课的录音被切成 10 分钟一段（segGroup = "$sessionId-$deviceId-$recStartMs"），
 * 逐段上传、Redis 记账。教师点「融合录音」→ finalize 端点调 [mergeSession]：
 * 每组段按 segIndex 排序 → 从存储拉回字节 → 剥 WAV 头拼回完整 PCM → 包 WAV 头 →
 * 作为**一条正式录音**走 analyze / Recordings 表 / Redis 元数据通道——
 * 之后的择优（ClassEndService.onClassEnded）与转写看到的是"每台设备一份录音"，
 * 段数不会撑爆 asrMax 转写上限。
 */
class SegmentService(private val services: RecServices) {

    /** 段上传时存进 Redis 的登记信息。 */
    @Serializable
    data class SegmentMeta(
        val segIndex: Int,
        val url: String,
        val sampleRate: Int = 16000,
        val durationSec: Double = 0.0,
        val role: String = "student",
        val at: Long = 0L,
    )

    data class MergeResult(val groups: Int, val merged: Int, val segments: Int)

    private val json = Json { ignoreUnknownKeys = true }
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    suspend fun mergeSession(sessionId: String): MergeResult {
        val groups = services.redis.getSegmentGroups(sessionId)
        var merged = 0
        var segCount = 0
        for (group in groups) {
            val metas = services.redis.getSegments(sessionId, group)
                .mapNotNull { runCatching { json.decodeFromString(SegmentMeta.serializer(), it) }.getOrNull() }
                .sortedBy { it.segIndex }
            if (metas.isEmpty()) continue
            segCount += metas.size

            // 逐段拉回字节（OBS/本地存储统一走 AudioStorage.get），剥头拼 PCM
            val pcm = ByteArrayOutputStream()
            var got = 0
            for (m in metas) {
                val bytes = services.storage.get(m.url) ?: continue
                pcm.write(stripWavHeader(bytes))
                got++
            }
            val data = pcm.toByteArray()
            if (data.isEmpty() || got == 0) {
                println("SEGMENT-MERGE-WARN: $group 段字节全部拉取失败，跳过本组")
                continue
            }
            val sampleRate = metas.first().sampleRate
            val wav = wrapWav(data, sampleRate)
            val durationSec = metas.sumOf { it.durationSec }
            val role = metas.first().role
            val classId = sessionId.substringBeforeLast('-')

            val recordingId = "merged-" + UUID.randomUUID()
            val url = services.storage.save(
                recordingId, wav, "wav",
                mapOf(
                    "sessionId" to sessionId, "segGroup" to group,
                    "mergedSegments" to got.toString(), "sampleRate" to sampleRate.toString(),
                    "durationSec" to durationSec.toString(), "sizeBytes" to wav.size.toString(),
                )
            )
            val analysis = services.analyzer.analyze(wav, "wav")
            val courseId = runCatching {
                transaction { Classes.selectAll().where { Classes.id eq classId }.firstOrNull()?.get(Classes.courseId) }
            }.getOrNull() ?: classId
            runCatching {
                transaction {
                    Recordings.insert {
                        it[Recordings.id] = recordingId
                        it[Recordings.classId] = classId
                        it[Recordings.sessionId] = sessionId
                        it[Recordings.courseId] = courseId
                        it[Recordings.studentId] = null
                        it[Recordings.studentName] = null
                        it[Recordings.role] = role
                        it[Recordings.deviceId] = group.take(64)
                        it[Recordings.url] = url
                        it[Recordings.score] = services.scorer.score(
                            RecordingMeta(recordingId, classId, sessionId, group, role, "wav", sampleRate, durationSec, wav.size.toLong(), analysis.avgLevelDb, analysis.snrDb)
                        ).score
                        it[Recordings.avgLevelDb] = analysis.avgLevelDb
                        it[Recordings.snrDb] = analysis.snrDb
                        it[Recordings.durationSec] = durationSec
                        it[Recordings.format] = "wav"
                        it[Recordings.sizeBytes] = wav.size.toLong()
                        it[Recordings.createdAt] = LocalDateTime.now().format(fmt)
                    }
                }
            }.onFailure { println("SEGMENT-MERGE-DB-WARN: $it") }
            // 元数据落 Redis：融合 / 转写 pipeline 与整轨上传完全同通道
            val endedAtMs = metas.maxOfOrNull { it.at } ?: System.currentTimeMillis()
            val meta = RecordingMeta(
                recordingId = recordingId, classId = classId, sessionId = sessionId, deviceId = group,
                role = role, format = "wav", sampleRate = sampleRate, durationSec = durationSec,
                sizeBytes = wav.size.toLong(), avgLevelDb = analysis.avgLevelDb, snrDb = analysis.snrDb,
                storedUrl = url, endedAtMs = endedAtMs,
            )
            services.redis.saveRecordingMeta(sessionId, Json.encodeToString(RecordingMeta.serializer(), meta))
            println("SEGMENT-MERGED: $group segs=$got/${metas.size} dur=${durationSec}s size=${wav.size}")
            merged++
        }
        return MergeResult(groups.size, merged, segCount)
    }

    /** 剥掉 44 字节 WAV 头取纯 PCM；不是 WAV（老格式兜底）原样返回。 */
    private fun stripWavHeader(bytes: ByteArray): ByteArray {
        if (bytes.size <= 44) return bytes
        if (bytes[0] != 'R'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
            bytes[8] != 'W'.code.toByte() || bytes[9] != 'A'.code.toByte()
        ) return bytes
        val dataSize = ((bytes[40].toInt() and 0xFF) or
            ((bytes[41].toInt() and 0xFF) shl 8) or
            ((bytes[42].toInt() and 0xFF) shl 16) or
            ((bytes[43].toInt() and 0xFF) shl 24)).coerceAtMost(bytes.size - 44)
        return bytes.copyOfRange(44, 44 + dataSize)
    }

    /** 纯 PCM → 44 字节头 WAV（16bit 单声道，与客户端格式一致）。 */
    private fun wrapWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val channels = 1
        val bits = 16
        val byteRate = sampleRate * channels * bits / 8
        fun i32(v: Int) = byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
        )
        fun i16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        val h = ByteArray(44)
        "RIFF".toByteArray().copyInto(h, 0)
        i32(36 + pcm.size).copyInto(h, 4)
        "WAVE".toByteArray().copyInto(h, 8)
        "fmt ".toByteArray().copyInto(h, 12)
        i32(16).copyInto(h, 16)
        i16(1).copyInto(h, 20)
        i16(channels).copyInto(h, 22)
        i32(sampleRate).copyInto(h, 24)
        i32(byteRate).copyInto(h, 28)
        i16(channels * bits / 8).copyInto(h, 32)
        i16(bits).copyInto(h, 34)
        "data".toByteArray().copyInto(h, 36)
        i32(pcm.size).copyInto(h, 40)
        val out = ByteArray(44 + pcm.size)
        h.copyInto(out)
        pcm.copyInto(out, 44)
        return out
    }

    /** 供 finalize 路由即时拼接用（路由层包装异常）。 */
    suspend fun mergeSessionSafe(sessionId: String): MergeResult = withContext(Dispatchers.IO) {
        mergeSession(sessionId)
    }
}
