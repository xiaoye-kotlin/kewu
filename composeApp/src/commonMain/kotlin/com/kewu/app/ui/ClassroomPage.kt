package com.kewu.app.ui

import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.SizeTransform
import com.kewu.app.LockTabSwipe
import androidx.compose.runtime.rememberCoroutineScope
import com.kewu.app.ROLE_TEACHER
import com.kewu.app.AssignmentRepo
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.kewu.app.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.kewu.app.AttendanceSession
import com.kewu.app.CourseInfo
import com.kewu.app.CourseRepo
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuGreen
import com.kewu.app.RecordingManager
import com.kewu.app.RecordingStatus
import com.kewu.app.haptic
import com.kewu.app.platformActivity
import com.kewu.app.requestAudioPermission
import com.kewu.app.todayCn
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.Surface
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.ChevronForward
import io.github.suqi8.coui.kmp.icon.extended.Mic
import io.github.suqi8.coui.kmp.theme.COUITheme
import com.kewu.app.Notifications
import com.kewu.app.currentRole
import com.kewu.app.ui.NotificationBellButton
import com.kewu.app.ui.NotificationSheet
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 首页大卡水印专用深绿（2026-10-19 用户反馈）：品牌荧光绿 `KewuGreen`(#19E68C) 即使
 * 叠在白卡上也偏浅、"看着不舒服"，水印换用同色相的深绿，α 由 0.30 提到 0.55。
 * 只作用于水印；交互元素仍用品牌荧光绿。
 */
private val WatermarkGreen = Color(0xFF0B8A54)

// ───────────────────────── 数据模型（mock） ─────────────────────────

/** 课程卡片状态：待上课 / 正在上课 / 已总结 / 总结生成中 / 上传或总结失败 */
private enum class CourseStatus { PENDING, LIVE, DONE, SUMMARIZING, FAILED }

private data class Course(
    val name: String,
    val room: String = "",
    val teacher: String = "",
    val time: String = "",
    val status: CourseStatus,
    val summary: String = "",
    val homework: Boolean = false,
    val devices: Int = 0,
    val aiReady: Boolean = false,
    val highlights: Int = 0,
    val estWait: String = "",
)

/** 今日课程（倒序：最新的在最上）+ 一批历史课程，用于「查看全部」展开。 */
private fun mockToday(): List<Course> = listOf(
    Course("高等数学", room = "主楼 305", teacher = "王老师", time = "14:00", status = CourseStatus.LIVE),
    Course(
        "大学英语", room = "外语楼 202", teacher = "李老师", time = "10:00",
        status = CourseStatus.DONE, summary = "定语从句与虚拟语气的嵌套用法、长难句拆解",
        homework = true, devices = 3, aiReady = true, highlights = 2,
    ),
    Course(
        "线性代数", room = "主楼 401", teacher = "张老师", time = "08:00",
        status = CourseStatus.DONE, summary = "特征值与特征向量的几何意义、相似对角化条件",
        devices = 3, aiReady = true, highlights = 3,
    ),
    Course("大学物理", room = "实验楼 110", teacher = "陈老师", time = "明天", status = CourseStatus.PENDING),
    Course("程序设计", room = "机房 B205", teacher = "刘老师", time = "昨天",
        status = CourseStatus.SUMMARIZING, estWait = "约 2 分钟"),
    Course("马克思主义原理", room = "主楼 210", teacher = "赵老师", time = "前天",
        status = CourseStatus.FAILED),
)

private fun mockHistory(): List<Course> = listOf(
    Course("高等数学", room = "主楼 305", teacher = "王老师", time = "上周一",
        status = CourseStatus.DONE, summary = "多元函数偏导数的计算与链式法则",
        devices = 2, aiReady = true, highlights = 2),
    Course("大学英语", room = "外语楼 202", teacher = "李老师", time = "上周一",
        status = CourseStatus.DONE, summary = "英语议论文结构与论点展开", devices = 3, aiReady = true, highlights = 1),
)

// ───────────────────────── 形状：MIUI squircle ─────────────────────────
//
// 2026-10-23 coui 化：本页三卡 / MoreBar 已改用 coui 原生 `Card`（自带 squircle 与灰底白卡分层），
// 不再调用下面这组自绘件；仍有其他页面（任务 / 教师控制台 / 笔记…）引用，故保留签名与实现。

/**
 * 生成 MIUI / miuix 风格的「squircle」圆角路径（超椭圆角）。
 * 四个角各走一段 n=4 超椭圆（`x = cos(t)^0.5, y = sin(t)^0.5`）再直线相连，
 * 比 `RoundedCornerShape` 的圆弧更「方中带圆」，是 MIUI 观感的关键。
 *
 * ★★ 必须 `coerceAtLeast(0f)` 再开方（实测踩过）：Float 精度下 `cos(π/2) = -4.371e-08`
 * 是**负数**，`(-4.4e-8).pow(0.5)` = `NaN`，一个 NaN 点就能让整条 Path 作废 ——
 * `drawPath` 什么都不画、也不抛异常，表现成「卡片整块空白但文字/图标正常」。
 */
internal fun squirclePath(w: Float, h: Float, r: Float): Path {
    val path = Path()
    val steps = 16
    val halfPi = 3.1415927f / 2f
    // 超椭圆参数式：|x|^4 + |y|^4 = 1 → x = cos(t)^0.5, y = sin(t)^0.5
    fun x(t: Float) = sqrt(cos(t).coerceAtLeast(0f))
    fun y(t: Float) = sqrt(sin(t).coerceAtLeast(0f))

    // 左上：从左边 (0, r) 走到顶边 (r, 0)
    path.moveTo(r * (1f - x(0f)), r * (1f - y(0f)))
    for (i in 1..steps) {
        val t = halfPi * i / steps
        path.lineTo(r * (1f - x(t)), r * (1f - y(t)))
    }
    // 右上：从顶边 (w-r, 0) 走到右边 (w, r)
    for (i in steps downTo 0) {
        val t = halfPi * i / steps
        path.lineTo(w - r + r * x(t), r * (1f - y(t)))
    }
    // 右下：从右边 (w, h-r) 走到底边 (w-r, h)
    for (i in 0..steps) {
        val t = halfPi * i / steps
        path.lineTo(w - r + r * x(t), h - r + r * y(t))
    }
    // 左下：从底边 (r, h) 走到左边 (0, h-r)
    for (i in steps downTo 0) {
        val t = halfPi * i / steps
        path.lineTo(r * (1f - x(t)), h - r + r * y(t))
    }
    path.close()
    return path
}

/**
 * squircle 卡片底 + 按卡片矩形裁剪内容。
 *
 * 为什么不用 `Modifier.clip(shape)` / `Modifier.background(color, shape)`：
 * 自定义 Shape 产出的是 `Outline.Generic`，交给 RenderNode（hardware outline）时
 * 在本工程 Compose 版本下**完全不绘制**（"布局占位正确、整块空白、零报错"）。
 * 所以这里走 `drawBehind` + `DrawScope.drawPath`（纯 Canvas 绘制链路，稳定），
 * 越界内容（超大水印）交给 `clipToBounds()`（矩形 outline，RenderNode 支持）。
 *
 * @param clip false 时不挂 `clipToBounds()`——留给外层自己包一层裁剪节点用
 *   （配 [squircleShadow] 时：阴影要画出卡片边界，节点本身不能裁；
 *   水印裁剪改由卡片内一个 `fillMaxSize().clipToBounds()` 的中间层完成）。
 */
internal fun Modifier.squircleCard(radius: Dp, color: Color, clip: Boolean = true): Modifier =
    this
        .drawBehind {
            val half = if (size.width < size.height) size.width / 2f else size.height / 2f
            drawPath(squirclePath(size.width, size.height, radius.toPx().coerceAtMost(half)), color)
        }
        .then(if (clip) Modifier.clipToBounds() else Modifier)

/**
 * squircle 柔和投影（伪软阴影，2026-10-19 首页三卡改白后加）。
 *
 * 原理：不依赖平台 blur API（跨平台稳定），把同一条 squircle 路径逐层
 * 「轻微外扩 + 逐层下移」叠画 6 次、透明度递减，模拟高斯投影；
 * 卡片本体由 [squircleCard] 画在上一层盖住投影中心，只留四周与下方一圈软边。
 *
 * ★ 必须与 [squircleCard] 拆成**兄弟节点**、且本修饰符所在节点**不能带 clipToBounds**，
 *   否则投影画不出卡片边界就被裁掉。标准用法：
 * ```
 * Box(modifier) {
 *     Box(Modifier.fillMaxSize().squircleShadow(16.dp))              // 底层：投影
 *     Box(Modifier.fillMaxSize().squircleCard(16.dp, color)) { … }   // 上层：卡片
 * }
 * ```
 */
internal fun Modifier.squircleShadow(radius: Dp, spread: Dp = 5.dp, alpha: Float = 0.075f): Modifier =
    this.drawBehind {
        val r = radius.toPx().coerceAtLeast(0f).coerceAtMost(minOf(size.width, size.height) / 2f)
        val path = squirclePath(size.width, size.height, r)
        val spreadPx = spread.toPx()
        val layers = 6
        for (i in 0 until layers) {
            val f = i / (layers - 1).toFloat() // 0..1：越靠外的层越下移、越淡
            scale(1f + 0.012f * i, pivot = Offset(size.width / 2f, size.height / 2f)) {
                translate(0f, spreadPx * f) {
                    drawPath(path, Color.Black.copy(alpha = alpha * (1f - f)))
                }
            }
        }
    }

// ───────────────────────── ★ 标志性组件：大半圆 + 勾／叉 ─────────────────────────

/**
 * 课悟的标志性图形：**大半圆包着中间的勾／叉**。
 *
 * `startAngle = 135f, sweepAngle = 270f`（Compose 里 0° = 3 点钟、角度顺时针增大），
 * 即从「左下」顺时针绕一整圈到「右下」—— 缺口落在**正下方**（45°..135°）。
 *
 * 同一个外壳、同一套笔画，只换中间符号与颜色，就是全 app 的「状态语言」：
 * - 绿勾 = 已完成／就绪（品牌色 [KewuGreen]）
 * - 红叉 = 失败（`colorScheme.error`），**不用感叹号**
 *
 * 三处用法：① 状态大卡里当作 170dp 的**超大水印**（Mishka 的手法：图标远大于卡片、
 * 塞到右下角、被卡片裁掉大半；缺口正好落在卡片外，所以看起来是一整圈）；
 * ② 课程卡「已总结」徽章；③ 课程卡「失败」徽章（`cross = true`）。
 * 另：任务页每条任务前的徽章、课表页「已上过」徽章也复用同一组件。
 *
 * ★ 出场动效：默认 `animateEntry = true` —— 先扫出大半圆（400ms），提笔呼吸 70ms，
 * 再**一笔**「写」出中间的勾/叉（240ms），像有人用笔当场画出来。这是本软件的标志性动效，
 * 无特殊理由不要关掉（见交接文档 15.2）。
 *
 * ★★ 勾必须**单笔绘制**（2026-10-19 用户反馈修正）：旧版把勾拆成两根 drawLine 分画，
 * 两笔交界处两次描边 alpha 叠加、颜色明显发深，能看出是"两根线接出来的"。现改为：
 * 整条折线合成一个 Path，用 PathMeasure 按进度截取后**一次 drawPath** 绘制，
 * 中间拐角由 `join = Round` 一笔带过——看不出接缝。
 */
@Composable
internal fun ThreeQuarterCheck(
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    cross: Boolean = false,
    /** 是否播放「画出来」的出场动效（默认开 —— 这是本软件的标志性动效） */
    animateEntry: Boolean = true,
) {
    // ★★ 软件标志性出场动效（2026-10-19）：**先扫出大半圆，再一笔写出中间的勾/叉**。
    // 两段动画串起来，看起来像有人用笔当场把这个符号画出来 —— 首页大卡水印、任务条目徽章、
    // 课表/课程卡徽章全都用它，是全 app 统一的识别点（见交接文档 15.2）。
    val arcP = remember { Animatable(if (animateEntry) 0f else 1f) }
    val markP = remember { Animatable(if (animateEntry) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!animateEntry) return@LaunchedEffect
        arcP.animateTo(1f, tween(durationMillis = 400, easing = FastOutSlowInEasing))
        delay(70) // 提笔呼吸：圆画完，笔挪到中央再落笔，圆与符号有明确先后
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
        // ② 中央符号：**单次 drawPath**（cap=Round + join=Round）。
        //    ★ 绝不能拆成两根 drawLine 分画——交界处两次描边 alpha 叠加会发深（用户反馈）。
        val style = Stroke(width = mw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        if (cross) {
            // 叉 = 两刀（\ 再 /），各占一半进度；两刀本就该相交，各自独立截取绘制
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
            // 勾 = 一笔折线（短边下笔 → 长边提起）：PathMeasure 从起点按进度截取，
            //    拐角由 join=Round 一笔带过，看不出接缝
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
 * 警示感叹号水印（上课态用）：大半圆弧 + 中央"!"，配琥珀黄。
 * 与 [ThreeQuarterCheck] 同尺寸、同右下偏移布局、同款「先画圆再画符号」描绘动效，
 * 可直接替换状态块水印。
 *
 * 2026-10-21 用户反馈：「!」下面的点显示不出来。原因：旧点半径仅 sw*0.62（≈7.6dp@170dp）
 * 且调用处 alpha=0.30，点在琥珀 tinted 卡上对比度不足 → 视觉缺失。现把竖加粗、点放大近一倍，
 * 动画节奏不变（竖 → 点，圆仍最先扫出）。
 */
@Composable
internal fun ExclaimMark(
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    /** 是否播放「画出来」的出场动效（与 [ThreeQuarterCheck] 同节奏，默认开） */
    animateEntry: Boolean = true,
) {
    // 与 ThreeQuarterCheck 完全同节奏：大半圆 400ms → 提笔呼吸 70ms → 感叹号 240ms
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
        // ① 大半圆：与勾/叉同一扫出方式
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
        // ② "!" 两笔：**先写竖（前 55% 进度），再落点（后 45% 进度，半径生长）**。
        //    竖更粗、点更大（半径 sw*0.80 ≈ 13.6dp@170dp），保证 tinted 卡上清晰可辨。
        val barP = (t / 0.55f).coerceIn(0f, 1f)
        if (barP > 0f) {
            val bar = Path().apply { moveTo(cx, s * 0.30f); lineTo(cx, s * 0.56f) }
            val measure = PathMeasure().apply { setPath(bar, false) }
            val seg = Path()
            if (measure.getSegment(0f, measure.length * barP, seg, true)) {
                drawPath(seg, c, style = style)
            }
        }
        // 点：圆心落在竖的正下方，与竖端留一目距；圆头端帽会自然过渡
        val dotP = ((t - 0.55f) / 0.45f).coerceIn(0f, 1f)
        if (dotP > 0f) {
            drawCircle(color = c, radius = sw * 0.80f * dotP, center = Offset(cx, s * 0.70f))
        }
    }
}

// 课堂主页下钻目标（5 态）；attendanceId 单独存值
private enum class ClassroomDrill { NONE, TIMETABLE, ATTENDANCE, SCAN, CODE, COURSES }

// ───────────────────────── 课堂主页 ─────────────────────────

/**
 * 课堂主页：整体照 Mishka（miuix）的版式 —— 12.dp 间距、
 * 左侧大状态卡 + 右侧两张堆叠小卡、卡内 16.dp 内边距、超大水印图形。
 * 2026-10-23 coui 化：白卡 = coui `Card`、文字 / 按钮 / 进度 / 标签 = coui 原生件，
 * 配色走 [COUITheme] colorScheme（灰底白卡），不自定义背景色；课程卡 / 签到卡 / 录音浮层仍是液态玻璃。
 *
 * @param onGoToTab 跳转到主界面某个 tab（头像 → 「我的」= 3）
 * @param onNavHide 滚动方向回调：true = 向下浏览（收起底部导航栏），false = 回顶（展开）
 */
@Composable
fun ClassroomPage(onGoToTab: (Int) -> Unit, onNavHide: (Boolean) -> Unit) {
    var showAll by remember { mutableStateOf(false) }
    // ★ 接入真实课程：/api/courses 替换 mock（后端仅 id/name/description/teacher，
    //   缺教室/时间/实时状态 → room="待定"、time="—"、status=PENDING）；失败降级 mock。
    // ★ 动画修复（2026-10-18）：初始必须是**空列表**而不是 mock —— 旧版先显示 mock 课程、
    //   接口返回后又整体换成真数据，等于「先出现一遍、再出现一遍」。现在先空着（内容区从
    //   零开始），加载成功才带凝结入场动画出现；只有断网失败才降级 mock。
    // ★ 2026-10-26：课程改读进程级缓存 CourseRepo（首页 / 我的 / 课程列表页共用，带超时与退避重试），
    //   修「首页偶发加载不出课程显示无课，要去『我的』转一圈才出现」——旧版这里只请求一次，
    //   还要先等 /user/me 返回，任一请求慢/失败首页就一直空着。
    val repoCourses by CourseRepo.courses.collectAsState()
    val repoLoaded by CourseRepo.loaded.collectAsState()
    val courseInfos: List<CourseInfo>? = repoCourses
    // 2026-10-26：课程卡带上课表里的时间 / 地点 / 任课老师（今天有课优先显示今天那节，否则显示下一次上课）
    var schedule by remember { mutableStateOf<List<com.kewu.app.ScheduleEntry>>(emptyList()) }
    LaunchedEffect(Unit) { schedule = KewuApi.getMySchedule().orEmpty() }
    val realCourses = remember(repoCourses, repoLoaded, schedule) {
        val nowMs = kotlin.time.Clock.System.now().toEpochMilliseconds() + 8 * 3_600_000L
        val todayWd = ((nowMs / 86_400_000L + 3) % 7).toInt() + 1
        val names = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        when {
            repoCourses != null -> repoCourses.orEmpty().map { c ->
                val mine = schedule.filter { it.courseId == c.id || it.courseName == c.name }
                // 今天的那节 → 本周之后最近的一节 → 任意一节
                val next = mine.firstOrNull { it.weekday == todayWd }
                    ?: mine.minByOrNull { (it.weekday - todayWd + 7) % 7 }
                Course(
                    name = c.name,
                    teacher = next?.teacherName?.ifBlank { null } ?: c.teacher,
                    room = next?.location?.ifBlank { null } ?: "待定",
                    time = next?.let { e ->
                        (if (e.weekday == todayWd) "" else names[e.weekday - 1] + " ") + e.startTime
                    } ?: "—",
                    status = CourseStatus.PENDING, summary = c.description,
                )
            }
            // 2026-10-26：断网不再降级 mock 课程（假课程点进去必然失败，还误导用户），状态卡给「点此重试」
            else -> emptyList()
        }
    }
    val coursesLoaded = repoCourses != null || repoLoaded
    val coursesFailed = repoCourses == null && repoLoaded
    val homeScope = rememberCoroutineScope()
    // 作业待交：与任务页共用 AssignmentRepo（旧版首页写死 "3"）；首页进来静默刷新一次（1 分钟内复用）
    val assignmentItems by AssignmentRepo.items.collectAsState()
    val pendingHomework = assignmentItems?.count { it.pending }
    LaunchedEffect(Unit) { AssignmentRepo.refresh(maxAgeMs = 60_000) }
    val today = realCourses
    val history = emptyList<Course>()
    val courses = if (showAll) today + history else today

    // 问候栏/头像用真实姓名（来自 /user/me 或登录账号）
    var displayName by remember { mutableStateOf(KewuApi.displayName("同学")) }
    LaunchedEffect(Unit) {
        // 课程与 /user/me 并行，互不阻塞；每次回到首页都静默刷新一次（有缓存先显示缓存）
        launch { CourseRepo.refresh(force = true) }
        // me 失败（后端不可达）时隔几秒重试，拿到真实昵称为止；期间显示缓存昵称
        var delayMs = 3_000L
        while (KewuApi.currentUser == null) {
            KewuApi.me()
            if (KewuApi.currentUser != null) break
            kotlinx.coroutines.delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(30_000L)
        }
        displayName = KewuApi.displayName(displayName)
    }

    // ★ coui 玻璃的硬约束：采样者必须在被采样层**之外**，否则首帧 RenderThread SIGSEGV。
    val pageBackdrop = rememberGlassBackdrop()
    val scrollState = rememberScrollState()

    LaunchedEffect(scrollState) {
        var last = 0
        snapshotFlow { scrollState.value }.collect { cur ->
            if (cur <= 0) {
                onNavHide(false)
            } else {
                val delta = cur - last
                if (delta > 6) onNavHide(true) else if (delta < -6) onNavHide(false)
            }
            last = cur
        }
    }

    // 课堂主页的下钻：课表页 / 签到详情。状态提升到本页，切走 tab 再回来会重置
    // （课表是「查看排课」的一次性入口，签到详情也是临时查看，均不需跨 tab 保留）。
    var drill by remember { mutableStateOf(ClassroomDrill.NONE) }
    var attendanceId by remember { mutableStateOf<String?>(null) }
    var pendingCode by remember { mutableStateOf("") }
    LockTabSwipe(drill != ClassroomDrill.NONE)

    // 通知中心：铃铛未读数 + 弹窗显隐（首页右上角系统通知，与助教 AI 红点无关）
    var showNotif by remember { mutableStateOf(false) }
    var notifUnread by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        // 2026-10-26：不再种入演示通知（「张三」「成绩已公布」等虚构数据会误导用户），后端通知接口上线前显示空态
        notifUnread = Notifications.unreadCount()
    }

    Box(Modifier.fillMaxSize()) {
        // 背景层：coui 页面灰底（surface），是玻璃的采样源
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(pageBackdrop)
                .background(COUITheme.colorScheme.surface),
        )
        // 下钻路由：签到详情 / 课表页 / 课堂主页。
        // ★ 共享轴滑页（与「我的」页子路由同款，仅 slide、无 fade）：旧版 when 直接硬切，
        //   页面「啪」地换掉，非常生硬；slide-only 不引入 alpha 离屏层，玻璃采样者安全。
        val attId = attendanceId
        // CODE 态但会话 id 丢了（异常路径）→ 自动回到首页，不再靠子分支里手动纠偏。
        LaunchedEffect(drill, attId) {
            if ((drill == ClassroomDrill.CODE || drill == ClassroomDrill.ATTENDANCE) && attId == null) {
                drill = ClassroomDrill.NONE
            }
        }
        val drillKey = when {
            drill == ClassroomDrill.ATTENDANCE && attId != null -> 2
            drill == ClassroomDrill.SCAN -> 3
            drill == ClassroomDrill.CODE && attId != null -> 4
            drill == ClassroomDrill.TIMETABLE -> 1
            drill == ClassroomDrill.COURSES -> 5
            else -> 0
        }
        // 返回时 attendanceId 先被置空、旧页还在滑出：记住最后一次的 id，滑出途中签到页内容不消失
        val lastAttId = remember { arrayOfNulls<String>(1) }
        attId?.let { lastAttId[0] = it }
        // ★ 2026-10-26 Telegram 式推入 / 返回（KewuSlideHost Push：新页整屏推入、底页 1/4 视差压暗，弹簧驱动）。
        //   仍然只有 placement 位移 + 纯绘制（下钻页含玻璃采样者与相机预览，alpha/离屏层会踩 coui 首帧
        //   SIGSEGV，且 AndroidView 在离屏层下会黑屏）。右滑跟手返回首页；暗号签到页有输入，不开。
        KewuSlideHost(
            targetState = drillKey,
            modifier = Modifier.fillMaxSize(),
            isForward = { a, b -> b > a },
            swipeEnabled = drillKey != 0 && drillKey != 4,
            swipeNeighbor = { from, d -> if (d < 0 && from != 0) 0 else null },
            onSwipeCommit = { drill = ClassroomDrill.NONE; attendanceId = null },
        ) { key ->
            when (key) {
                2 -> {
                    // 拦截系统返回手势，回到课堂主页，而不是让 Activity 直接 finish 退出 app。
                    BackHandler { drill = ClassroomDrill.NONE; attendanceId = null }
                    // 转场期间 attendanceId 可能已被置空（如按返回），先绑定非空本地值，避免 attId!! NPE 闪退
                    val aid = attId ?: lastAttId[0] ?: return@KewuSlideHost
                    AttendanceDetailScreen(
                        attendanceId = aid,
                        studentId = KewuApi.username ?: "",
                        studentName = KewuApi.nameForServer(),
                        onBack = { drill = ClassroomDrill.NONE; attendanceId = null },
                    )
                }
                3 -> {
                    // 扫码签到：学生扫教师端展示的二维码（教师端在签到详情里展示二维码）。
                    BackHandler { drill = ClassroomDrill.NONE; attendanceId = null }
                    ScanPage(
                        fallbackSessionId = attendanceId,
                        onBack = { drill = ClassroomDrill.NONE; attendanceId = null },
                        // 签到成功 → 返回首页；失败（如需答随堂问题）→ 带暗号跳暗号签到页继续
                        onDone = { drill = ClassroomDrill.NONE; attendanceId = null },
                        onOpenCodeCheckIn = { sid, code ->
                            // 扫码拿到的暗号一起带过去（旧版丢掉了，学生要在 30s 有效期内重新手输）
                            pendingCode = code
                            attendanceId = sid
                            drill = ClassroomDrill.CODE
                        },
                    )
                }
                4 -> {
                    // 暗号签到独立页（学生）：扫码失败的兜底 / 相机不可用
                    BackHandler { drill = ClassroomDrill.NONE; attendanceId = null }
                    // 转场期间 attendanceId 可能已被置空，先绑定非空本地值，避免 attId!! NPE 闪退
                    val aid = attId ?: lastAttId[0] ?: return@KewuSlideHost
                    CodeCheckInPage(
                        sessionId = aid,
                        initialCode = pendingCode,
                        onBack = { drill = ClassroomDrill.NONE; attendanceId = null; pendingCode = "" },
                        onDone = { drill = ClassroomDrill.NONE; attendanceId = null; pendingCode = "" },
                    )
                }
                1 -> {
                    BackHandler { drill = ClassroomDrill.NONE }
                    TimetablePage(onBack = { drill = ClassroomDrill.NONE }, onNavHide = onNavHide)
                }
                5 -> {
                    // 「我的课程」全部列表（点首页左侧大状态卡进入，coui 列表页，2026-10-26）
                    BackHandler { drill = ClassroomDrill.NONE }
                    AllCoursesPage(onBack = { drill = ClassroomDrill.NONE }, onNavHide = onNavHide)
                }
                else -> {
                    // 首次进场（开 app / 切回课堂 tab）：分块 Mac 式入场。
                    // ★ 动画修复（2026-10-18）：所有块都「从零开始」—— 初始帧即不可见
                    //   （非玻璃 alpha=0 / 玻璃 tint≈0 + 内容透明），错峰延迟后才凝结上浮，
                    //   不再「控件先出现、再突然播一遍入场」。
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(bottom = 96.dp),
                    ) {
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.greetIn(40)) {
                            GreetingBar(
                                name = displayName,
                                unread = notifUnread,
                                onOpenNotifications = {
                                    // 先打开再标记已读（旧版先 markAllRead 再打开，列表里的未读点永远看不到）
                                    showNotif = true
                                },
                            ) { onGoToTab(3) }
                        }
                        Box(Modifier.macPopIn(140)) {
                            StatusSection(
                                courses = courses,
                                // 只数「今天」的课（time 为 HH:mm 的才是今天；明天/昨天/前天不计），
                                // 与课表页今天的 3 节对齐
                                todayCount = today.count { it.time.length == 5 && it.time[2] == ':' },
                                loading = !coursesLoaded,
                                failed = coursesFailed,
                                pendingHomework = pendingHomework,
                                onRetry = { homeScope.launch { CourseRepo.refresh(force = true) } },
                                onAllCourses = { drill = ClassroomDrill.COURSES },
                                onTodayCourses = { drill = ClassroomDrill.TIMETABLE },
                                onHomework = { onGoToTab(2) },
                            )
                        }
                        // 签到卡片（当有进行中的签到时显示）
                        // 身份分流：该签到的发布者（教师）→ 签到详情展示二维码；学生 → 扫码页
                        CheckInCardIfActive(pageBackdrop) { session ->
                            attendanceId = session.id
                            // 2026-10-26：按登录角色分流（旧版比「发布者 == 我的昵称」，同名学生会进教师视图）
                            drill = if (currentRole() == ROLE_TEACHER) ClassroomDrill.ATTENDANCE else ClassroomDrill.SCAN
                        }
                        RecordingOverlay(backdrop = pageBackdrop, courseInfos = courseInfos)
                        // 2026-10-26：课堂录音中 → 课程卡区域换成「课堂录音雷达」（同堂正在录音的设备分布在圆心四周）。
                        // 切换只用 slide（placement 位移）+ 不裁剪的尺寸过渡：玻璃采样者祖先不加 alpha / clip 层。
                        val recSt by RecordingManager.status.collectAsState()
                        val recSid = if (recSt is RecordingStatus.Recording) RecordingManager.currentSessionId() else null
                        AnimatedContent(
                            targetState = recSid,
                            transitionSpec = {
                                (slideInVertically(spring(dampingRatio = 0.82f, stiffness = 360f)) { it / 5 } togetherWith
                                    slideOutVertically(tween(160)) { it / 8 })
                                    .using(SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 0.86f, stiffness = 360f) })
                            },
                            label = "radarSwap",
                        ) { sid ->
                            Column(Modifier.fillMaxWidth()) {
                                if (sid != null) {
                                    RecordingRadar(pageBackdrop, sid, Modifier.padding(bottom = 12.dp))
                                } else {
                                    courses.forEachIndexed { i, c -> CourseCard(c, pageBackdrop, i) }
                                }
                            }
                        }
                        // 「查看全部课程」直接进课程列表（旧版只切换一个永远为空的历史列表，点了没效果）
                        MoreBar(showAll, Modifier.riseEnter(rememberEnterProgress(0, baseDelayMs = 220L), 24f)) {
                            drill = ClassroomDrill.COURSES
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
        NotificationSheet(show = showNotif, onDismiss = {
            showNotif = false
            Notifications.markAllRead()
            notifUnread = 0
        })
    }
}

// ───────────────────────── 顶部：问候栏 ─────────────────────────

@Composable
private fun GreetingBar(
    name: String,
    unread: Int,
    onOpenNotifications: () -> Unit,
    onAvatar: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(rememberGreeting(), color = cs.onSurfaceSecondary, fontSize = 13.sp, maxLines = 1)
            Text(
                name, color = cs.onSurface, fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
        }
        NotificationBellButton(unread = unread, onClick = onOpenNotifications)
        Spacer(Modifier.width(8.dp))
        Avatar(name, onAvatar)
    }
}

@Composable
private fun rememberGreeting(): String {
    val hour = remember { ((Clock.System.now().toEpochMilliseconds() / 3_600_000L + 8L) % 24L).toInt() }
    return when {
        hour < 6 -> "凌晨好"
        hour < 12 -> "早上好"
        hour < 14 -> "中午好"
        hour < 18 -> "下午好"
        else -> "晚上好"
    }
}

/** 头像：coui [IconButton]（圆形浅灰底 + 姓氏首字），点按跳「我的」。 */
@Composable
private fun Avatar(name: String, onAvatar: () -> Unit) {
    val cs = COUITheme.colorScheme
    IconButton(
        onClick = { haptic(HapticKind.Tick); onAvatar() },
        backgroundColor = cs.secondaryContainer,
        cornerRadius = 18.dp,
        minHeight = 36.dp,
        minWidth = 36.dp,
    ) {
        Text(
            name.firstOrNull()?.toString() ?: "我", color = cs.onSecondaryVariant,
            fontWeight = FontWeight.Bold, fontSize = 15.sp,
        )
    }
}

// ───────────────────────── 状态区（Mishka Home 版式） ─────────────────────────

/**
 * 左：大状态卡（超大水印「大半圆+绿勾」+ 状态标题 + 两行副文本）
 * 右：两张堆叠小卡（标签 13.sp + 数值 20.sp Bold）—— 对齐 Mishka 的 statusSection。
 *
 * 2026-10-23 coui 化：三卡改 coui 原生 [Card]（coui 白卡 surfaceContainer 叠在页面灰底 surface 上，
 * 天然分层），**去掉 squircleShadow 自绘软阴影**（15.7 的阴影是当年「白底白卡」的补救，灰底下不再需要）。
 *
 * @param todayCount 今日课程数（不随「查看全部」展开变化）
 * @param onTodayCourses 点「今日课程」→ 进课表页看排课
 * @param onHomework 点「作业待交」→ 跳任务 tab
 */
@Composable
private fun StatusSection(
    courses: List<Course>,
    todayCount: Int,
    loading: Boolean = false,
    failed: Boolean = false,
    pendingHomework: Int? = null,
    onRetry: () -> Unit = {},
    onAllCourses: () -> Unit,
    onTodayCourses: () -> Unit,
    onHomework: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    val live = courses.firstOrNull { it.status == CourseStatus.LIVE }
    val done = courses.count { it.status == CourseStatus.DONE }
    // 下节课（未上课时直接显示教室，用户要求：不要"下节课在哪里上"这种话术）
    val next = courses.firstOrNull { it.status == CourseStatus.PENDING || it.status == CourseStatus.SUMMARIZING }
    // 状态语义色：**正在上课 = 琥珀黄「!」；无课 = 品牌绿大半圆+勾**（用户 2026-10-19 明确要求这种对应）
    val statusTint = if (live != null) Color(0xFFFFB300) else KewuGreen

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp, bottom = 6.dp)
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧大状态卡：coui Card（squircleSurface 本身就按卡片轮廓遮罩子内容）。
        // insideMargin 置 0，16dp 内边距挪到文字列上——水印要贴着卡片原点布局、越界部分被卡片裁掉。
        // 2026-10-26：整卡可点 → 「我的课程」全部列表（coui 按压 Tint，与右侧小卡同一交互）
        Card(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            cornerRadius = 22.dp,
            insideMargin = PaddingValues(0.dp),
            colors = kewuCardColors(),
            onClick = { haptic(HapticKind.Tick); if (failed) onRetry() else onAllCourses() },
        ) {
            // 内容裁剪层（双保险：coui 遮罩之外再挂一层矩形 clipToBounds）。
            // ★ 裁剪节点必须留在卡片原点：水印子节点带 offset，若把 clip 挂到
            //   被 offset 的节点上，裁剪框会跟着一起移走，水印就裁不掉了。
            Box(Modifier.fillMaxSize().clipToBounds()) {
                // 超大水印：170dp 的大半圆+符号，offset 到右下角、被卡片裁掉大半。
                // 注意用 **lambda 版 offset**（px，纯视觉平移）：Dp 版 `offset(x, y)` 会把
                // x/y 加进 min intrinsic 尺寸，导致 `Row(height = IntrinsicSize.Min)` 被撑高 45dp。
                Box(
                    Modifier
                        .fillMaxSize()
                        .offset { IntOffset(52.dp.roundToPx(), 58.dp.roundToPx()) },
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    // 无课 → 品牌绿大半圆+勾；正在上课 → 琥珀黄大半圆+「!」（用户 2026-10-19 要求）
                    // 水印绿用深绿（2026-10-19 用户反馈：品牌荧光绿 α0.30 太浅，看不清）
                    if (live != null) {
                        // ★ 2026-10-21：alpha 0.30→0.45 —— 点在琥珀 tinted 卡上太浅看不清（用户反馈）
                        ExclaimMark(size = 170.dp, color = statusTint, alpha = 0.45f)
                    } else {
                        ThreeQuarterCheck(size = 170.dp, color = WatermarkGreen, alpha = 0.55f)
                    }
                }
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text(
                        if (live != null) "！当前正在上课" else "无课",
                        color = statusTint, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    // 2026-10-26：后端课程还没有上课时间 / 教室，旧版这里同时出现「无课 / 下节课：X · 待定 /
                    // 今天没有排课」三句互相矛盾的话；排课数据接入前只陈述能确定的事实
                    Text(
                        when {
                            live != null -> "${live.name} · ${live.room}"
                            loading -> "正在同步课程…"
                            failed -> "课程加载失败"
                            courses.isEmpty() -> "还没有加入课程"
                            else -> "共 ${courses.size} 门课程"
                        },
                        color = if (failed) cs.error else cs.onSurfaceSecondary,
                        fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                    )
                    Text(
                        when {
                            live != null -> "已总结 $done / ${courses.size} 节"
                            failed -> "点此重试"
                            loading -> ""
                            todayCount > 0 -> "今天共 $todayCount 节课"
                            else -> "点击查看全部课程"
                        },
                        color = cs.onSurfaceSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                    )
                }
            }
        }

        // 右侧两张堆叠小卡（均可点：进课表 / 跳任务页）
        Column(Modifier.weight(1f).fillMaxHeight()) {
            MiniStatCard(
                label = "今日课程",
                value = "$todayCount",
                modifier = Modifier.fillMaxWidth().weight(1f),
                onClick = onTodayCourses,
            )
            Spacer(Modifier.height(12.dp))
            MiniStatCard(
                label = "作业待交",
                value = pendingHomework?.toString() ?: "—", // 真实待交数（未交 + 被打回），加载中显示 —
                modifier = Modifier.fillMaxWidth().weight(1f),
                onClick = onHomework,
            )
        }
    }
}

/**
 * Mishka 堆叠小卡：标签 + 大数值（coui [Card]，可点时走 COUI 按压 Tint）。
 * [onClick] 非空时整卡可点，并在右侧放一个 coui 箭头图标提示「可进入」。
 */
@Composable
private fun MiniStatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val cs = COUITheme.colorScheme
    Card(
        modifier = modifier,
        cornerRadius = 22.dp,
        insideMargin = PaddingValues(16.dp),
        colors = kewuCardColors(),
        onClick = onClick?.let { cb -> { haptic(HapticKind.Tick); cb() } },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, color = cs.onSurfaceSecondary, fontSize = 13.sp, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    Text(value, color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                if (onClick != null) {
                    Spacer(Modifier.width(6.dp))
                    // 右侧小箭头（coui ChevronForward）：弱提示「这张卡可以点」
                    Icon(
                        COUIIcons.ChevronForward, contentDescription = null,
                        tint = cs.onSurfaceVariantSummary, modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

// ───────────────────────── 录音浮层（coui 毛玻璃 · 真实录音 + 上传） ─────────────────────────

/**
 * 首页录音浮层。
 *
 * ★ 2026-10-19 修「旋屏后录音中断」：录音引擎与状态全部在进程级单例
 *   [RecordingManager]（原因与旧版泄漏详见该文件头注释）——旋屏（Activity
 *   重建）/ 切 tab（本 Composition 销毁）都不再中断录音或丢失状态，本
 *   Composable 只是观察者 + 发起点，不再持有任何 remember 录音状态。
 *
 * 玻璃外壳保留；内部文字 / 录音钮 / 进度条 / 重试钮已换 coui 原生件（2026-10-23）。
 */
@Composable
private fun RecordingOverlay(backdrop: LayerBackdrop, courseInfos: List<CourseInfo>?) {
    val cs = COUITheme.colorScheme

    // 录音状态单源：单例 StateFlow（旋屏 / 切 tab / 页面销毁均不影响录音进程）
    val st = RecordingManager.status.collectAsState().value

    // ★ 2026-10-26 用户口径：只有「老师发起了签到（正在上课）+ 我已经签到」的那门课才出现课堂录音按钮，
    //   录音也绑定到这门课（旧版无条件显示、固定绑第一门课）。每 20 秒复查一次；录音 / 上传进行中不受影响。
    var liveCourse by remember { mutableStateOf<CourseInfo?>(null) }
    LaunchedEffect(courseInfos) {
        val me = KewuApi.username.orEmpty()
        while (true) {
            liveCourse = courseInfos.orEmpty().firstOrNull { c ->
                val on = KewuApi.getClassState(c.id)?.state == "ON"
                val todays = KewuApi.getAttendances(c.id).orEmpty().filter { it.createdAt.startsWith(todayCn()) }
                (on || todays.any { it.state.equals("OPEN", true) }) && me.isNotBlank() &&
                    todays.any { s -> KewuApi.getMyAttendance(s.id, me) != null }
            }
            kotlinx.coroutines.delay(if (liveCourse != null) 10_000 else 20_000)
        }
    }
    // 下课后（不再「上课中 + 已签到」）按钮消失；只有录音中 / 上传中 / 上传失败待重试时保留
    val busy = st is RecordingStatus.Recording || st is RecordingStatus.Uploading || st is RecordingStatus.Error
    val snackbar = LocalKewuSnackbar.current
    val evt = RecordingManager.events.collectAsState().value
    LaunchedEffect(evt) { evt?.let { snackbar.toast(it); RecordingManager.consumeEvent() } }
    if (liveCourse == null && !busy) return

    val activeCourse = liveCourse ?: courseInfos?.firstOrNull()
    val classId = activeCourse?.id
    val courseName = activeCourse?.name ?: "课堂"

    // 课堂协同会话：RecordingManager 上传时带 sessionId = classSessionId(课程, 今天)，
    // 同课同日的教师端 / 学生端落到同一会话由 8081 择优融合。这里只查一下会话是否存在，
    // 存在就提示一行小字；查询失败 / 404 静默不显示。状态类型变化时（如上传成功后会话刚建立）重查一次。
    var collabOn by remember(classId) { mutableStateOf(false) }
    LaunchedEffect(classId, st::class) {
        val cid = classId ?: return@LaunchedEffect
        collabOn = KewuApi.getSession(KewuApi.classSessionId(cid, todayCn())) != null
    }

    // 玻璃凝结入场（2026-10-18 动画统一改造）：初始 tint≈0 + 内容透明 = 不可见，
    // 240ms 后从零「长」出来，不再一进场就整卡出现、再突然滑一遍。
    val p = rememberEnterProgress(0, baseDelayMs = 240L)

    fun fmtTime(s: Int): String {
        val m = s / 60
        val sec = s % 60
        return "${if (m < 10) "0$m" else "$m"}:${if (sec < 10) "0$sec" else "$sec"}"
    }

    fun onRecordClick() {
        haptic(HapticKind.Medium)
        if (st is RecordingStatus.Recording) {
            // 停止 → 生成 WAV → 自动上传（单例负责，与页面生命周期无关）
            RecordingManager.stopAndUpload()
            return
        }
        val cid = classId
        if (cid == null) {
            RecordingManager.showError("暂无可绑定的课程，请先在「我的」绑定课程后再录音")
            return
        }
        if (RecordingManager.hasPermission()) {
            RecordingManager.start(cid)
        } else {
            val act = platformActivity()
            if (act != null) requestAudioPermission(act) { granted ->
                if (granted) RecordingManager.start(cid)
                else RecordingManager.showError("需要麦克风权限才能录音，请在系统弹窗中允许")
            } else {
                RecordingManager.showError("需要麦克风权限才能录音，请在系统弹窗中允许")
            }
        }
    }

    KewuGlassCard(
        backdrop = backdrop,
        shape = RoundedCornerShape(16.dp),
        blurRadius = glassCondenseBlur(p, 20f),
        tintAlpha = 0.3f * p,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .glassEnter(p),
    ) {
        Column(Modifier.alpha(p).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Waveform(active = st is RecordingStatus.Recording)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (classId == null) "无可用课程" else "$courseName · 课堂录音",
                        color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    val sub = when (st) {
                        is RecordingStatus.Recording -> "录音中 ${fmtTime(st.elapsedSec)}"
                        is RecordingStatus.Uploading -> "录音完成，正在上传"
                        is RecordingStatus.Success -> "已上传 ✓"
                        is RecordingStatus.Error -> st.msg
                        RecordingStatus.Idle -> "点击右侧按钮开始录音"
                    }
                    Text(sub, color = cs.onSurfaceSecondary, fontSize = 13.sp, maxLines = 1)
                    if (collabOn) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "已加入课堂协同录音 · 老师端优先融合",
                            color = cs.onSurfaceVariantSummary, fontSize = 11.5.sp, maxLines = 1,
                        )
                    }
                }
                // 录音 / 停止按钮（coui IconButton 圆钮：待录 = 主色 + 麦克风；录音中 = error 红 + 停止方块）
                IconButton(
                    onClick = { onRecordClick() },
                    backgroundColor = if (st is RecordingStatus.Recording) cs.error else cs.primary,
                    cornerRadius = 24.dp,
                    minHeight = 48.dp,
                    minWidth = 48.dp,
                ) {
                    if (st is RecordingStatus.Recording) {
                        // coui-icons 无「停止」图形，按通行语义画一枚白色圆角方块
                        Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(Color.White))
                    } else {
                        Icon(COUIIcons.Mic, contentDescription = "开始录音", tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
            }

            // 上传状态（错误态带「重试」，仅当本地文件还在）
            when (st) {
                is RecordingStatus.Uploading -> {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text("上传中…", color = cs.onSurfaceSecondary, fontSize = 12.5.sp)
                }
                is RecordingStatus.Success -> {
                    Spacer(Modifier.height(12.dp))
                    StatusTag("已上传 · ${st.recordingId}", KewuGreen.copy(alpha = 0.18f), KewuGreen)
                }
                is RecordingStatus.Error -> {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(st.msg, color = cs.error, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                        if (st.filePath != null) {
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                text = "重试",
                                onClick = { haptic(HapticKind.Tick); RecordingManager.retryUpload() },
                                colors = ButtonDefaults.textButtonColorsPrimary(color = cs.error),
                                minHeight = ButtonDefaults.MinHeightSmall,
                                minWidth = ButtonDefaults.MinWidthSmall,
                                insideMargin = ButtonDefaults.InsideMarginSmall,
                            )
                        }
                    }
                }
                RecordingStatus.Idle, is RecordingStatus.Recording -> {}
            }
        }
    }
}

@Composable
private fun Waveform(active: Boolean = false) {
    // 2026-10-26：只在录音时创建无限动画（旧版空闲也一直在跑，且位于玻璃卡内，每帧都要重绘）；
    // 动画值只在 Canvas 的 draw 阶段读，不触发重组
    val color = COUITheme.colorScheme.primary
    if (!active) {
        Canvas(Modifier.size(36.dp, 26.dp)) {
            val n = 5
            val bw = size.width / (n * 2f)
            for (i in 0 until n) {
                val h = 0.12f * size.height
                drawRoundRect(
                    color = color,
                    topLeft = Offset(i * 2f * bw + bw * 0.25f, (size.height - h) / 2f),
                    size = Size(bw * 0.5f, h),
                    cornerRadius = CornerRadius(bw * 0.25f),
                )
            }
        }
        return
    }
    val tState = rememberInfiniteTransition().animateFloat(
        0f, 1f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
    )
    Canvas(Modifier.size(36.dp, 26.dp)) {
        val t = tState.value
        val n = 5
        val bw = size.width / (n * 2f)
        val pi = 3.1415927f
        for (i in 0 until n) {
            val phase = t * 2f * pi + i * 0.8f
            // 未录音时压成细线，避免空转动画打扰
            val amp = if (active) (0.28f + 0.72f * abs(sin(phase))) else 0.12f
            val h = amp.coerceAtMost(1f) * size.height
            drawRoundRect(
                color = color,
                topLeft = Offset(i * 2f * bw + bw * 0.25f, (size.height - h) / 2f),
                size = Size(bw * 0.5f, h),
                cornerRadius = CornerRadius(bw * 0.25f),
            )
        }
    }
}

// ───────────────────────── 课程卡片流 ─────────────────────────

/**
 * 课程卡：外壳保留液态玻璃（KewuGlassCard 是项目自研玻璃件，按「除液态玻璃外全部 coui」原则不替换），
 * 卡内文字 / 标签 / 转圈换 coui 原生件。
 */
@Composable
private fun CourseCard(course: Course, backdrop: LayerBackdrop, index: Int) {
    // 玻璃凝结入场：初始 tint≈0 + 内容透明（不可见）→ 错峰从零上浮「长」出来
    val p = rememberEnterProgress(index, baseDelayMs = 300L, stepMs = 60L)
    KewuGlassCard(
        backdrop = backdrop,
        shape = RoundedCornerShape(16.dp),
        blurRadius = glassCondenseBlur(p, 20f),
        tintAlpha = 0.34f * p,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .glassEnter(p),
    ) {
        Row(
            Modifier.fillMaxWidth().alpha(p).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusBadge(course.status)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) { CourseContent(course) }
        }
    }
}

@Composable
private fun StatusBadge(status: CourseStatus) {
    val cs = COUITheme.colorScheme
    when (status) {
        // 已总结：标志性「四分之三圆 + 勾」
        CourseStatus.DONE -> ThreeQuarterCheck(size = 28.dp, color = KewuGreen)
        CourseStatus.LIVE -> {
            // 呼吸圆点：coui 无 tertiary，改用主色（与「录音中」标签的 tertiaryContainer 主色浅底同色系）
            val pulse by rememberInfiniteTransition().animateFloat(
                0.65f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse),
            )
            Canvas(Modifier.size(28.dp)) {
                drawCircle(color = cs.primary.copy(alpha = 0.22f * pulse + 0.1f))
                drawCircle(color = cs.primary, radius = size.width * 0.17f * pulse)
            }
        }
        CourseStatus.SUMMARIZING -> {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                InfiniteProgressIndicator(color = cs.primary, size = 20.dp, strokeWidth = 2.5.dp)
            }
        }
        // 失败：同样的「大半圆外壳」，里面改成红色的叉（不用感叹号）
        CourseStatus.FAILED -> ThreeQuarterCheck(size = 28.dp, color = cs.error, cross = true)
        CourseStatus.PENDING -> {
            Canvas(Modifier.size(28.dp)) {
                drawCircle(
                    color = cs.onSurfaceSecondary.copy(alpha = 0.45f),
                    style = Stroke(width = size.width * 0.09f),
                )
            }
        }
    }
}

@Composable
private fun CourseContent(course: Course) {
    val cs = COUITheme.colorScheme
    // 标题行：课程名 + 时间（单行）
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            course.name, color = cs.onSurface, fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1,
        )
        if (course.time.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(course.time, color = cs.onSurfaceSecondary, fontSize = 13.sp, maxLines = 1)
        }
    }

    when (course.status) {
        CourseStatus.PENDING -> PendingBody(course)
        CourseStatus.LIVE -> LiveBody(course)
        CourseStatus.DONE -> DoneBody(course)
        CourseStatus.SUMMARIZING -> SummarizingBody(course)
        CourseStatus.FAILED -> FailedBody(course)
    }
}

@Composable
private fun PendingBody(course: Course) {
    val cs = COUITheme.colorScheme
    Spacer(Modifier.height(3.dp))
    MetaText("${course.room} · ${course.teacher}")
    Spacer(Modifier.height(8.dp))
    StatusTag("未开始", cs.secondaryContainer, cs.onSurfaceSecondary)
}

@Composable
private fun LiveBody(course: Course) {
    val cs = COUITheme.colorScheme
    Spacer(Modifier.height(3.dp))
    MetaText(course.room)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusTag("录音中", cs.tertiaryContainer, cs.onTertiaryContainer)
        if (course.homework) StatusTag("随堂作业", cs.tertiaryContainer, cs.onTertiaryContainer)
    }
}

@Composable
private fun DoneBody(course: Course) {
    val cs = COUITheme.colorScheme
    Spacer(Modifier.height(3.dp))
    Text(
        course.summary.ifEmpty { "本节课暂无摘要" },
        color = cs.onSurfaceSecondary, fontSize = 13.sp, maxLines = 2,
    )
    if (course.devices > 0 || course.aiReady) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (course.devices > 0) StatusTag("${course.devices} 台设备", cs.tertiaryContainer, cs.onTertiaryContainer)
            if (course.aiReady) StatusTag("AI 已就绪", cs.tertiaryContainer, cs.onTertiaryContainer)
        }
    }
}

@Composable
private fun SummarizingBody(course: Course) {
    val cs = COUITheme.colorScheme
    Spacer(Modifier.height(3.dp))
    Text(
        "AI 正在整理课堂纪要…", color = cs.onSurfaceSecondary, fontSize = 13.sp, maxLines = 1,
    )
    Spacer(Modifier.height(3.dp))
    Text(
        course.estWait, color = cs.onSurfaceVariantSummary, fontSize = 12.sp, maxLines = 1,
    )
}

@Composable
private fun FailedBody(course: Course) {
    val cs = COUITheme.colorScheme
    Spacer(Modifier.height(3.dp))
    Text("录音上传不完整", color = cs.error, fontSize = 13.sp, maxLines = 1)
    Spacer(Modifier.height(8.dp))
    StatusTag("点击重试", cs.errorContainer, cs.onErrorContainer)
}

@Composable
private fun MetaText(text: String) {
    Text(
        text, color = COUITheme.colorScheme.onSurfaceSecondary, fontSize = 13.sp, maxLines = 1,
    )
}

/**
 * 状态小标签（跨页共享，签名保留）：2026-10-23 起内部改走 coui [KewuTag]（coui Surface 胶囊 + coui Text）。
 * [background] / [content] 由调用方按语义传 coui 色（如 tertiaryContainer / onTertiaryContainer）。
 */
@Composable
internal fun StatusTag(label: String, background: Color, content: Color) {
    KewuTag(label, color = background, contentColor = content)
}

/** 「查看全部 / 收起」条：coui 可点 [Card]（按压 Tint），文字居中。 */
@Composable
private fun MoreBar(showAll: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = COUITheme.colorScheme
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(vertical = 14.dp),
        colors = kewuCardColors(),
        onClick = { haptic(HapticKind.Tick); onClick() },
    ) {
        Text(
            if (showAll) "收起历史课程" else "查看全部课程",
            color = cs.onSurfaceSecondary, fontSize = 14.sp,
            fontWeight = FontWeight.Medium, maxLines = 1, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ───────────────────────── 统一入场动画体系（2026-10-18 动画改造） ─────────────────────────

/**
 * 统一的入场进度：先保持 0（不可见），按 [index] 错峰延迟后低阻尼弹簧跑向 1。
 * ★ 这是本次动画修复的核心：旧 `cardEnter` 初始帧 alpha=1（只有位移），首帧内容直接可见，
 * 延迟结束后又整体滑一遍 → 「控件先出现、再突然播入场」的割裂感。现在一切入场都从 0 起步。
 * 玻璃件另配 [glassEnter]（只位移）+ [glassCondenseBlur]/tint 插值（纯 draw 参数），
 * 非玻璃件配 [riseEnter]（位移 + 透明度 + 微缩放）。
 */
@Composable
internal fun rememberEnterProgress(
    index: Int,
    baseDelayMs: Long = 300L,
    stepMs: Long = 60L,
    maxDelayMs: Long = 900L,
): Float {
    var target by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        delay((baseDelayMs + index * stepMs).coerceAtMost(maxDelayMs))
        target = 1f
    }
    val p by animateFloatAsState(
        targetValue = target,
        // 显式小阈值：默认 0.01 会让弹簧在第一次穿过目标时截断，回弹过冲被吃掉（本项目多次踩坑）
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 340f, visibilityThreshold = 0.0005f),
        label = "enterP$index",
    )
    return p
}

/**
 * 非玻璃元素入场：从下方上浮 + 淡入 + 轻微回弹缩放（0.96→1）。
 * alpha/scale 只允许用在**非采样者**上（squircle/drawBehind、纯文字、普通 Box）。
 */
@Composable
internal fun Modifier.riseEnter(p: Float, travelDp: Float = 60f): Modifier {
    val v = p.coerceIn(0f, 1f)
    return this
        .offset { IntOffset(0, ((1f - v) * travelDp).dp.roundToPx()) }
        .alpha(v)
        .scale(lerp(0.96f, 1f, v))
}

/**
 * 玻璃采样者入场：只做 placement 位移（禁 alpha/scale/graphicsLayer 包裹 —— coui 首帧 SIGSEGV 硬约束）。
 * 「从无到有」的另一半由调用方用纯 draw 参数完成：blurRadius 走 [glassCondenseBlur]、
 * tintAlpha 传 `目标值 * p`、玻璃内部内容包 `Modifier.alpha(p)`。
 */
@Composable
internal fun Modifier.glassEnter(p: Float, travelDp: Float = 70f): Modifier {
    val v = p.coerceIn(0f, 1f)
    return this.offset { IntOffset(0, ((1f - v) * travelDp).dp.roundToPx()) }
}

/** 玻璃凝结插值之 blur：从 4dp「凝结」到目标值（配合 tint 插值 = 登录页/助教输入框同款凝结出场）。 */
internal fun glassCondenseBlur(p: Float, targetDp: Float): Dp {
    val v = p.coerceIn(0f, 1f)
    return (4f + (targetDp - 4f) * v).dp
}

/**
 * 首页「正在上课」状态块：Mac app 打开式弹出 —— 从底部、近乎零大小放大入场，
 * 中段带一次「扭曲」（scaleX 压窄 + scaleY 拉长）再回弹到 1:1，配合轻微上浮与淡入。
 * 状态块是 squircle（drawBehind 绘制，非 coui 采样者），可安全叠 scale/alpha。
 */
@Composable
internal fun Modifier.macPopIn(delayMs: Int = 120): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(delayMs.toLong()); p.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 300f)) }
    val v = p.value.coerceIn(0f, 1f)
    // 扭曲：前半段纵向拉长、横向压窄，弹回 1:1（弹簧过冲自然产生「回正」的果冻感）
    val sx = lerp(0.82f, 1f, v)
    val sy = lerp(1.18f, 1f, v)
    return this
        .alpha(v)
        .offset { IntOffset(0, ((1f - v) * 48f).dp.roundToPx()) }
        .scale(sx, sy)
}

/** 问候栏轻量入场：从下方小幅上浮 + 淡入。 */
@Composable
internal fun Modifier.greetIn(delayMs: Int = 40): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(delayMs.toLong()); p.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 380f)) }
    val v = p.value.coerceIn(0f, 1f)
    return this.alpha(v).offset { IntOffset(0, ((1f - v) * 24f).dp.roundToPx()) }
}

/**
 * 文字缩放出现（Mac 式文字入场）：首次组合后按 [delayMs] 从 0.92 弹到 1.0 并淡入。
 * 用于纯文字（非玻璃采样者），可安全叠 scale/alpha。给首页「文字缩放出现」用。
 */
@Composable
internal fun Modifier.textRiseIn(delayMs: Int): Modifier {
    var target by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) { delay(delayMs.toLong()); target = 1f }
    val v by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 380f),
        label = "textRise$delayMs",
    )
    return this.alpha(v).scale(lerp(0.92f, 1f, v))
}

// ───────────────────────── 签到卡片 ─────────────────────────

/**
 * 签到入口卡片 —— 当有进行中的签到（state=OPEN）时显示。
 * 整卡可点；身份分流由调用方按 [AttendanceSession.publisher] 与当前用户决定：
 * 教师（发布者本人）→ [AttendanceDetailScreen] 展示二维码；学生 → [ScanPage] 扫码。
 * 外壳保留液态玻璃；内部标记圆 / 文字换 coui Surface / Text（2026-10-23）。
 */
@Composable
private fun CheckInCardIfActive(backdrop: LayerBackdrop, onOpen: (AttendanceSession) -> Unit) {
    val cs = COUITheme.colorScheme
    var currentSession by remember { mutableStateOf<AttendanceSession?>(null) }

    // 获取当前进行中的签到会话（取第一门课的 OPEN 会话）
    LaunchedEffect(Unit) {
        try {
            val courses = KewuApi.getCourses()
            if (!courses.isNullOrEmpty()) {
                val sessions = KewuApi.getAttendances(courses[0].id)
                currentSession = sessions?.firstOrNull { it.state == "OPEN" }
            }
        } catch (e: Exception) {
            // 网络异常时卡片静默不显示，不阻塞首页
        }
    }

    val session = currentSession ?: return // 没有进行中的签到，不显示

    // 玻璃凝结入场（签到卡是后到的数据：出现时从零「长」出来，不硬弹）
    val p = rememberEnterProgress(0, baseDelayMs = 300L)
    KewuGlassCard(
        backdrop = backdrop,
        shape = RoundedCornerShape(20.dp),
        blurRadius = glassCondenseBlur(p, 22f),
        tintAlpha = 0.28f * p,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
            .glassEnter(p),
        onClick = { onOpen(session) },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .alpha(p)
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧：醒目的进行中标记（coui Surface 圆，主色浅底 + 主色字）
            Surface(
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                color = cs.tertiaryContainer,
                contentColor = cs.onTertiaryContainer,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("签", color = cs.onTertiaryContainer, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.width(16.dp))

            // 右侧：标题 + 进入提示
            Column(Modifier.weight(1f)) {
                Text(
                    session.title,
                    color = cs.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "进行中 · 点击扫码签到",
                    color = KewuGreen,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}
