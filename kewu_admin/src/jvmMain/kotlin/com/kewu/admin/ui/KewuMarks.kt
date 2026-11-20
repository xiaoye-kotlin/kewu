package com.kewu.admin.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// ───────────────────────── ★ 课悟标志性图形（总控端同款复刻） ─────────────────────────
//
// 源自 composeApp commonMain 的 ClassroomPage.kt（学生端/教师端首页水印同款）。
// 总控端是独立 Gradle 项目，无法跨模块引用，故将这两个纯原生 Compose 组件原样复刻到此。
// 动画与绘制参数与学生端/教师端保持逐字一致（400ms 扫弧 + 70ms 提笔 + 240ms 写符号），
// 这是全 app 统一的「状态语言」标志性动效，无特殊理由不要改动参数。

/**
 * 课悟的标志性图形：**大半圆包着中间的勾／叉**。
 *
 * `startAngle = 135f, sweepAngle = 270f`（Compose 里 0° = 3 点钟、角度顺时针增大），
 * 即从「左下」顺时针绕一整圈到「右下」—— 缺口落在**正下方**（45°..135°）。
 *
 * ★ 出场动效（默认开）：先扫出大半圆（400ms），提笔呼吸 70ms，再一笔「写」出中间的勾/叉（240ms），
 *   像有人用笔当场画出来。首页大卡水印就是用它。
 *
 * ★ 勾必须单笔绘制：整条折线合成一个 Path，用 PathMeasure 按进度截取后一次 drawPath，
 *   拐角由 join=Round 一笔带过——不会出现「两根线接出来」的深色接缝。
 */
@Composable
fun ThreeQuarterCheck(
    size: Dp,
    color: Color,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    alpha: Float = 1f,
    cross: Boolean = false,
    /** 是否播放「画出来」的出场动效（默认开 —— 标志性动效） */
    animateEntry: Boolean = true,
) {
    val arcP = remember { Animatable(if (animateEntry) 0f else 1f) }
    val markP = remember { Animatable(if (animateEntry) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!animateEntry) return@LaunchedEffect
        arcP.animateTo(1f, tween(durationMillis = 400, easing = FastOutSlowInEasing))
        delay(70) // 提笔呼吸：圆画完，笔挪到中央再落笔
        markP.animateTo(1f, tween(durationMillis = 240, easing = LinearOutSlowInEasing))
    }
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val sw = s * 0.072f
        val inset = sw / 2f
        val c = color.copy(alpha = alpha)
        // ① 大半圆：sweep 从 0 扫到 270°（起点 135°，缺口落在正下方）
        drawArc(
            color = c,
            startAngle = 135f,
            sweepAngle = 270f * arcP.value,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(s - sw, s - sw),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        val t = markP.value
        if (t <= 0f) return@Canvas
        val mw = sw * 1.25f
        val style = Stroke(width = mw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        if (cross) {
            // 叉 = 两刀（\ 再 /），各占一半进度
            fun stroke(ax: Float, ay: Float, bx: Float, by: Float, prog: Float) {
                val p = prog.coerceIn(0f, 1f)
                if (p <= 0f) return
                val path = Path().apply { moveTo(ax, ay); lineTo(bx, by) }
                val measure = PathMeasure().apply { setPath(path, false) }
                val seg = Path()
                if (measure.getSegment(0f, measure.length * p, seg, true)) {
                    drawPath(seg, c, style = style)
                }
            }
            stroke(s * 0.35f, s * 0.35f, s * 0.65f, s * 0.65f, t * 2f)
            stroke(s * 0.65f, s * 0.35f, s * 0.35f, s * 0.65f, (t - 0.5f) * 2f)
        } else {
            // 勾 = 一笔折线（短边下笔 → 长边提起），PathMeasure 按进度截取后一次绘制
            val path = Path().apply {
                moveTo(s * 0.31f, s * 0.50f)
                lineTo(s * 0.44f, s * 0.63f)
                lineTo(s * 0.69f, s * 0.37f)
            }
            val measure = PathMeasure().apply { setPath(path, false) }
            val seg = Path()
            if (measure.getSegment(0f, measure.length * t.coerceIn(0f, 1f), seg, true)) {
                drawPath(seg, c, style = style)
            }
        }
    }
}

/**
 * 警示感叹号水印：大半圆弧 + 中央"!"，配琥珀黄。
 * 与 [ThreeQuarterCheck] 同尺寸、同款「先画圆再画符号」描绘动效，可直接替换状态块水印。
 */
@Composable
fun ExclaimMark(
    size: Dp,
    color: Color,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    alpha: Float = 1f,
    animateEntry: Boolean = true,
) {
    val arcP = remember { Animatable(if (animateEntry) 0f else 1f) }
    val markP = remember { Animatable(if (animateEntry) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!animateEntry) return@LaunchedEffect
        arcP.animateTo(1f, tween(durationMillis = 400, easing = FastOutSlowInEasing))
        delay(70)
        markP.animateTo(1f, tween(durationMillis = 240, easing = LinearOutSlowInEasing))
    }
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val sw = s * 0.072f
        val inset = sw / 2f
        val c = color.copy(alpha = alpha)
        drawArc(
            color = c,
            startAngle = 135f,
            sweepAngle = 270f * arcP.value,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(s - sw, s - sw),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        val t = markP.value
        if (t <= 0f) return@Canvas
        val cx = s * 0.5f
        val mw = sw * 1.35f
        val style = Stroke(width = mw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val barP = (t / 0.55f).coerceIn(0f, 1f)
        if (barP > 0f) {
            val bar = Path().apply { moveTo(cx, s * 0.30f); lineTo(cx, s * 0.56f) }
            val measure = PathMeasure().apply { setPath(bar, false) }
            val seg = Path()
            if (measure.getSegment(0f, measure.length * barP, seg, true)) drawPath(seg, c, style = style)
        }
        val dotP = ((t - 0.55f) / 0.45f).coerceIn(0f, 1f)
        if (dotP > 0f) {
            drawCircle(color = c, radius = sw * 0.80f * dotP, center = Offset(cx, s * 0.70f))
        }
    }
}
