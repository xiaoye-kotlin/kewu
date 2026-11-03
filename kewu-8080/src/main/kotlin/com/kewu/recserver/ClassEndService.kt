package com.kewu.recserver

import com.kewu.db.ClassRecordings
import com.kewu.db.Classes
import com.kewu.db.Courses
import com.kewu.db.RecordingTranscripts
import com.kewu.db.Recordings
import com.kewu.recserver.model.RecordingMeta
import com.kewu.recserver.services.SisTranscriptionService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 下课后的录音收尾：一节课结束（老师手动下课，或开课满 90 分钟自动下课）时，
 * 取这节课协同会话里所有端上传的录音元数据，按质量分 + 融合策略（教师端优先、异常回退学生端）选出**主录音**，
 * 落到 `class_recordings`，transcriptStatus = PENDING_ASR，等待转写。
 *
 * 2026-11-02 转写改为「多端互补」：本节课各端录音（教师端优先，其余按质量分，最多 [asrMax] 份）**逐份转写**，
 * 以主录音为骨架，按绝对时间把主录音没录到的片段用其他录音补上，合成一份完整课堂文本再生成纪要；
 * 学生端录音的转写再做「课堂专注分析」（是否闲聊 / 开小差），供教师端查看。
 * 进度写 `stage` / `progress`，首页「纪要生成进度」卡读它。
 *
 * 会话 id 与 App 一致：`{courseId}-{yyyyMMdd}`（KewuApi.classSessionId）。
 */
class ClassEndService(private val services: RecServices) {

    companion object {
        /** 录音上传接口在每次上传后调用：若这节课已下课择优过，就带上新录音重新择优（晚到的上传不丢）。 */
        @Volatile var onUpload: ((String) -> Unit)? = null

        /** 单节课最多转写几份录音（SIS 按时长计费）。环境变量 KEWU_ASR_MAX 可调。 */
        val asrMax: Int = System.getenv("KEWU_ASR_MAX")?.toIntOrNull()?.coerceIn(1, 60) ?: 12

        fun sessionRecordings(sessionId: String): List<SessionRecordingView> = transaction {
            // 转写表读失败（老库缺表 / 列）不拖垮整页：录音照常列出，只是没有转写与专注结论
            val tr = runCatching {
                RecordingTranscripts.selectAll().where { RecordingTranscripts.sessionId eq sessionId }
                    .associateBy { it[RecordingTranscripts.recordingId] }
            }.onFailure { println("SESSION-RECS-WARN transcripts $sessionId: $it") }.getOrDefault(emptyMap())
            val fromDb = Recordings.selectAll().where { (Recordings.sessionId eq sessionId) and Recordings.deletedAt.isNull() }
                .orderBy(Recordings.createdAt, SortOrder.ASC)
                .map { r ->
                    val t = tr[r[Recordings.id]]
                    SessionRecordingView(
                        recordingId = r[Recordings.id], role = r[Recordings.role],
                        username = t?.get(RecordingTranscripts.username),
                        name = r[Recordings.studentName] ?: t?.get(RecordingTranscripts.displayName),
                        url = r[Recordings.url], durationSec = r[Recordings.durationSec],
                        createdAt = r[Recordings.createdAt], score = r[Recordings.score],
                        transcriptStatus = t?.get(RecordingTranscripts.status),
                        focusVerdict = t?.get(RecordingTranscripts.focusVerdict),
                        focusEvidence = t?.get(RecordingTranscripts.focusEvidence),
                        focusConfidence = t?.get(RecordingTranscripts.focusConfidence)
                    )
                }
            // recordings 表缺的（老数据只在转写表里）补上
            val known = fromDb.map { it.recordingId }.toSet()
            fromDb + tr.values.filter { it[RecordingTranscripts.recordingId] !in known }.map { t ->
                SessionRecordingView(
                    recordingId = t[RecordingTranscripts.recordingId], role = t[RecordingTranscripts.role],
                    username = t[RecordingTranscripts.username], name = t[RecordingTranscripts.displayName],
                    url = t[RecordingTranscripts.url], transcriptStatus = t[RecordingTranscripts.status],
                    focusVerdict = t[RecordingTranscripts.focusVerdict], focusEvidence = t[RecordingTranscripts.focusEvidence],
                    focusConfidence = t[RecordingTranscripts.focusConfidence]
                )
            }
        }
    }

    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val json = Json { ignoreUnknownKeys = true }
    /** 正在转写的会话（进程内防重入；服务重启后 TRANSCRIBING 状态可被 force 重跑）。 */
    private val running = ConcurrentHashMap.newKeySet<String>()
    /** 晚到上传的防抖：会话 → 最近一次上传时间。 */
    private val lastUpload = ConcurrentHashMap<String, Long>()

    /** 已下课的会话：新录音到达时重新择优；停止上传 3 分钟后把新录音补进转写（已转过的不重复转）。 */
    suspend fun refreshIfDecided(sessionId: String) {
        val row = transaction {
            ClassRecordings.selectAll().where { ClassRecordings.sessionId eq sessionId }.firstOrNull()
        } ?: return
        onClassEnded(row[ClassRecordings.courseId], sessionId.substringAfterLast('-').let {
            if (it.length == 8) "${it.take(4)}-${it.substring(4, 6)}-${it.takeLast(2)}" else null
        }, row[ClassRecordings.endReason])
        val ts = System.currentTimeMillis()
        lastUpload[sessionId] = ts
        delay(180_000)
        if (lastUpload[sessionId] != ts) return
        val st = transaction {
            ClassRecordings.selectAll().where { ClassRecordings.sessionId eq sessionId }.firstOrNull()?.get(ClassRecordings.transcriptStatus)
        }
        if (st == null || st == "NO_AUDIO") return
        // 正在转写就等它跑完再补转（最多等 60 分钟）
        repeat(120) { if (sessionId in running) delay(30_000) }
        if (lastUpload[sessionId] == ts) transcribe(sessionId, force = true)
    }

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
        val durationSec: Int? = null,
        val teacherId: String? = null,
        val teacherName: String? = null,
        val transcript: String? = null,
        val summary: String? = null,
        val asrError: String? = null,
        /** 进度文案（等待上传 / 转写中 2/5 / 合并中 / 生成纪要 / 完成 / 失败）。 */
        val stage: String? = null,
        /** 0-100。 */
        val progress: Int? = null
    )

    /** [startedAt] 形如 "2026-10-26 14:00"；取日期部分拼会话 id。 */
    suspend fun onClassEnded(courseId: String, startedAt: String?, reason: String) {
        val date = (startedAt?.take(10) ?: LocalDateTime.now().format(fmt).take(10)).replace("-", "")
        val sessionId = "$courseId-$date"
        val metas = sessionMetas(sessionId)
        val scored = metas.map { it to services.scorer.score(it).score }
        val fused = services.fusion.fuse(sessionId, scored)
        val primary = metas.firstOrNull { it.recordingId == fused.primaryRecordingId }
        val duration = primary?.durationSec?.takeIf { it > 0 }?.toInt()
        val now = LocalDateTime.now().format(fmt)
        transaction {
            val old = ClassRecordings.selectAll().where { ClassRecordings.sessionId eq sessionId }.firstOrNull()
            // 已在转写 / 转写完：保留状态，别把 DONE 冲回 PENDING_ASR（晚到的录音由 refreshIfDecided 补转）
            val keepAsr = old != null && old[ClassRecordings.transcriptStatus] in setOf("TRANSCRIBING", "DONE")
            if (old != null) {
                ClassRecordings.update({ ClassRecordings.sessionId eq sessionId }) {
                    it[primaryRecordingId] = fused.primaryRecordingId
                    it[primaryUrl] = primary?.storedUrl
                    it[strategy] = fused.strategy.take(128)
                    it[candidateCount] = metas.size
                    it[endReason] = reason
                    it[decidedAt] = now
                    it[ClassRecordings.durationSec] = duration
                    if (!keepAsr) {
                        it[transcriptStatus] = if (primary == null) "NO_AUDIO" else "PENDING_ASR"
                        it[stage] = if (primary == null) "等待录音上传" else "排队中"
                        it[progress] = 0
                    }
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
                    it[ClassRecordings.durationSec] = duration
                    it[transcriptStatus] = if (primary == null) "NO_AUDIO" else "PENDING_ASR"
                    it[stage] = if (primary == null) "等待录音上传" else "排队中"
                    it[progress] = 0
                }
            }
        }
        println("CLASS-END: $sessionId reason=$reason candidates=${metas.size} primary=${fused.primaryRecordingId} (${fused.strategy})")
    }

    /** 本节课全部录音元数据：Redis 为主，Redis 丢了就用 recordings 表兜底。 */
    private suspend fun sessionMetas(sessionId: String): List<RecordingMeta> {
        val fromRedis = services.redis.getRecordingMetas(sessionId).mapNotNull {
            runCatching { json.decodeFromString(RecordingMeta.serializer(), it) }.getOrNull()
        }
        val fromDb = runCatching {
            transaction {
                Recordings.selectAll().where { (Recordings.sessionId eq sessionId) and Recordings.deletedAt.isNull() }.map {
                    RecordingMeta(
                        recordingId = it[Recordings.id], classId = it[Recordings.classId], sessionId = sessionId,
                        deviceId = it[Recordings.deviceId].orEmpty(), role = it[Recordings.role],
                        format = it[Recordings.format], durationSec = it[Recordings.durationSec],
                        sizeBytes = it[Recordings.sizeBytes], avgLevelDb = it[Recordings.avgLevelDb],
                        snrDb = it[Recordings.snrDb], storedUrl = it[Recordings.url],
                        displayName = it[Recordings.studentName],
                    )
                }
            }
        }.getOrDefault(emptyList())
        return (fromRedis + fromDb).distinctBy { it.recordingId }
    }

    private fun setProgress(sessionId: String, status: String?, stageText: String, pct: Int, error: String? = null) {
        transaction {
            ClassRecordings.update({ ClassRecordings.sessionId eq sessionId }) {
                if (status != null) it[transcriptStatus] = status
                it[stage] = stageText.take(64)
                it[progress] = pct.coerceIn(0, 100)
                if (error != null) it[asrError] = error.take(500)
            }
        }
    }

    private fun formatOf(url: String) =
        url.substringAfterLast('.', "wav").lowercase().let { if (it in setOf("wav", "mp3", "m4a", "aac", "amr", "ogg")) it else "wav" }

    /**
     * 转写 + 互补合并 + AI 课堂纪要 + 课堂专注分析。状态：PENDING_ASR → TRANSCRIBING → DONE / EMPTY / FAILED。
     * 已转写成功的录音不重复转（晚到录音补转时只转新增的）。[force] = 手动重试 / 补转。
     */
    suspend fun transcribe(sessionId: String, force: Boolean = false) {
        val row = transaction { ClassRecordings.selectAll().where { ClassRecordings.sessionId eq sessionId }.firstOrNull() } ?: return
        val st = row[ClassRecordings.transcriptStatus]
        if (!force && st != "PENDING_ASR") return
        if (!running.add(sessionId)) return
        try {
            runPipeline(sessionId, row[ClassRecordings.courseId], row[ClassRecordings.primaryRecordingId])
        } finally {
            running.remove(sessionId)
        }
    }

    /**
     * 兜底巡检（2026-11-02）：卡在 PENDING_ASR（下课触发丢了 / 晚到上传把状态冲回）或
     * TRANSCRIBING（转写中途服务重启）超过 5 分钟、且本进程没在跑的节次，重新转写。
     * 线代 11-02 那节就是转写失败后被晚到上传冲回 PENDING_ASR，再没人触发，首页一直 10%。
     */
    suspend fun sweepStuck() {
        val cutoff = LocalDateTime.now().minusMinutes(5).format(fmt)
        val stuck = transaction {
            ClassRecordings.selectAll().where {
                (ClassRecordings.transcriptStatus eq "PENDING_ASR") or (ClassRecordings.transcriptStatus eq "TRANSCRIBING")
            }.filter { it[ClassRecordings.decidedAt] <= cutoff }.map { it[ClassRecordings.sessionId] }
        }
        for (sid in stuck) {
            if (sid in running) continue
            println("ASR-SWEEP: $sid")
            runCatching { transcribe(sid, force = true) }.onFailure { println("ASR-SWEEP-ERROR: $sid $it") }
        }
    }

    private suspend fun runPipeline(sessionId: String, courseId: String, primaryId: String?) {
        val courseName = transaction {
            Courses.selectAll().where { Courses.id eq courseId }.firstOrNull()?.get(Courses.name)
        } ?: courseId
        val metas = sessionMetas(sessionId).filter { !it.storedUrl.isNullOrBlank() }
        if (metas.isEmpty()) {
            setProgress(sessionId, "NO_AUDIO", "没有录音", 0)
            return
        }
        // 候选：主录音 → 教师端 → 学生端（按质量分），最多 asrMax 份
        val scored = metas.associateWith { services.scorer.score(it).score }
        val ordered = metas.sortedWith(
            compareByDescending<RecordingMeta> { it.recordingId == primaryId }
                .thenByDescending { it.role.lowercase() in setOf("teacher", "教师") }
                .thenByDescending { scored[it] ?: 0.0 }
        ).take(asrMax)

        val done = transaction {
            RecordingTranscripts.selectAll().where { RecordingTranscripts.sessionId eq sessionId }
                .associateBy { it[RecordingTranscripts.recordingId] }
        }
        val todo = ordered.filter { done[it.recordingId]?.get(RecordingTranscripts.status) != "DONE" }
        val total = ordered.size
        val finished = AtomicInteger(total - todo.size)
        setProgress(sessionId, "TRANSCRIBING", "转写中 ${finished.get()}/$total", 5, error = "")
        println("ASR-START: $sessionId total=$total todo=${todo.size}")

        // 逐份转写（并发 3 路，SIS 异步任务大部分时间在排队等结果）
        val gate = Semaphore(3)
        coroutineScope {
            todo.map { m ->
                async {
                    gate.withPermit {
                        val segs = runCatching {
                            services.transcription.transcribeSegments(m.storedUrl!!, formatOf(m.storedUrl))
                        }
                        saveTranscript(sessionId, courseId, m, segs.getOrNull(), segs.exceptionOrNull()?.message)
                        val n = finished.incrementAndGet()
                        setProgress(sessionId, null, "转写中 $n/$total", 5 + 60 * n / total.coerceAtLeast(1))
                    }
                }
            }.awaitAll()
        }

        val rows = transaction {
            RecordingTranscripts.selectAll().where {
                (RecordingTranscripts.sessionId eq sessionId) and (RecordingTranscripts.status eq "DONE")
            }.toList()
        }
        val tracks = ordered.mapNotNull { m ->
            val r = rows.firstOrNull { it[RecordingTranscripts.recordingId] == m.recordingId } ?: return@mapNotNull null
            Track(m, decodeSegments(r[RecordingTranscripts.segments]))
        }.filter { it.segments.isNotEmpty() }
        if (tracks.isEmpty()) {
            val err = transaction {
                RecordingTranscripts.selectAll().where { RecordingTranscripts.sessionId eq sessionId }
                    .mapNotNull { it[RecordingTranscripts.transcript] }.firstOrNull { it.startsWith("ERR:") }
            }
            if (err != null) {
                setProgress(sessionId, "FAILED", "转写失败", 0, error = err.removePrefix("ERR:"))
            } else {
                transaction {
                    ClassRecordings.update({ ClassRecordings.sessionId eq sessionId }) { it[transcript] = "" }
                }
                setProgress(sessionId, "EMPTY", "没有识别到讲课内容", 100)
            }
            println("ASR-EMPTY: $sessionId err=$err")
            return
        }

        setProgress(sessionId, null, "合并中", 70)
        val merged = mergeTracks(tracks)
        setProgress(sessionId, null, "生成纪要", 80)
        val notes = (services.ai as? com.kewu.recserver.services.ai.MaasAiService)?.classNotes(courseName, merged).orEmpty()
            .takeUnless { it.startsWith("（AI ") }
        transaction {
            ClassRecordings.update({ ClassRecordings.sessionId eq sessionId }) {
                it[transcriptStatus] = "DONE"
                it[transcript] = merged
                it[summary] = notes?.ifBlank { null }
                it[stage] = "完成"
                it[progress] = 100
                it[asrError] = null
            }
        }
        println("ASR-DONE: $sessionId tracks=${tracks.size} chars=${merged.length} notes=${notes?.length ?: 0}")

        // 课堂专注分析：只看学生端，已分析过的不重复
        val lecture = notes?.ifBlank { null } ?: merged.take(3000)
        for (t in tracks) {
            if (t.meta.role.lowercase() in setOf("teacher", "教师")) continue
            val r = rows.firstOrNull { it[RecordingTranscripts.recordingId] == t.meta.recordingId }
            if (r?.get(RecordingTranscripts.focusVerdict) != null) continue
            runCatching { analyzeFocus(courseName, lecture, t) }.onFailure { println("FOCUS-ERROR: ${t.meta.recordingId} $it") }
        }
    }

    private data class Track(val meta: RecordingMeta, val segments: List<SisTranscriptionService.Segment>)

    private fun saveTranscript(
        sessionId: String, courseId: String, m: RecordingMeta,
        segs: List<SisTranscriptionService.Segment>?, error: String?,
    ) {
        val now = LocalDateTime.now().format(fmt)
        val text = segs?.joinToString("\n") { it.text }
        val segJson = segs?.let { list ->
            JsonArray(list.map { s -> JsonArray(listOf(JsonPrimitive(s.startMs), JsonPrimitive(s.endMs), JsonPrimitive(s.text))) }).toString()
        }
        val name = m.displayName ?: m.username ?: transaction {
            Recordings.selectAll().where { Recordings.id eq m.recordingId }.firstOrNull()?.get(Recordings.studentName)
        }
        transaction {
            val exists = RecordingTranscripts.selectAll().where { RecordingTranscripts.recordingId eq m.recordingId }.any()
            if (exists) {
                RecordingTranscripts.update({ RecordingTranscripts.recordingId eq m.recordingId }) {
                    it[status] = if (segs != null) "DONE" else "FAILED"
                    it[transcript] = text ?: "ERR:${error.orEmpty()}".take(500)
                    it[segments] = segJson
                    it[updatedAt] = now
                }
            } else {
                RecordingTranscripts.insert {
                    it[recordingId] = m.recordingId
                    it[RecordingTranscripts.sessionId] = sessionId
                    it[RecordingTranscripts.courseId] = courseId
                    it[role] = m.role.take(16)
                    it[username] = m.username
                    it[displayName] = name?.take(128)
                    it[url] = m.storedUrl.orEmpty().take(1024)
                    it[status] = if (segs != null) "DONE" else "FAILED"
                    it[transcript] = text ?: "ERR:${error.orEmpty()}".take(500)
                    it[segments] = segJson
                    it[updatedAt] = now
                }
            }
        }
    }

    private fun decodeSegments(raw: String?): List<SisTranscriptionService.Segment> = runCatching {
        Json.parseToJsonElement(raw ?: return emptyList()).jsonArray.map { e ->
            val a = e.jsonArray
            SisTranscriptionService.Segment(a[0].jsonPrimitive.long, a[1].jsonPrimitive.long, a[2].jsonPrimitive.content)
        }
    }.getOrDefault(emptyList())

    /**
     * 互补合并：每份录音的片段换算成绝对时间（录音结束时刻 − 时长 + 片段偏移）。
     * 以第一条（主录音）为骨架，其他录音只补骨架没覆盖到的时间段（前后各留 8 秒容差，避免重复句），最后按时间排序拼接。
     */
    private fun mergeTracks(tracks: List<Track>): String {
        if (tracks.size == 1) return tracks[0].segments.joinToString("\n") { it.text }
        data class Abs(val start: Long, val end: Long, val text: String)
        fun absOf(t: Track): List<Abs> {
            val durMs = ((t.meta.durationSec ?: 0.0) * 1000).toLong()
            val lastSeg = t.segments.maxOf { it.endMs }
            val endAt = t.meta.endedAtMs ?: 0L
            val startAt = if (endAt > 0) endAt - maxOf(durMs, lastSeg) else 0L
            return t.segments.map { Abs(startAt + it.startMs, startAt + it.endMs, it.text) }
        }
        // 没有结束时刻的老录音无法对齐：退化为只用主录音
        val alignable = tracks.filter { it.meta.endedAtMs != null }
        val base = tracks[0]
        if (base.meta.endedAtMs == null || alignable.size < 2) return base.segments.joinToString("\n") { it.text }
        val tolerance = 8_000L
        val covered = mutableListOf<LongRange>()
        val out = mutableListOf<Abs>()
        absOf(base).forEach { out += it; covered += (it.start - tolerance)..(it.end + tolerance) }
        for (t in alignable.drop(1)) {
            for (a in absOf(t)) {
                val mid = (a.start + a.end) / 2
                if (covered.none { mid in it }) {
                    out += a
                    covered += (a.start - tolerance)..(a.end + tolerance)
                }
            }
        }
        return out.sortedBy { it.start }.joinToString("\n") { it.text }
    }

    private suspend fun analyzeFocus(courseName: String, lecture: String, t: Track) {
        val maas = services.ai as? com.kewu.recserver.services.ai.MaasAiService ?: return
        val text = t.segments.joinToString("\n") { it.text }
        if (text.length < 20) return
        val raw = maas.focusAnalysis(courseName, lecture, text)
        val obj = runCatching {
            val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
            Json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
        }.getOrNull() ?: return
        val verdict = obj["verdict"]?.jsonPrimitive?.content?.takeIf { it in setOf("认真", "疑似开小差", "疑似聊天") } ?: return
        val evidence = obj["evidence"]?.jsonPrimitive?.content.orEmpty().take(200)
        val confidence = obj["confidence"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt()?.coerceIn(0, 100)
        transaction {
            RecordingTranscripts.update({ RecordingTranscripts.recordingId eq t.meta.recordingId }) {
                it[focusVerdict] = verdict
                it[focusEvidence] = evidence
                it[focusConfidence] = confidence
            }
        }
    }

    fun list(courseId: String): List<ClassRecordingView> = transaction {
        // 教师信息取课程的教学班关系；不用 ClassStates（它只保存“当前课”，会随下一次上课覆盖）。
        val teachingClass = Classes.selectAll().where { Classes.courseId eq courseId }.firstOrNull()
        val courseTeacher = Courses.selectAll().where { Courses.id eq courseId }.firstOrNull()?.get(Courses.teacher).orEmpty()
        val teacherId = teachingClass?.get(Classes.teacherId)
        val teacherName = teachingClass?.get(Classes.teacherName)?.ifBlank { null } ?: courseTeacher.ifBlank { null }

        ClassRecordings.selectAll().where { ClassRecordings.courseId eq courseId }
            .orderBy(ClassRecordings.decidedAt, SortOrder.DESC)
            .map {
                ClassRecordingView(
                    sessionId = it[ClassRecordings.sessionId], courseId = it[ClassRecordings.courseId],
                    primaryRecordingId = it[ClassRecordings.primaryRecordingId], primaryUrl = it[ClassRecordings.primaryUrl],
                    strategy = it[ClassRecordings.strategy], candidateCount = it[ClassRecordings.candidateCount],
                    endReason = it[ClassRecordings.endReason], decidedAt = it[ClassRecordings.decidedAt],
                    transcriptStatus = it[ClassRecordings.transcriptStatus], durationSec = it[ClassRecordings.durationSec],
                    teacherId = teacherId, teacherName = teacherName,
                    transcript = it[ClassRecordings.transcript],
                    summary = it[ClassRecordings.summary], asrError = it[ClassRecordings.asrError],
                    stage = it[ClassRecordings.stage], progress = it[ClassRecordings.progress]
                )
            }
    }

    /** 教师端：一节课所有端上传的录音 + 各自转写状态 + 课堂专注结论。 */
    @Serializable
    data class SessionRecordingView(
        val recordingId: String,
        val role: String,
        val username: String? = null,
        val name: String? = null,
        val url: String,
        val durationSec: Double? = null,
        val createdAt: String = "",
        val score: Double? = null,
        val transcriptStatus: String? = null,
        val focusVerdict: String? = null,
        val focusEvidence: String? = null,
        val focusConfidence: Int? = null
    )

}
