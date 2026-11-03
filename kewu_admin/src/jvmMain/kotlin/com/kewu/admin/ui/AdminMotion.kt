package com.kewu.admin.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// ───────────────────────── 总控端动效体系（对齐学生端 KewuMotion / ClassroomPage 入场范式） ─────────────────────────
//
// ★ 液态玻璃硬约束：玻璃件及其所有祖先只允许 placement 位移（offset lambda），
//   禁止 alpha / scale / graphicsLayer / clip 层；非玻璃件（coui Card、文字、普通 Box）可以淡入 + 微缩放。
//   - 非玻璃元素：[riseEnter]（位移 + 透明度 + 0.96→1 缩放）
//   - 玻璃元素：[glassEnter]（只位移）+ [glassCondenseBlur]/tint × p（纯 draw 参数）

/** 入场进度：保持 0 → 按 [index] 错峰后用低阻尼弹簧跑到 1（带轻微回弹）。 */
@Composable
fun rememberEnterProgress(
    index: Int = 0,
    baseDelayMs: Long = 40L,
    stepMs: Long = 45L,
    maxDelayMs: Long = 420L,
): Float {
    var target by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        delay((baseDelayMs + index * stepMs).coerceAtMost(maxDelayMs))
        target = 1f
    }
    val p by animateFloatAsState(
        targetValue = target,
        // 显式小阈值：默认阈值会截断回弹过冲（学生端踩过的坑）
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 360f, visibilityThreshold = 0.0005f),
        label = "adminEnter$index",
    )
    return p
}

/** 非玻璃元素入场：上浮 + 淡入 + 轻微回弹缩放。只能用在不含玻璃的子树上。 */
fun Modifier.riseEnter(p: Float, travelDp: Float = 22f): Modifier {
    val v = p.coerceIn(0f, 1f)
    return this
        .offset { IntOffset(0, ((1f - p) * travelDp).dp.roundToPx()) }
        .alpha(v)
        .scale(0.97f + 0.03f * v)
}

/** 玻璃入场：只做位移（纵向）。 */
fun Modifier.glassEnter(p: Float, travelDp: Float = 26f): Modifier =
    this.offset { IntOffset(0, ((1f - p) * travelDp).dp.roundToPx()) }

/** 玻璃入场：只做位移（横向，导航栏从左侧滑入）。 */
fun Modifier.glassEnterX(p: Float, travelDp: Float = -36f): Modifier =
    this.offset { IntOffset(((1f - p) * travelDp).dp.roundToPx(), 0) }

/** 玻璃「凝结」：blur 从 4dp 插值到目标值。 */
fun glassCondenseBlur(p: Float, targetDp: Float): Dp {
    val v = p.coerceIn(0f, 1f)
    return (4f + (targetDp - 4f) * v).dp
}

/** 悬停 / 按压状态（桌面端交互反馈）。 */
class AdminInteraction(
    val source: MutableInteractionSource,
    val hovered: Boolean,
    val pressed: Boolean,
)

@Composable
fun rememberAdminInteraction(): AdminInteraction {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    return AdminInteraction(source, hovered, pressed)
}

/** 按压回弹缩放（非玻璃元素用；玻璃内部的子元素也可以用，它不是玻璃的祖先）。 */
@Composable
fun Modifier.pressScale(pressed: Boolean, pressedScale: Float = 0.965f): Modifier {
    val s by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        spring(dampingRatio = 0.6f, stiffness = 520f),
        label = "pressScale",
    )
    return this.scale(s)
}

/**
 * 页面切换宿主：新页从导航方向（下 / 上）弹簧滑入。
 * 只用 offset 位移 —— 页面里的玻璃头部 / 浮条因此合法；非玻璃内容由各自的 [riseEnter] 负责淡入。
 */
@Composable
fun <S> AdminPageHost(
    target: S,
    order: (S) -> Int,
    modifier: Modifier = Modifier,
    content: @Composable (S) -> Unit,
) {
    var shown by remember { mutableStateOf(target) }
    var dir by remember { mutableIntStateOf(1) }
    val shift = remember { Animatable(0f) }
    LaunchedEffect(target) {
        if (target == shown) return@LaunchedEffect
        dir = if (order(target) >= order(shown)) 1 else -1
        shown = target
        shift.snapTo(dir.toFloat())
        shift.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 300f, visibilityThreshold = 0.0005f))
    }
    Box(modifier) {
        key(shown) {
            Box(Modifier.fillMaxSize().offset { IntOffset(0, (shift.value * 48.dp.toPx()).roundToInt()) }) {
                content(shown)
            }
        }
    }
}
