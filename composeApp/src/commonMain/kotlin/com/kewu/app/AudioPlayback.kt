package com.kewu.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 平台音频播放器（2026-10-26：录音管理 / 作业录音附件直接在 App 内试听）。
 * [prepare] 的 source 可以是 http(s) 链接（边下边播）或本地文件路径；所有回调都在主线程。
 * 一个实例只播一个 source，换曲目由 [AudioPlayback] 释放旧实例再建新的。
 */
expect class PlatformAudioPlayer() {
    fun prepare(source: String, onReady: (durationMs: Long) -> Unit, onComplete: () -> Unit, onError: (String) -> Unit)
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun positionMs(): Long
    fun release()
}

/**
 * 全局唯一的播放会话：同一时刻只播一条，点另一条会先停掉当前这条。
 * UI 用 [state] 判断「这一条是不是正在播」（按调用方给的 key，例如 `rec:<localId>`、`att:<附件 id>`）。
 */
object AudioPlayback {
    enum class Phase { IDLE, LOADING, PLAYING, PAUSED, ERROR }

    data class State(
        val key: String? = null,
        val phase: Phase = Phase.IDLE,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: PlatformAudioPlayer? = null
    private var ticker: Job? = null

    /** 点播放键：同一条在播 → 暂停；暂停中 → 继续；否则从头播放 [source]。 */
    fun toggle(key: String, source: String) {
        val s = _state.value
        val p = player
        if (s.key == key && p != null) {
            when (s.phase) {
                Phase.PLAYING -> { p.pause(); ticker?.cancel(); _state.update { it.copy(phase = Phase.PAUSED, positionMs = p.positionMs()) }; return }
                Phase.PAUSED -> { p.play(); _state.update { it.copy(phase = Phase.PLAYING) }; startTicker(p); return }
                Phase.LOADING -> return
                else -> Unit
            }
        }
        start(key, source)
    }

    /** 拖动进度条松手后跳转（仅对当前这一条生效）。 */
    fun seek(key: String, ms: Long) {
        val p = player ?: return
        if (_state.value.key != key) return
        val target = ms.coerceIn(0, _state.value.durationMs.coerceAtLeast(0))
        p.seekTo(target)
        _state.update { it.copy(positionMs = target) }
    }

    /** 停止并清空（退出登录、开始录音时调用）。 */
    fun stop() {
        releasePlayer()
        _state.value = State()
    }

    /** 只有当前播的是 [key] 才停（页面 / 行销毁时调用，不误停别处正在播的）。 */
    fun stopIf(key: String) {
        if (_state.value.key == key) stop()
    }

    /** 当前播的 key 以 [prefix] 开头才停（如离开录音管理页时停掉所有 `rec:` 回放）。 */
    fun stopIfPrefix(prefix: String) {
        if (_state.value.key?.startsWith(prefix) == true) stop()
    }

    private fun start(key: String, source: String) {
        releasePlayer()
        _state.value = State(key = key, phase = Phase.LOADING)
        val p = try { PlatformAudioPlayer() } catch (_: Throwable) {
            _state.value = State(key = key, phase = Phase.ERROR, error = "当前设备不支持播放")
            return
        }
        player = p
        p.prepare(
            source = source,
            onReady = { dur ->
                if (player === p) {
                    _state.update { it.copy(phase = Phase.PLAYING, durationMs = dur.coerceAtLeast(0), positionMs = 0) }
                    p.play()
                    startTicker(p)
                }
            },
            onComplete = {
                if (player === p) {
                    ticker?.cancel()
                    p.seekTo(0)
                    _state.update { it.copy(phase = Phase.PAUSED, positionMs = 0) }
                }
            },
            onError = { msg ->
                if (player === p) {
                    releasePlayer()
                    _state.value = State(key = key, phase = Phase.ERROR, error = msg)
                }
            },
        )
    }

    private fun startTicker(p: PlatformAudioPlayer) {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && player === p) {
                val pos = p.positionMs()
                _state.update { it.copy(positionMs = pos) }
                delay(200)
            }
        }
    }

    private fun releasePlayer() {
        ticker?.cancel()
        ticker = null
        val p = player
        player = null
        try { p?.release() } catch (_: Throwable) { }
    }
}

/** `mm:ss`（超过 1 小时为 `h:mm:ss`），播放器时间标签用。 */
fun formatPlayTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    fun two(v: Long) = if (v < 10) "0$v" else "$v"
    return if (s >= 3600) "${s / 3600}:${two(s % 3600 / 60)}:${two(s % 60)}" else "${two(s / 60)}:${two(s % 60)}"
}
