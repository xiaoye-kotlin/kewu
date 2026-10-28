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
 */
sealed interface RecordingStatus {
    /** 待录音（或上一次结果已被下一次开始覆盖）。 */
    data object Idle : RecordingStatus

    /** 录音中；elapsedSec 由单例内计时器每秒 +1。 */
    data class Recording(val elapsedSec: Int) : RecordingStatus

    /** 已停止，WAV 生成 + 上传中；durationSec = 刚才这一段的时长。 */
    data class Uploading(val durationSec: Int) : RecordingStatus

    /** 上传成功。 */
    data class Success(val recordingId: String) : RecordingStatus

    /** 失败；filePath 非空表示本地文件还在，可重试上传。 */
    data class Error(val msg: String, val durationSec: Int, val filePath: String?) : RecordingStatus
}

/**
 * 一条本地录音上传记录（kv `kewu_rec_history` 持久化，最多保留 [RecordingManager.HISTORY_LIMIT] 条）。
 * 成功项带 8081 返回的 recordingId / url / 质量分 / 电平 / 信噪比，供「录音管理」展示与多端融合；
 * 失败项保留本地 [filePath]，可在录音管理里手动重传。
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
    private val json = Json { ignoreUnknownKeys = true }

    private val _history = MutableStateFlow(loadHistory())
    /** 本地录音上传记录（新的在前）。 */
    val history: StateFlow<List<RecordingRecord>> = _history.asStateFlow()

    /**
     * 单线程作用域：开始 / 停止 / 计时 / 上传全部串行执行，免锁免竞态
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

    val isRecording: Boolean get() = _status.value is RecordingStatus.Recording

    fun hasPermission(): Boolean = audio.hasPermission()

    /** 「开始」已受理但录音器还没起来的窗口期标记（主线程同步置位，防连点）。 */
    @kotlin.concurrent.Volatile private var starting = false

    /**
     * 开始录音；录音中 / 正在启动 / 上传中一律忽略（防连点）。
     * ★ 2026-10-26：旧版状态要等 startRecording 返回才变 Recording，第二下点击会再排一次启动，
     *   被录音器拒绝后把状态改成 Error——界面显示「启动失败」，麦克风却还开着、且再也停不掉。
     */
    fun start(classId: String) {
        val st = _status.value
        if (starting || st is RecordingStatus.Recording || st is RecordingStatus.Uploading) return
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
                _status.value = RecordingStatus.Recording(0)
                startTicker()
            } else {
                _status.value = RecordingStatus.Error(
                    msg = if (audio.hasPermission()) "录音启动失败，请稍后重试"
                    else "需要麦克风权限才能录音，请在系统弹窗中允许",
                    durationSec = 0,
                    filePath = null,
                )
            }
        }
    }

    /** 停止录音 → 生成 WAV → 自动上传。 */
    fun stopAndUpload() {
        val cur = _status.value
        if (cur !is RecordingStatus.Recording) return
        ticker?.cancel()
        heartbeat("stopped")
        scope.launch {
            _status.value = RecordingStatus.Uploading(cur.elapsedSec)
            val path = audio.stopRecording()
            if (path == null) {
                _status.value = RecordingStatus.Error("录音文件生成失败，请稍后重试", cur.elapsedSec, null)
            } else {
                upload(path, cur.elapsedSec)
            }
        }
    }

    /** 退出登录等场景：正在录音就停止并丢弃（不上传，避免录音挂在下一个账号名下）。 */
    fun cancelIfRecording() {
        if (_status.value !is RecordingStatus.Recording && !starting) return
        ticker?.cancel()
        heartbeat("stopped")
        scope.launch {
            audio.stopRecording()?.let { platformDeleteFile(it) }
            _status.value = RecordingStatus.Idle
        }
    }

    /** 重试上传（Error 且本地文件还在时有效）。 */
    fun retryUpload() {
        val cur = _status.value
        if (cur !is RecordingStatus.Error) return
        val path = cur.filePath ?: return
        scope.launch { upload(path, cur.durationSec) }
    }

    /** UI 侧前置校验失败（如无可绑定课程 / 权限被拒）也走统一错误通道。 */
    fun showError(msg: String) {
        if (_status.value is RecordingStatus.Recording) return
        _status.value = RecordingStatus.Error(msg, 0, null)
    }

    private suspend fun upload(path: String, durationSec: Int) {
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
            val res = KewuApi.uploadRecording(path, filename, cid, sid, durationSec.toDouble(), role)
            val record = RecordingRecord(
                localId = path, classId = cid, sessionId = sid, role = role,
                durationSec = durationSec, sizeBytes = size, at = now,
                uploaded = res != null,
                recordingId = res?.recordingId.orEmpty(), url = res?.url.orEmpty(),
                score = res?.score ?: 0.0, avgLevelDb = res?.avgLevelDb, snrDb = res?.snrDb,
                filePath = if (res == null) path else null,
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
                record.sessionId, record.durationSec.toDouble(), record.role,
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
            )
        }
    } catch (_: Exception) { emptyList() }

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
                // 每 10 秒查一次课堂状态：老师下课（上课中 → 已下课）就自动停止并上传（2026-10-26）
                val cur0 = _status.value
                if (cur0 is RecordingStatus.Recording) {
                    if (cur0.elapsedSec % 5 == 0) heartbeat("recording", cur0.elapsedSec)
                    if (cur0.elapsedSec % 10 == 5) checkClassEnded()
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
