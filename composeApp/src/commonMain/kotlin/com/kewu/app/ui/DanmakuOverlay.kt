package com.kewu.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.isActive
import kotlin.math.abs

// 弹幕渲染（2026-11-05）：一个 Canvas 画完全部弹幕，不建几十个 Text 组件。
//
// 性能三条硬规矩（对应需求）：
//   1. 动画由 withFrameNanos 驱动，帧时间写进一个只在 draw 阶段读取的 State —— 变化只让
//      Canvas 重绘，不触发 Compose 重组；播放器 250ms 才报一次进度，靠帧时间插值成连续时钟。
//   2. 只取 [currentTimeMs, currentTimeMs + 8000] 的活跃弹幕，上限 50 条，超出直接丢弃。
//   3. 移动/排版/轨道全在 draw 块里算，measure 结果缓存起来，不每帧重新排版。

/** 弹幕从右到左走完整屏的时长，也等于活跃窗口大小。 */
private const val TRAVEL_MS = 8_000L
/** 同屏上限：活跃弹幕达到 50 条后，新的直接丢弃，防止 Canvas 卡顿。 */
private const val MAX_ON_SCREEN = 50

/** 把 250ms 一跳的播放进度插值成连续时间用的锚点（普通对象，不是 State，不触发重组）。 */
private class ClockAnchor(var videoMs: Long = 0L, var frameNanos: Long = 0L)

/**
 * 弹幕画布：叠在视频画面上即可。
 *
 * @param danmakuPool 全局弹幕池（已按 progress 排序，内部维护 12 分钟滑动窗口）
 * @param currentTimeMs 播放器当前进度（毫秒）
 */
@Composable
fun DanmakuOverlay(
    danmakuPool: DanmakuPool,
    currentTimeMs: Long,
    modifier: Modifier = Modifier,
    maxOnScreen: Int = MAX_ON_SCREEN,
) {
    val measurer = rememberTextMeasurer(cacheSize = 128)
    // 帧时钟：只在 Canvas 的 draw 块里读，改动只 invalidate draw 阶段
    val frameNanos = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (isActive) {
            withFrameNanos { frameNanos.longValue = it }
        }
    }
    val anchor = remember { ClockAnchor() }
    // 排版缓存：同屏最多 50 条，别每帧重新 measure
    val layoutCache = remember { HashMap<String, TextLayoutResult>(128) }

    Canvas(modifier) {
        val nanos = frameNanos.longValue
        if (nanos == 0L) return@Canvas
        // 播放器进度每 250ms 更新一次：更新时重设锚点，中间帧按真实经过的纳秒平滑推进
        if (currentTimeMs != anchor.videoMs) {
            anchor.videoMs = currentTimeMs
            anchor.frameNanos = nanos
        }
        val smoothMs = anchor.videoMs + (nanos - anchor.frameNanos) / 1_000_000L

        // 同屏限制在这里生效：窗口内按时间顺序取前 maxOnScreen 条，其余直接丢弃
        val active = danmakuPool.window(smoothMs, TRAVEL_MS, maxOnScreen)
        if (active.isEmpty()) return@Canvas

        val w = size.width
        val h = size.height
        for (d in active) {
            val fontSize = d.fontsize.coerceIn(18, 36)
            val key = "$fontSize|${d.content}"
            val layout = layoutCache.getOrPut(key) {
                if (layoutCache.size > 256) layoutCache.clear()
                measurer.measure(d.content, TextStyle(fontSize = fontSize.sp, color = Color.Black))
            }
            val textW = layout.size.width.toFloat()
            val textH = layout.size.height.toFloat()

            // 8 秒行程：remaining = progress - now，从 8000 递减到 0，
            // x 从右边缘 w 一路减到 -textW（从右向左移出屏幕）
            val remaining = (d.progress - smoothMs).coerceIn(0L, TRAVEL_MS)
            val t = 1f - remaining.toFloat() / TRAVEL_MS
            val x = w - t * (w + textW)
            if (x > w || x + textW < 0f) continue

            // 稳定轨道：同一条弹幕每帧落在同一行，不会上下闪跳；字号越大轨道越高
            val trackH = (fontSize.sp.toPx() * 1.55f).coerceAtLeast(24.dp.toPx())
            val tracks = (h / trackH).toInt().coerceAtLeast(1)
            val track = (abs(d.progress * 31L + d.content.hashCode()) % tracks).toInt()
            val y = (track * trackH).coerceIn(0f, (h - textH).coerceAtLeast(0f))

            // 颜色是 0xRRGGBB，补上不透明的 alpha；先画 1px 黑描边，视频亮画面上也看得清
            val argb = 0xFF000000.toInt() or (d.color and 0xFFFFFF)
            drawText(layout, topLeft = Offset(x + 1f, y + 1f))
            drawText(layout, color = Color(argb), topLeft = Offset(x, y))
        }
    }
}
