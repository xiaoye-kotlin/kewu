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

object RecordingManager {

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

    /** 开始录音；已在录音中则忽略（防连点）。 */
    fun start(classId: String) {
        if (_status.value is RecordingStatus.Recording) return
        this.classId = classId
        scope.launch {
            val out = platformCacheDir() + "/kewu_rec_${Clock.System.now().toEpochMilliseconds()}.pcm"
            if (audio.startRecording(out)) {
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
            val bytes = platformReadFile(path)
            if (bytes == null) {
                _status.value = RecordingStatus.Error("录音文件读取失败，请稍后重试", durationSec, path)
                return
            }
            val cid = classId ?: "unknown"
            val filename = "kewu_${cid}_${Clock.System.now().toEpochMilliseconds()}.wav"
            val res = KewuApi.uploadRecording(bytes, filename, cid, null, durationSec.toDouble())
            _status.value = if (res != null) RecordingStatus.Success(res.recordingId)
            else RecordingStatus.Error("录音上传失败，请稍后重试", durationSec, path)
        } catch (e: Exception) {
            _status.value = RecordingStatus.Error("录音上传失败，请稍后重试", durationSec, path)
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (_status.value is RecordingStatus.Recording) {
                delay(1000)
                val cur = _status.value
                if (cur is RecordingStatus.Recording) {
                    _status.value = RecordingStatus.Recording(cur.elapsedSec + 1)
                }
            }
        }
    }
}
