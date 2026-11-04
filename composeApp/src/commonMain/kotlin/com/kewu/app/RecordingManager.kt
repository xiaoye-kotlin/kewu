package com.kewu.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock

/**
 * 录音 / 上传状态机 —— 进程级单例（2026-10-19 修「旋屏后录音中断」）。
 *
 * 背景：录音曾由 `ClassroomPage.RecordingOverlay` 的 `remember` 持有——旋屏
 * （Activity 重建）或切 tab（Composition 销毁）都会把 UI 状态打回 Idle，
 * 观感「录音结束了」；而 AudioRecorder 实例其实还在后台写文件（麦克风
 * 指示灯不灭、PCM 继续落盘）= 真实泄漏。且回到页面时新旧两个录音器并存。
 *
 * 现在引擎 + 状态都收在本单例，UI（RecordingOverlay）只是观察者 + 发起点：
 * 旋屏 / 切 tab / 页面销毁都不影响录音进程，回到页面状态原样恢复。
 * 计时器也在单例内（与 UI 生命周期无关），不会因为页面不在前台就停。
 *
 * ★ 2026-11-04 大改造（文档六十六节）：
 *  - 分段上传：录音不停，每 [SEGMENT_SECS] 秒按文件偏移从持续写入的原始音频
 *    文件切一段 → 客户端包 WAV 头 → 入队上传；一节课 90 分钟只产生 ~9 段，
 *    单段 ~19MB（16kHz）/ ~53MB（44.1kHz），远离 300MB 上限。
 *  - 断点续传：队列持久化到 kv [QUEUE_KEY]，失败 30s 指数退避（封顶 5 分钟），
 *    App 启动 [resumePendingUploads] 自动续传；退出软件 / 断网都不丢段。
 *  - 教师融合（finalize）后后端封口：段上传收到 SESSION_SEALED → [sealSession]
 *    丢弃本会话全部剩余段，「这节课算结束，没传完的不再传」。
 *  - 签到自动开录：[autoStartAfterCheckIn]；不再因上传状态阻塞新的录音。
 */
sealed interface RecordingStatus {
    /** 待录音（或上一次结果已被下一次开始覆盖）。 */
    data object Idle : RecordingStatus

    /** 录音中；elapsedSec 由单例内计时器每秒 +1。 */
    data class Recording(val elapsedSec: Int) : RecordingStatus

    /** 已停止，尾段入队/上传中；durationSec = 刚才这一段的时长。队列清空后转 Success。 */
    data class Uploading(val durationSec: Int) : RecordingStatus

    /** 上传成功。 */
    data class Success(val recordingId: String) : RecordingStatus

    /** 失败；filePath 非空表示本地文件还在，可重试上传。 */
    data class Error(val msg: String, val durationSec: Int, val filePath: String?) : RecordingStatus
}

/**
 * 一段待上传的录音（分段上传队列项，kv [QUEUE_KEY] 持久化，App 重启后续传）。
 */
data class PendingUpload(
    val id: String,            // "$segGroup#$segIndex"，去重键
    val filePath: String,      // 段 WAV 临时文件（上传成功 / 丢弃后删除）
    val classId: String,
    val sessionId: String,
    val segGroup: String,      // 同一次录音共享："$sessionId-$deviceId-$recStartMs"，后端拼接用
    val segIndex: Int,
    val durationSec: Int,
    val role: String,
    val at: Long,              // 入队时刻 epoch ms
    val attempts: Int = 0,     // 已失败次数（退避计算用）
)

/**
 * 一条本地录音上传记录（kv `kewu_rec_history` 持久化，最多保留 [RecordingManager.HISTORY_LIMIT] 条）。
 * 成功项带 8081 返回的 recordingId / url / 质量分 / 电平 / 信噪比，供「录音管理」展示与多端融合；
 * 失败项保留本地 [filePath]，可在录音管理里手动重传。
 * 2026-11-04：分段上传的录音在停止时先落一条汇总记录（uploaded=false），队列全部传完后更新为 uploaded=true。
 */
data class RecordingRecord(
    val localId: String,
    val classId: String,
    val sessionId: String,
    val role: String,
    val durationSec: Int,
    val sizeBytes: Long,
    val at: Long,
    val uploaded: Boolean,
    val recordingId: String = "",
    val url: String = "",
    val score: Double = 0.0,
    val avgLevelDb: Double? = null,
    val snrDb: Double? = null,
    val filePath: String? = null,
    /** 录音所属账号（2026-11-02：录音管理只显示本人的，切账号不串）。旧记录加载时归给当时登录的账号。 */
    val owner: String = "",
)

/** 东八区当天日期 `yyyy-MM-dd`（commonMain 无 kotlinx-datetime，手算公历；课堂场景只在国内）。 */
fun todayCn(epochMs: Long = Clock.System.now().toEpochMilliseconds()): String {
    val days = (epochMs + 8 * 3_600_000L).floorDiv(86_400_000L)
    // Howard Hinnant civil_from_days
    val z = days + 719_468
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + if (m <= 2) 1 else 0
    fun two(v: Long) = if (v < 10) "0$v" else "$v"
    return "$y-${two(m)}-${two(d)}"
}

object RecordingManager {

    const val HISTORY_LIMIT = 50
    private const val HISTORY_KEY = "kewu_rec_history"
    /** 分段上传队列 kv key（App 重启恢复续传）。 */
    private const val QUEUE_KEY = "kewu_upload_queue"
    /** 已被教师 finalize 封口的会话 kv key（逗号分隔，封顶 50 个）。 */
    private const val SEALED_KEY = "kewu_sealed_sessions"
    /** 每 10 分钟切一段上传。 */
    const val SEGMENT_SECS = 600
    private val json = Json { ignoreUnknownKeys = true }

    private val _history = MutableStateFlow(loadHistory())
    /** 本地录音上传记录（新的在前）。 */
    val history: StateFlow<List<RecordingRecord>> = _history.asStateFlow()

    private val _pending = MutableStateFlow(loadQueue())
    /** 待上传的录音段队列（新的在后，串行消费；UI 可显示「排队中 N 段」）。 */
    val pendingUploads: StateFlow<List<PendingUpload>> = _pending.asStateFlow()

    /** 只取当前账号的录音。 */
    fun mine(all: List<RecordingRecord> = _history.value): List<RecordingRecord> {
        val me = KewuApi.username.orEmpty()
        return all.filter { it.owner == me }
    }

    /**
     * 单线程作用域：开始 / 停止 / 计时 / 切分 / 上传队列全部串行执行，免锁免竞态
     * （start 是挂起函数，连点两下也不会起两个录音器）。
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(1),
    )

    /** 引擎懒构造：首次 start 时才建（appContext 已由 MainActivity.onCreate 注入）。 */
    private val audio by lazy { AudioRecorder(platformAppContext()) }

    private val _status = MutableStateFlow<RecordingStatus>(RecordingStatus.Idle)
    val status: StateFlow<RecordingStatus> = _status.asStateFlow()

    /** 当前录音绑定的课程（上传归属用）。 */
    private var classId: String? = null
    private var ticker: Job? = null

    // ---- 2026-11-04 分段上传：一次录音的运行时上下文 ----
    /** 传给 startRecording 的输出路径（Android/Desktop=.pcm；iOS 实际写 .wav）。 */
    private var recOutPath: String? = null
    /** 本次录音的分组 key（段上传 segGroup 用）。 */
    private var recSegGroup: String? = null
    /** 原始音频数据流已切到的偏移（不含平台文件头）。 */
    private var nextDataOffset = 0L
    /** 下一段的序号。 */
    private var segIndex = 0
    /** 本次录音开始时刻 epoch ms（文件命名 / segGroup 用）。 */
    private var recStartMs = 0L
    /** 本次录音已切出的总字节（汇总记录 sizeBytes 用）。 */
    private var recTotalBytes = 0L
    /** 最近一次段上传成功的结果（汇总记录 + Success 状态用）。 */
    private var lastSegResult: RecordingUploadResult? = null
    /** 最近一次停止的录音在 history 里的 localId（= 其 PCM 路径，汇总记录更新用）。 */
    private var lastRecLocalId: String? = null
    /** 队列消费协程（空队列时退出，入队再踢活）。 */
    private var queueJob: Job? = null
    /** 已封口会话（教师 finalize 后，本端不再传该会话的任何段）。 */
    private var sealedSessions: Set<String> = loadSealed()

    val isRecording: Boolean get() = _status.value is RecordingStatus.Recording

    fun hasPermission(): Boolean = audio.hasPermission()

    fun isSealed(sessionId: String): Boolean = sessionId in sealedSessions

    /** 「开始」已受理但录音器还没起来的窗口期标记（主线程同步置位，防连点）。 */
    @kotlin.concurrent.Volatile private var starting = false

    /**
     * 开始录音；录音中 / 正在启动一律忽略（防连点）。
     * ★ 2026-11-04：上传中（Uploading）不再阻塞开始——旧录音的段队列在后台继续传，
     *   新录音直接起；开始即重置分段上下文。
     */
    fun start(classId: String) {
        val st = _status.value
        if (starting || st is RecordingStatus.Recording) return
        starting = true
        sawClassOn = false
        AudioPlayback.stop() // 外放的回放会被麦克风录进去
        this.classId = classId
        scope.launch {
            if (audio.isRecording()) { starting = false; return@launch }
            val out = platformCacheDir() + "/kewu_rec_${Clock.System.now().toEpochMilliseconds()}.pcm"
            val ok = audio.startRecording(out)
            starting = false
            if (ok) {
                val now = Clock.System.now().toEpochMilliseconds()
                recOutPath = out
                recStartMs = now
                nextDataOffset = 0
                segIndex = 0
                recTotalBytes = 0
                val sid = KewuApi.classSessionId(classId, todayCn(now))
                recSegGroup = "$sid-${platformDeviceId()}-$now"
                lastSegResult = null
                _status.value = RecordingStatus.Recording(0)
                startTicker()
            } else {
                recOutPath = null
                _status.value = RecordingStatus.Error(
                    msg = if (audio.hasPermission()) "录音启动失败，请稍后重试"
                    else "需要麦克风权限才能录音，请在系统弹窗中允许",
                    durationSec = 0,
                    filePath = null,
                )
            }
        }
    }

    /**
     * 签到成功自动开录（2026-11-04，任务 #14）：静默、幂等。
     * 没权限不弹错误（不打扰签到流程），返回 false 由调用方决定是否提示。
     */
    fun autoStartAfterCheckIn(courseId: String) {
        val st = _status.value
        if (st is RecordingStatus.Recording || starting) return
        if (!audio.hasPermission()) return
        val sid = KewuApi.classSessionId(courseId, todayCn())
        if (isSealed(sid)) return // 本节课已被教师融合封口
        start(courseId)
    }

    /** 停止录音 → 切尾段 → 入队上传（段队列后台串行消费）。 */
    fun stopAndUpload() {
        val cur = _status.value
        if (cur !is RecordingStatus.Recording) return
        ticker?.cancel()
        heartbeat("stopped")
        val cid = classId.orEmpty()
        val elapsed = cur.elapsedSec
        scope.launch {
            _status.value = RecordingStatus.Uploading(elapsed)
            val wavPath = audio.stopRecording()
            // 尾段：此时原始音频文件已完整（Android/Desktop 的 PCM 在 stop 后不删，见 AudioRecorder）
            cutSegment()
            // 清理：原始 PCM / iOS wav 由 cutSegment 读完后删掉；整轨 WAV（stopRecording 的返回）不再需要
            recOutPath?.let { raw -> platformDeleteFile(audio.rawAudioPath(raw)) }
            wavPath?.let { platformDeleteFile(it) }
            // 汇总记录：先落「上传中」，队列全部传完再由 maybeCompleteSummary 置 uploaded=true
            val pcmPath = recOutPath
            lastRecLocalId = pcmPath
            if (pcmPath != null && cid.isNotBlank()) {
                val sid = KewuApi.classSessionId(cid, todayCn(recStartMs))
                putHistory(RecordingRecord(
                    localId = pcmPath, classId = cid, sessionId = sid,
                    role = if (kvGet("kewu_role") == ROLE_TEACHER) "teacher" else "student",
                    durationSec = elapsed, sizeBytes = recTotalBytes, at = recStartMs,
                    uploaded = false, owner = KewuApi.username.orEmpty(),
                ))
            }
            recOutPath = null
            maybeCompleteSummary(recSegGroup)
        }
    }

    /** 退出登录等场景：正在录音就停止并丢弃（不上传，避免录音挂在下一个账号名下）；待传段一并清空。 */
    fun cancelIfRecording() {
        if (_status.value is RecordingStatus.Recording || starting) {
            ticker?.cancel()
            heartbeat("stopped")
            scope.launch {
                audio.stopRecording()?.let { platformDeleteFile(it) }
                recOutPath?.let { raw -> platformDeleteFile(audio.rawAudioPath(raw)) }
                recOutPath = null
            }
        }
        // 队列里的段属于即将退出的账号：清掉，别让它们挂到新账号名下
        if (_pending.value.isNotEmpty()) {
            _pending.value.forEach { platformDeleteFile(it.filePath) }
            _pending.value = emptyList()
            saveQueue()
        }
        if (_status.value !is RecordingStatus.Idle) _status.value = RecordingStatus.Idle
    }

    /** 重试上传（Error 且本地文件还在时有效；或队列有待传段时踢活队列）。 */
    fun retryUpload() {
        val cur = _status.value
        if (cur is RecordingStatus.Error && cur.filePath != null) {
            val path = cur.filePath
            scope.launch { wholeFileUpload(path, cur.durationSec) }
            return
        }
        if (_pending.value.isNotEmpty()) kickQueue()
    }

    /** UI 侧前置校验失败（如无可绑定课程 / 权限被拒）也走统一错误通道。 */
    fun showError(msg: String) {
        if (_status.value is RecordingStatus.Recording) return
        _status.value = RecordingStatus.Error(msg, 0, null)
    }

    // ---- 分段上传：切段 / 队列 ----

    /**
     * 从持续写入的原始音频文件按偏移切一段 → 包 WAV 头落盘 → 入队。
     * 已切到文件末尾则 no-op；会话已封口则直接丢弃剩余。
     */
    private fun cutSegment() {
        val out = recOutPath ?: return
        val cid = classId ?: return
        val group = recSegGroup ?: return
        val sid = KewuApi.classSessionId(cid, todayCn(recStartMs))
        if (isSealed(sid)) { nextDataOffset = Long.MAX_VALUE; return }
        val rawPath = audio.rawAudioPath(out)
        val base = audio.rawDataOffset().toLong()
        val fileSize = platformFileSize(rawPath)
        val dataEnd = fileSize - base
        if (dataEnd <= nextDataOffset) return
        val len = (dataEnd - nextDataOffset).toInt()
        if (len <= 0) return
        val pcm = platformReadFileRange(rawPath, base + nextDataOffset, len) ?: return
        val sr = audio.getSampleRate()
        val durSec = pcm.size / 2 / sr
        nextDataOffset = dataEnd
        if (durSec <= 0) return // 不足 1 个采样，不值得单独成段
        val header = wavHeader(pcm.size, sr)
        val bytes = ByteArray(header.size + pcm.size)
        header.copyInto(bytes)
        pcm.copyInto(bytes, header.size)
        val segWav = platformCacheDir() + "/kewu_seg_${recStartMs}_${segIndex}.wav"
        if (!platformWriteFile(segWav, bytes)) return
        recTotalBytes += pcm.size
        val idx = segIndex++
        val now = Clock.System.now().toEpochMilliseconds()
        _pending.value = _pending.value + PendingUpload(
            id = "$group#$idx",
            filePath = segWav, classId = cid, sessionId = sid,
            segGroup = group, segIndex = idx,
            durationSec = durSec,
            role = if (kvGet("kewu_role") == ROLE_TEACHER) "teacher" else "student",
            at = now,
        )
        saveQueue()
        kickQueue()
    }

    /** 入队后踢活队列消费协程（已在跑则忽略）。 */
    private fun kickQueue() {
        if (queueJob?.isActive == true) return
        queueJob = scope.launch { processQueue() }
    }

    /** App 启动时调用（登录态就绪后）：恢复续传上次未传完的段（任务 #15）。 */
    fun resumePendingUploads() {
        if (_pending.value.isNotEmpty()) kickQueue()
    }

    /**
     * 队列串行消费：逐段上传，成功删文件取下一段；失败 30s 起指数退避（封顶 5 分钟）；
     * 收到 SESSION_SEALED → 封口会话、丢弃该会话全部剩余段。
     */
    private suspend fun processQueue() {
        while (true) {
            val item = _pending.value.firstOrNull() ?: break
            if (isSealed(item.sessionId)) {
                platformDeleteFile(item.filePath)
                _pending.value = _pending.value.drop(1)
                saveQueue()
                continue
            }
            val filename = "kewu_${item.classId}_s${item.segIndex}.wav"
            when (val r = KewuApi.uploadRecordingSegment(
                item.filePath, filename, item.classId, item.sessionId,
                item.segGroup, item.segIndex, item.durationSec.toDouble(), item.role, item.at,
                sampleRate = audio.getSampleRate(),
            )) {
                is KewuApi.SegmentUploadResult.Ok -> {
                    lastSegResult = r.result
                    platformDeleteFile(item.filePath)
                    _pending.value = _pending.value.drop(1)
                    saveQueue()
                    maybeCompleteSummary(item.segGroup)
                }
                KewuApi.SegmentUploadResult.Sealed -> {
                    sealSession(item.sessionId)
                    break
                }
                is KewuApi.SegmentUploadResult.Failed -> {
                    val updated = item.copy(attempts = item.attempts + 1)
                    _pending.value = listOf(updated) + _pending.value.drop(1)
                    saveQueue()
                    delay((30_000L shl minOf(updated.attempts - 1, 3)).coerceAtMost(300_000L))
                }
            }
        }
    }

    /**
     * 教师 finalize 封口（或本端收到 SESSION_SEALED）：
     * 丢弃该会话全部待传段、记入 sealed 集合持久化——「这节课算结束，没传完的不再传」。
     */
    private fun sealSession(sessionId: String) {
        if (sessionId !in sealedSessions) {
            sealedSessions = (sealedSessions + sessionId).toList().takeLast(50).toSet()
            runCatching {
                kvSet(SEALED_KEY, sealedSessions.joinToString(","))
            }
        }
        val (mine, others) = _pending.value.partition { it.sessionId == sessionId }
        mine.forEach { platformDeleteFile(it.filePath) }
        if (mine.isNotEmpty()) {
            _pending.value = others
            saveQueue()
        }
        heartbeat("stopped")
        if (_status.value is RecordingStatus.Uploading) _status.value = RecordingStatus.Idle
        _events.value = "本节课已由老师结束，未上传完成的录音已自动放弃"
    }

    /** 队列里该录音的段全部传完：汇总记录置 uploaded=true；状态 Uploading → Success。 */
    private fun maybeCompleteSummary(group: String?) {
        if (group == null) return
        if (_pending.value.any { it.segGroup == group }) return
        val res = lastSegResult
        val pcmPath = lastRecLocalId
        if (pcmPath != null) {
            _history.value = _history.value.map { r ->
                if (r.localId == pcmPath && !r.uploaded) r.copy(
                    uploaded = true,
                    recordingId = res?.recordingId.orEmpty(),
                    url = res?.url.orEmpty(),
                    score = res?.score ?: 0.0,
                    avgLevelDb = res?.avgLevelDb,
                    snrDb = res?.snrDb,
                ) else r
            }
            saveHistory()
        }
        if (_status.value is RecordingStatus.Uploading) {
            _status.value = RecordingStatus.Success(res?.recordingId.orEmpty())
        }
    }

    /** 44 字节 WAV 头（PCM 16bit 单声道）。 */
    private fun wavHeader(dataSize: Int, sampleRate: Int): ByteArray {
        val channels = 1
        val bits = 16
        val byteRate = sampleRate * channels * bits / 8
        fun i32(v: Int) = byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
        )
        fun i16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        val h = ByteArray(44)
        "RIFF".encodeToByteArray().copyInto(h, 0)
        i32(36 + dataSize).copyInto(h, 4)
        "WAVE".encodeToByteArray().copyInto(h, 8)
        "fmt ".encodeToByteArray().copyInto(h, 12)
        i32(16).copyInto(h, 16)
        i16(1).copyInto(h, 20)
        i16(channels).copyInto(h, 22)
        i32(sampleRate).copyInto(h, 24)
        i32(byteRate).copyInto(h, 28)
        i16(channels * bits / 8).copyInto(h, 32)
        i16(bits).copyInto(h, 34)
        "data".encodeToByteArray().copyInto(h, 36)
        i32(dataSize).copyInto(h, 40)
        return h
    }

    // ---- 旧整轨上传通道（Error 重试 / 录音管理手动重传沿用） ----

    private suspend fun wholeFileUpload(path: String, durationSec: Int) {
        _status.value = RecordingStatus.Uploading(durationSec)
        try {
            val size = platformFileSize(path)
            if (size <= 0) {
                _status.value = RecordingStatus.Error("录音文件读取失败，请稍后重试", durationSec, path)
                return
            }
            val cid = classId ?: "unknown"
            val now = Clock.System.now().toEpochMilliseconds()
            // 同课同日的教师端 / 学生端落到同一协同会话，8081 才能按会话做多端择优融合
            val sid = KewuApi.classSessionId(cid, todayCn(now))
            val role = if (kvGet("kewu_role") == ROLE_TEACHER) "teacher" else "student"
            val filename = "kewu_${cid}_$now.wav"
            val res = KewuApi.uploadRecording(path, filename, cid, sid, durationSec.toDouble(), role, endedAt = now)
            val record = RecordingRecord(
                localId = path, classId = cid, sessionId = sid, role = role,
                durationSec = durationSec, sizeBytes = size, at = now,
                uploaded = res != null,
                recordingId = res?.recordingId.orEmpty(), url = res?.url.orEmpty(),
                score = res?.score ?: 0.0, avgLevelDb = res?.avgLevelDb, snrDb = res?.snrDb,
                filePath = if (res == null) path else null,
                owner = KewuApi.username.orEmpty(),
            )
            putHistory(record)
            if (res != null) platformDeleteFile(path) // 已上传成功，清掉本地 WAV（旧版只增不删）
            _status.value = if (res != null) RecordingStatus.Success(res.recordingId)
            else RecordingStatus.Error("录音上传失败，请稍后重试", durationSec, path)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = RecordingStatus.Error("录音上传失败，请稍后重试", durationSec, path)
        }
    }

    /** 录音管理页「重传」：按记录重新上传本地文件（文件已被系统清理时返回失败文案）。 */
    fun retryRecord(record: RecordingRecord, onDone: (String?) -> Unit) {
        val path = record.filePath ?: return onDone("本地录音文件已不存在")
        scope.launch {
            if (platformFileSize(path) <= 0) { onDone("本地录音文件已不存在"); return@launch }
            val res = KewuApi.uploadRecording(
                path, "kewu_${record.classId}_${record.at}.wav", record.classId,
                record.sessionId, record.durationSec.toDouble(), record.role, endedAt = record.at,
            )
            if (res == null) { onDone("录音上传失败，请稍后重试"); return@launch }
            platformDeleteFile(path)
            putHistory(record.copy(
                uploaded = true, recordingId = res.recordingId, url = res.url, score = res.score,
                avgLevelDb = res.avgLevelDb, snrDb = res.snrDb, filePath = null,
            ))
            onDone(null)
        }
    }

    /** 从本地记录里移除一条（不删 OBS 上的文件）。 */
    fun removeRecord(localId: String) {
        _history.value = _history.value.filterNot { it.localId == localId }
        saveHistory()
    }

    private fun putHistory(r: RecordingRecord) {
        _history.value = (listOf(r) + _history.value.filterNot { it.localId == r.localId }).take(HISTORY_LIMIT)
        saveHistory()
    }

    private fun saveHistory() {
        val arr = JsonArray(_history.value.map { r ->
            JsonObject(buildMap {
                put("localId", JsonPrimitive(r.localId)); put("classId", JsonPrimitive(r.classId))
                put("sessionId", JsonPrimitive(r.sessionId)); put("role", JsonPrimitive(r.role))
                put("durationSec", JsonPrimitive(r.durationSec)); put("sizeBytes", JsonPrimitive(r.sizeBytes))
                put("at", JsonPrimitive(r.at)); put("uploaded", JsonPrimitive(r.uploaded))
                put("recordingId", JsonPrimitive(r.recordingId)); put("url", JsonPrimitive(r.url))
                put("score", JsonPrimitive(r.score))
                r.avgLevelDb?.let { put("avgLevelDb", JsonPrimitive(it)) }
                r.snrDb?.let { put("snrDb", JsonPrimitive(it)) }
                r.filePath?.let { put("filePath", JsonPrimitive(it)) }
                put("owner", JsonPrimitive(r.owner))
            })
        })
        // 存储失败（如 desktop kv 超限）只影响本地记录，绝不能把「已上传成功」误报成上传失败
        runCatching { kvSet(HISTORY_KEY, arr.toString()) }
    }

    private fun loadHistory(): List<RecordingRecord> = try {
        val raw = kvGet(HISTORY_KEY)
        if (raw.isNullOrBlank()) emptyList() else (json.parseToJsonElement(raw) as JsonArray).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = o[k]?.jsonPrimitive?.content.orEmpty()
            fun d(k: String) = o[k]?.jsonPrimitive?.content?.toDoubleOrNull()
            RecordingRecord(
                localId = s("localId"), classId = s("classId"), sessionId = s("sessionId"), role = s("role"),
                durationSec = s("durationSec").toIntOrNull() ?: 0, sizeBytes = s("sizeBytes").toLongOrNull() ?: 0L,
                at = s("at").toLongOrNull() ?: 0L, uploaded = s("uploaded") == "true",
                recordingId = s("recordingId"), url = s("url"), score = d("score") ?: 0.0,
                avgLevelDb = d("avgLevelDb"), snrDb = d("snrDb"), filePath = o["filePath"]?.jsonPrimitive?.content,
                owner = o["owner"]?.jsonPrimitive?.content ?: kvGet("kewu_username").orEmpty(),
            )
        }
    } catch (_: Exception) { emptyList() }

    // ---- 队列 / 封口持久化 ----

    private fun saveQueue() {
        val arr = JsonArray(_pending.value.map { p ->
            JsonObject(buildMap {
                put("id", JsonPrimitive(p.id)); put("filePath", JsonPrimitive(p.filePath))
                put("classId", JsonPrimitive(p.classId)); put("sessionId", JsonPrimitive(p.sessionId))
                put("segGroup", JsonPrimitive(p.segGroup)); put("segIndex", JsonPrimitive(p.segIndex))
                put("durationSec", JsonPrimitive(p.durationSec)); put("role", JsonPrimitive(p.role))
                put("at", JsonPrimitive(p.at)); put("attempts", JsonPrimitive(p.attempts))
            })
        })
        // desktop kv 有 8KB 上限：存不下就放弃持久化（内存队列仍继续传，本次进程内不受影响）
        runCatching { kvSet(QUEUE_KEY, arr.toString()) }
    }

    private fun loadQueue(): List<PendingUpload> = try {
        val raw = kvGet(QUEUE_KEY)
        if (raw.isNullOrBlank()) emptyList()
        else (json.parseToJsonElement(raw) as JsonArray).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = o[k]?.jsonPrimitive?.content.orEmpty()
            PendingUpload(
                id = s("id"), filePath = s("filePath"), classId = s("classId"),
                sessionId = s("sessionId"), segGroup = s("segGroup"),
                segIndex = s("segIndex").toIntOrNull() ?: 0,
                durationSec = s("durationSec").toIntOrNull() ?: 0,
                role = s("role"),
                at = s("at").toLongOrNull() ?: 0L,
                attempts = s("attempts").toIntOrNull() ?: 0,
            )
        }.filter { platformFileSize(it.filePath) > 0 } // 临时段文件已被清理的项直接丢弃
    } catch (_: Exception) { emptyList() }

    private fun loadSealed(): Set<String> = try {
        kvGet(SEALED_KEY)?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    } catch (_: Exception) { emptySet() }

    /** 当前录音所属的课堂会话 id（雷达按它查同堂设备）；没在录为 null。 */
    fun currentSessionId(): String? = classId?.takeIf { isRecording }?.let { KewuApi.classSessionId(it, todayCn()) }

    private fun heartbeat(status: String, elapsedSec: Int = 0) {
        val cid = classId ?: return
        val sid = KewuApi.classSessionId(cid, todayCn())
        // 心跳走独立协程，网络慢不拖住计时
        netScope.launch { KewuApi.sendRecordingHeartbeat(sid, status, elapsedSec = elapsedSec) }
    }

    /** 本次录音期间是否见过「上课中」：见过后再变成「已下课」才自动停（从没开过课的不误停）。 */
    private var sawClassOn = false

    private val _events = MutableStateFlow<String?>(null)

    /** 一次性提示（如「老师已下课，录音已自动上传」），页面展示后调用 [consumeEvent]。 */
    val events: StateFlow<String?> = _events.asStateFlow()
    fun consumeEvent() { _events.value = null }

    private fun checkClassEnded() {
        val cid = classId ?: return
        netScope.launch {
            val st = KewuApi.getClassState(cid)?.state ?: return@launch
            if (st == "ON") sawClassOn = true
            else if (sawClassOn && st == "OFF" && isRecording) {
                _events.value = "老师已下课，课堂录音已自动停止并上传"
                stopAndUpload()
            }
        }
    }
    private val netScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (_status.value is RecordingStatus.Recording) {
                // 每 5 秒一次设备心跳（第 0 秒先报一次，带已录时长），供课堂录音雷达显示「谁在录、录了多久」；
                // 每 10 秒查一次课堂状态：老师下课（上课中 → 已下课）就自动停止并上传（2026-10-26）；
                // 每 SEGMENT_SECS 秒按文件偏移切一段入队上传（2026-11-04 分段上传）
                val cur0 = _status.value
                if (cur0 is RecordingStatus.Recording) {
                    if (cur0.elapsedSec % 5 == 0) heartbeat("recording", cur0.elapsedSec)
                    if (cur0.elapsedSec % 10 == 5) checkClassEnded()
                    if (cur0.elapsedSec > 0 && cur0.elapsedSec % SEGMENT_SECS == 0) cutSegment()
                }
                delay(1000)
                val cur = _status.value
                if (cur is RecordingStatus.Recording) {
                    _status.value = RecordingStatus.Recording(cur.elapsedSec + 1)
                }
            }
        }
    }
}
