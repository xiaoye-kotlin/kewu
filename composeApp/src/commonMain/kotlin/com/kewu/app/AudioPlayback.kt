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
 *
 * ★ §88（2026-11-18）片段播放：教师听学生「开小差」录音时只播被判为开小差的那段时间区间，
 * 用 ExoPlayer（各端 PlatformAudioPlayer 实现）的 seekTo 跳到区间起点，ticker 到终点自动暂停。
 * [toggle] / [seekOrStart] 保留原签名（整轨播放）以兼容所有既有调用点。
 */
object AudioPlayback {
    enum class Phase { IDLE, LOADING, PLAYING, PAUSED, ERROR }

    data class State(
        val key: String? = null,
        val phase: Phase = Phase.IDLE,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val error: String? = null,
        /** 片段区间（毫秒）：起点 / 终点。整轨播放时 start=0、end=null。 */
        val clipStartMs: Long = 0,
        val clipEndMs: Long? = null,
    ) {
        val isClip: Boolean get() = clipStartMs > 0 || clipEndMs != null
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private const val LOAD_TIMEOUT_MS = 20_000L
    private var player: PlatformAudioPlayer? = null
    private var ticker: Job? = null
    private var pendingSeekMs: Long? = null

    /** 点播放键（整轨）：同一条在播 → 暂停；暂停中 → 继续；否则从头播放 [source]。 */
    fun toggle(key: String, source: String) = toggleClip(key, source, 0L, null)

    /**
     * 片段播放：只播 [startMs, endMs] 区间（如学生「开小差」片段）。
     * 同一条同一区间在播 → 暂停；暂停中 → 继续（已到末尾则从头重播）；否则从 [startMs] 开始播、到 [endMs] 自动停。
     */
    fun toggleClip(key: String, source: String, startMs: Long, endMs: Long?) {
        val s = _state.value
        val p = player
        val sameClip = s.clipStartMs == startMs && s.clipEndMs == endMs
        if (s.key == key && p != null && sameClip) {
            when (s.phase) {
                Phase.PLAYING -> { p.pause(); ticker?.cancel(); _state.update { it.copy(phase = Phase.PAUSED, positionMs = p.positionMs()) }; return }
                Phase.PAUSED -> {
                    // 已播到片段末尾 → 从头重播，否则接着播
                    if (endMs != null && p.positionMs() >= endMs - 150L) p.seekTo(startMs)
                    p.play(); _state.update { it.copy(phase = Phase.PLAYING) }; startTicker(p); return
                }
                Phase.LOADING -> return
                else -> Unit
            }
        }
        start(key, source, startMs, endMs)
    }

    /** 拖动进度条松手后跳转（仅对当前这一条生效）；片段模式下夹在区间内。 */
    fun seek(key: String, ms: Long) {
        val p = player ?: return
        val s = _state.value
        if (s.key != key) return
        // 2026-11-02：流式 WAV 的时长常拿不到（=0），旧版按 0 夹紧 → 永远跳回开头，表现为「拖不动进度」
        val dur = s.durationMs
        var target = if (dur > 0) ms.coerceIn(0, dur) else ms.coerceAtLeast(0)
        s.clipEndMs?.let { end ->
            val upper = if (dur > 0) minOf(end, dur) else end
            target = target.coerceIn(s.clipStartMs, upper)
        }
        p.seekTo(target)
        _state.update { it.copy(positionMs = target) }
    }

    /** 拖进度：这一条已就绪就直接跳；还没播（或已停）就从 [ms] 处开始播。 */
    fun seekOrStart(key: String, source: String, ms: Long) =
        seekOrStartClip(key, source, ms, 0L, null)

    /** 片段版拖进度。 */
    fun seekOrStartClip(key: String, source: String, ms: Long, clipStartMs: Long, clipEndMs: Long?) {
        val s = _state.value
        if (s.key == key && player != null && (s.phase == Phase.PLAYING || s.phase == Phase.PAUSED)) {
            seek(key, ms)
            return
        }
        if (s.key == key && s.phase == Phase.LOADING) { pendingSeekMs = ms; return }
        start(key, source, clipStartMs, clipEndMs)
        pendingSeekMs = ms
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

    private fun start(key: String, source: String, clipStartMs: Long = 0L, clipEndMs: Long? = null) {
        releasePlayer()
        pendingSeekMs = if (clipStartMs > 0) clipStartMs else null
        _state.value = State(key = key, phase = Phase.LOADING, clipStartMs = clipStartMs, clipEndMs = clipEndMs)
        val p = try { PlatformAudioPlayer() } catch (_: Throwable) {
            _state.value = State(key = key, phase = Phase.ERROR, error = "当前设备不支持播放", clipStartMs = clipStartMs, clipEndMs = clipEndMs)
            return
        }
        player = p
        // 2026-11-02 加载看门狗：网络挂死时部分播放器既不回调就绪也不回调失败，UI 会一直转圈。
        // 20 秒还没就绪就判失败，给「重试」而不是无限等待。
        scope.launch {
            delay(LOAD_TIMEOUT_MS)
            if (player === p && _state.value.phase == Phase.LOADING) {
                releasePlayer()
                _state.value = State(key = key, phase = Phase.ERROR, error = "加载超时，请检查网络后重试", clipStartMs = clipStartMs, clipEndMs = clipEndMs)
            }
        }
        p.prepare(
            source = source,
            onReady = { dur ->
                if (player === p) {
                    val at = (pendingSeekMs ?: clipStartMs).coerceAtLeast(0)
                    pendingSeekMs = null
                    if (at > 0) p.seekTo(at)
                    _state.update { it.copy(phase = Phase.PLAYING, durationMs = dur.coerceAtLeast(0), positionMs = at) }
                    p.play()
                    startTicker(p)
                }
            },
            onComplete = {
                if (player === p) {
                    ticker?.cancel()
                    // 片段播到媒体结尾仍兜底回到区间起点（正常情况 ticker 会先在 end 暂停）
                    val back = _state.value.clipStartMs
                    p.seekTo(back)
                    _state.update { it.copy(phase = Phase.PAUSED, positionMs = back) }
                }
            },
            onError = { msg ->
                if (player === p) {
                    releasePlayer()
                    _state.value = State(key = key, phase = Phase.ERROR, error = msg, clipStartMs = clipStartMs, clipEndMs = clipEndMs)
                }
            },
        )
    }

    private fun startTicker(p: PlatformAudioPlayer) {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && player === p) {
                val pos = p.positionMs()
                val end = _state.value.clipEndMs
                if (end != null && pos >= end) {
                    // 片段到点：暂停并停在终点（再点播放会从起点重播）
                    p.pause()
                    _state.update { it.copy(positionMs = end, phase = Phase.PAUSED) }
                    break
                }
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
