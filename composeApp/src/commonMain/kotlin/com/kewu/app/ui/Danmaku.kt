package com.kewu.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.kewu.app.DanmakuItem
import com.kewu.app.KewuApi
import com.kewu.app.VideoControls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// B站视频弹幕的数据侧（2026-11-05）：分段预加载 + 滑动窗口。
//
// 为什么不一次性把整段视频的弹幕拉下来：B站按 6 分钟一个分段给数据，一部 2 小时的课有 20 段、
// 上万条弹幕，全灌进内存既慢又容易 OOM。这里只留「当前时间前后一小段」的数据：
//   - 进新分段 / 拖进度条 → 拉当前段 + 下一段（下一段保证跨段边界不空屏），500ms 防抖；
//   - 池里按 progress 排序 + 去重，超过当前时间 12 分钟的旧弹幕直接清掉。
// 渲染侧见 DanmakuOverlay.kt：全部用 Canvas 画，不建一堆 Text 组件。
//
// 全 App 只有一个播放宿主（BilibiliPlayerHost），所以这里 remember 出来的池就是全局唯一的弹幕池；
// 换视频 / 关弹幕会清空，不会把上一个视频的弹幕带过来。

/** 一个弹幕分段 = B站的 6 分钟（segmentIndex = currentTimeMs / 360000 + 1，从 1 开始）。 */
const val DANMAKU_SEGMENT_MS = 360_000L
/** 滑动窗口：只保留距离当前播放时间 12 分钟以内的弹幕。 */
const val DANMAKU_KEEP_MS = 12 * 60_000L

/**
 * 全局弹幕池：一个按 progress 升序的 MutableList + 去重集合。
 * 读写都发生在主线程（网络回调回到主协程、Canvas 也在主线程 draw），不需要加锁。
 */
class DanmakuPool {
    private val items = mutableListOf<DanmakuItem>()
    private val seen = HashSet<String>()

    val size: Int get() = items.size

    /** 追加一段数据：按 progress+内容去重（同一毫秒可以有多条不同弹幕，不能只按 progress 去重），再排序。 */
    fun append(incoming: List<DanmakuItem>) {
        if (incoming.isEmpty()) return
        incoming.forEach { d ->
            if (seen.add(keyOf(d))) items.add(d)
        }
        items.sortBy { it.progress }
    }

    /**
     * 活跃窗口 [nowMs, nowMs + windowMs]，最多 limit 条（同屏上限，超出直接丢弃）。
     * 列表有序，用二分找起点，避免每帧从头扫上万条。
     */
    fun window(nowMs: Long, windowMs: Long, limit: Int): List<DanmakuItem> {
        if (items.isEmpty() || limit <= 0) return emptyList()
        val end = nowMs + windowMs
        val out = ArrayList<DanmakuItem>(limit.coerceAtMost(64))
        var i = lowerBound(nowMs)
        while (i < items.size && out.size < limit) {
            val d = items[i]
            if (d.progress > end) break
            out.add(d)
            i++
        }
        return out
    }

    /** 清掉 12 分钟以外的旧弹幕，同时把去重键一起清掉，避免 seen 无限涨。 */
    fun prune(nowMs: Long) {
        if (items.isEmpty()) return
        val cutoff = nowMs - DANMAKU_KEEP_MS
        val it = items.iterator()
        while (it.hasNext()) {
            val d = it.next()
            if (d.progress >= cutoff) break   // 列表有序，后面都更新
            it.remove()
            seen.remove(keyOf(d))
        }
    }

    fun clear() {
        items.clear()
        seen.clear()
    }

    private fun keyOf(d: DanmakuItem) = "${d.progress}|${d.content}"

    /** 第一个 progress >= timeMs 的下标。 */
    private fun lowerBound(timeMs: Long): Int {
        var lo = 0
        var hi = items.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (items[mid].progress < timeMs) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

/**
 * 分段预加载器：只在「进入新分段」或「用户拖了进度条」时安排请求，
 * 因为播放进度每 250ms 就变一次，若每次进度变化都防抖，请求会被不断取消、永远发不出去。
 */
class DanmakuLoader(
    private val pool: DanmakuPool,
    private val scope: CoroutineScope,
) {
    private var debounceJob: Job? = null
    private val loaded = HashSet<Int>()
    private var bvid = ""
    private var lastSegment = -1
    private var lastTimeMs = 0L

    fun onProgress(newBvid: String, cid: Long, timeMs: Long) {
        if (newBvid.isBlank()) return
        if (newBvid != bvid) reset(newBvid)

        val seg = (timeMs / DANMAKU_SEGMENT_MS + 1).toInt()
        // 拖拽判定：时间倒退，或一次跳超过 3 秒（正常轮询是 250ms 一跳）
        val seeked = timeMs + 1_000L < lastTimeMs || timeMs - lastTimeMs > 3_000L
        lastTimeMs = timeMs
        pool.prune(timeMs)

        if (!seeked && seg == lastSegment) return   // 段没变、也没拖动 → 什么都不做
        if (seeked) {
            // 跳走之后只保留附近分段的「已加载」标记，用户再拖回来时会重新拉
            val it = loaded.iterator()
            while (it.hasNext()) if (it.next() !in seg - 1..seg + 2) it.remove()
        }
        lastSegment = seg
        schedule(cid, seg, timeMs)
    }

    /** 500ms 防抖：连续拖动只发最后一次；然后并发拉当前段 + 下一段，追加到池里。 */
    private fun schedule(cid: Long, seg: Int, timeMs: Long) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(500)
            val targets = listOf(seg, seg + 1).filter { it !in loaded }
            if (targets.isEmpty()) return@launch
            // 两段并发，别串行白等一个 RTT
            val results = targets.map { s ->
                async { s to KewuApi.getBilibiliDanmaku(bvid, s, cid) }
            }.awaitAll()
            results.forEach { (s, list) ->
                pool.append(list)
                loaded.add(s)   // 空段也记下来：这一分段确实没弹幕，别反复打接口
            }
            pool.prune(timeMs)
        }
    }

    private fun reset(newBvid: String) {
        debounceJob?.cancel()
        loaded.clear()
        pool.clear()
        bvid = newBvid
        lastSegment = -1
        lastTimeMs = 0L
    }

    fun dispose() {
        debounceJob?.cancel()
    }
}

/**
 * 弹幕层：把池、预加载、渲染组装起来，播放页只要传 bvid / cid / 开关。
 * 播放进度来自 [VideoControls]（250ms 一跳），动画的平滑插值在 DanmakuOverlay 的 Canvas 里做。
 */
@Composable
fun DanmakuLayer(
    bvid: String,
    cid: Long,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val pool = remember(bvid, cid) { DanmakuPool() }
    val scope = rememberCoroutineScope()
    val loader = remember(bvid, cid) { DanmakuLoader(pool, scope) }
    DisposableEffect(loader) { onDispose { loader.dispose() } }

    if (enabled) {
        val timeMs = VideoControls.positionMs
        LaunchedEffect(timeMs) { loader.onProgress(bvid, cid, timeMs) }
        DanmakuOverlay(danmakuPool = pool, currentTimeMs = timeMs, modifier = modifier)
    } else {
        LaunchedEffect(Unit) { pool.clear() }
    }
}
