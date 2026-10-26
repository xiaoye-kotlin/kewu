package com.kewu.app.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// ───────────────────────── Telegram 风格切页引擎（2026-10-26 动画专项） ─────────────────────────
//
// 两种风格共用一套状态机（from 页 / to 页 / 进度 p）：
// - Pager（tab 间）：两页整屏联动，手指拖多少页面走多少；松手按位移（>40%）或甩动速度决定翻过去还是弹回；
//   第一页往右、最后一页往左拖是橡皮筋阻尼，松手带过冲回弹。
// - Push（下钻）：新页整屏从右推入、压在上面，底页向左 1/4 视差并压暗，上页左缘带一道柔和投影；
//   支持右滑跟手返回（swipeEnabled），松手同样按位移 / 速度决定返回还是弹回。
// 全部是弹簧驱动，动画中途再点 / 再拖都从当前位置和速度接着走，不会跳帧。
//
// coui 液态玻璃约束（交接文档第七节）：页面只做 placement 位移（offset lambda）+ 纯绘制（压暗 / 投影），
// 不引入 alpha / graphicsLayer；容器是 Box（每页都按满宽测量，没有 Row 并排时后续页 maxWidth=0 的老坑）。

internal enum class SlideStyle { Pager, Push }

/** tab 切页：轻微过冲（≈1%），落定干脆。 */
private val PagerSpring: AnimationSpec<Float> = spring(dampingRatio = 0.84f, stiffness = 380f)
/** 下钻推入 / 返回。 */
private val PushSpring: AnimationSpec<Float> = spring(dampingRatio = 0.86f, stiffness = 420f)
/** 两端橡皮筋松手：低阻尼，回弹明显。 */
private val RubberSpring: AnimationSpec<Float> = spring(dampingRatio = 0.5f, stiffness = 420f)

private const val PUSH_PARALLAX = 0.25f   // 底页视差：被盖住时向左移 1/4 屏
private const val PUSH_DIM = 0.14f        // 底页最大压暗
private const val COMMIT_FRACTION = 0.4f  // 松手时拖过 40% 即翻页
private const val FLING_SCREENS = 0.9f    // 甩动速度阈值（屏宽 / 秒）

@Stable
private class SlideHostState<S>(
    initial: S,
    private val style: SlideStyle,
    private val scope: CoroutineScope,
) {
    var from by mutableStateOf(initial)
    var to by mutableStateOf<S?>(null)
    /** +1：to 页在右侧（前进）；-1：to 页在左侧（后退）。 */
    var dir by mutableIntStateOf(1)
    var p by mutableFloatStateOf(0f)
    /** 无邻页时的橡皮筋位移（px，仅 Pager）。 */
    var rubber by mutableFloatStateOf(0f)

    private var target: S = initial
    private var job: Job? = null
    private var dragOffset = 0f
    private val spec get() = if (style == SlideStyle.Pager) PagerSpring else PushSpring

    private fun finish(dest: S) {
        from = dest
        to = null
        p = 0f
        rubber = 0f
    }

    /** 外部状态变了（点 tab / 点进下钻 / 返回键）→ 弹簧过去。 */
    fun go(dest: S, isForward: (S, S) -> Boolean) {
        if (dest == target && (job?.isActive == true || (to == null && from == dest))) return
        target = dest
        job?.cancel()
        val cur = to
        // 上一段已过半：直接落到它，再从那里出发（快速连点时不会出现两页互相拉扯）
        if (cur != null && cur != dest && p >= 0.5f) finish(cur)
        if (dest == from) {
            job = scope.launch { settleBack(0f) }
            return
        }
        if (to != dest) {
            to = dest
            dir = if (isForward(from, dest)) 1 else -1
        }
        job = scope.launch { settleForward(0f) }
    }

    private suspend fun settleForward(velocity: Float) {
        val dest = to ?: return
        rubber = 0f
        animate(p, 1f, velocity, spec) { v, _ -> p = v }
        finish(dest)
    }

    private suspend fun settleBack(velocity: Float) {
        if (rubber != 0f) {
            animate(rubber, 0f, 0f, RubberSpring) { v, _ -> rubber = v }
        }
        if (to != null) {
            animate(p, 0f, velocity, spec) { v, _ -> p = v }
            to = null
            p = 0f
        }
    }

    /** 按下开始拖：返回 false 表示本次不接管（Push 风格动画进行中不打断）。 */
    fun dragStart(width: Float): Boolean {
        if (width <= 0f) return false
        if (style == SlideStyle.Push && to != null) return false
        job?.cancel()
        dragOffset = if (to != null) -dir * p * width else rubber
        return true
    }

    fun drag(dx: Float, width: Float, neighbor: (S, Int) -> S?) {
        dragOffset += dx
        if (style == SlideStyle.Push) dragOffset = dragOffset.coerceAtLeast(0f) // 下钻只允许右滑返回
        if (dragOffset == 0f) {
            p = 0f
            rubber = 0f
            return
        }
        val want = if (dragOffset < 0f) 1 else -1
        if (to == null || dir != want) {
            to = neighbor(from, want)
            dir = want
        }
        if (to == null) {
            p = 0f
            rubber = if (style == SlideStyle.Pager) rubberBand(dragOffset, width) else 0f
        } else {
            rubber = 0f
            p = (abs(dragOffset) / width).coerceIn(0f, 1f)
        }
    }

    /** 松手：返回翻到的页（null = 弹回原页）。 */
    fun release(velocityX: Float, width: Float): S? {
        val dest = to
        if (dest == null) {
            job = scope.launch { settleBack(0f) }
            return null
        }
        // vp > 0：朝 to 页方向甩
        val vp = (-velocityX * dir / width).coerceIn(-6f, 6f)
        val commit = if (abs(vp) > FLING_SCREENS) vp > 0f else p > COMMIT_FRACTION
        if (commit) {
            target = dest
            job = scope.launch { settleForward(vp) }
            return dest
        }
        job = scope.launch { settleBack(vp) }
        return null
    }

    /** 页面横向位置（px）。 */
    fun xOf(page: S, width: Float): Float {
        val t = to ?: return rubber
        val isFrom = page == from
        if (!isFrom && page != t) return 0f
        return when (style) {
            SlideStyle.Pager -> if (isFrom) -dir * p * width + rubber else dir * (1f - p) * width
            SlideStyle.Push -> if (dir == 1) {
                if (isFrom) -PUSH_PARALLAX * width * p else width * (1f - p)
            } else {
                if (isFrom) width * p else -PUSH_PARALLAX * width * (1f - p)
            }
        }
    }

    /** Push：底页之上画压暗 + 上页左缘投影（纯绘制，不进离屏层）。 */
    fun DrawScope.drawUnder(page: S, width: Float) {
        val t = to ?: return
        val under = if (dir == 1) from else t
        if (page != under) return
        val covered = (if (dir == 1) p else 1f - p).coerceIn(0f, 1f)
        if (covered <= 0f) return
        drawRect(Color.Black, alpha = PUSH_DIM * covered)
        val top = if (dir == 1) t else from
        val edge = xOf(top, width) - xOf(under, width)
        val shadowW = 14.dp.toPx()
        if (edge > 0f && edge < size.width + shadowW) {
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.10f)),
                    startX = edge - shadowW,
                    endX = edge,
                ),
                topLeft = Offset(edge - shadowW, 0f),
                size = Size(shadowW, size.height),
            )
        }
    }
}

/** iOS / Telegram 同款橡皮筋：越拉越重，最多约 0.3 屏。 */
private fun rubberBand(offset: Float, width: Float): Float {
    val d = abs(offset) / width
    val r = (1f - 1f / (d * 1.2f + 1f)) * width * 0.5f
    return if (offset < 0f) -r else r
}

/**
 * Telegram 风格切页容器：替代 `AnimatedContent` 做 tab 切页与下钻转场。
 *
 * @param isForward from→to 是否「前进」（新页从右边进来）。
 * @param swipeEnabled 是否接管横向拖动（Pager：tab 间跟手；Push：右滑返回）。
 * @param swipeNeighbor 拖动方向上的邻页：dir=+1 左拖要露出的页，dir=-1 右拖要露出的页；null = 没有。
 * @param onSwipeCommit 拖动松手翻页后回调，调用方在这里把外部状态改成该页。
 * @param onSwipeProgress 拖动中逐帧回调（from、to、进度），tab 用它带着导航泡泡一起走。
 * @param onSwipeEnd 松手回调（翻到的页；null = 弹回）。
 */
@Composable
internal fun <S> KewuSlideHost(
    targetState: S,
    modifier: Modifier = Modifier,
    style: SlideStyle = SlideStyle.Push,
    isForward: (from: S, to: S) -> Boolean,
    swipeEnabled: Boolean = false,
    swipeNeighbor: (from: S, dir: Int) -> S? = { _, _ -> null },
    onSwipeCommit: (S) -> Unit = {},
    onSwipeProgress: ((from: S, to: S?, p: Float) -> Unit)? = null,
    onSwipeEnd: ((committed: S?) -> Unit)? = null,
    content: @Composable (S) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state = remember { SlideHostState(targetState, style, scope) }
    val forward by rememberUpdatedState(isForward)
    val neighbor by rememberUpdatedState(swipeNeighbor)
    val commit by rememberUpdatedState(onSwipeCommit)
    val progress by rememberUpdatedState(onSwipeProgress)
    val end by rememberUpdatedState(onSwipeEnd)
    LaunchedEffect(targetState) { state.go(targetState, forward) }

    var width by remember { mutableFloatStateOf(0f) }
    val bg = COUITheme.colorScheme.surface
    val gesture = if (swipeEnabled) {
        Modifier.pointerInput(state) {
            val tracker = VelocityTracker()
            var accepted = false
            fun finishDrag(vx: Float) {
                if (!accepted) return
                accepted = false
                val to = state.release(vx, width)
                if (to != null) commit(to)
                end?.invoke(to)
            }
            detectHorizontalDragGestures(
                onDragStart = {
                    tracker.resetTracking()
                    accepted = state.dragStart(width)
                },
                onDragEnd = { finishDrag(tracker.calculateVelocity().x) },
                onDragCancel = { finishDrag(0f) },
                onHorizontalDrag = { change, dx ->
                    if (accepted) {
                        tracker.addPosition(change.uptimeMillis, change.position)
                        change.consume()
                        state.drag(dx, width, neighbor)
                        progress?.invoke(state.from, state.to, state.p)
                    }
                },
            )
        }
    } else {
        Modifier
    }

    Box(modifier.onSizeChanged { width = it.width.toFloat() }.then(gesture)) {
        val from = state.from
        val to = state.to
        // 绘制顺序 = 子节点顺序：Push 时底页先画、上页后画
        val pages = when {
            to == null -> listOf(from)
            style == SlideStyle.Push && state.dir == -1 -> listOf(to, from)
            else -> listOf(from, to)
        }
        for (page in pages) {
            key(page) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .offset { IntOffset(state.xOf(page, width).roundToInt(), 0) }
                        .background(bg)
                        .then(
                            if (style == SlideStyle.Push) {
                                Modifier.drawWithContent {
                                    drawContent()
                                    with(state) { drawUnder(page, width) }
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    content(page)
                }
            }
        }
    }
}
