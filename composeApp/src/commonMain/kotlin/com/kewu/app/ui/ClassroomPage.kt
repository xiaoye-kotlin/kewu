package com.kewu.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.kewu.app.KewuGreen
import com.kewu.app.ui.liquidglass.LiquidGlassSurface
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

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

private data class RecordingDevice(val name: String, val state: String)

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

/**
 * 生成 MIUI / miuix 风格的「squircle」圆角路径（超椭圆角）。
 * 四个角各走一段 n=4 超椭圆（`x = cos(t)^0.5, y = sin(t)^0.5`）再直线相连，
 * 比 `RoundedCornerShape` 的圆弧更「方中带圆」，是 MIUI 观感的关键。
 *
 * ★★ 必须 `coerceAtLeast(0f)` 再开方（实测踩过）：Float 精度下 `cos(π/2) = -4.371e-08`
 * 是**负数**，`(-4.4e-8).pow(0.5)` = `NaN`，一个 NaN 点就能让整条 Path 作废 ——
 * `drawPath` 什么都不画、也不抛异常，表现成「卡片整块空白但文字/图标正常」。
 */
private fun squirclePath(w: Float, h: Float, r: Float): Path {
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
 */
private fun Modifier.squircleCard(radius: Dp, color: Color): Modifier =
    this.drawBehind {
        val half = if (size.width < size.height) size.width / 2f else size.height / 2f
        drawPath(squirclePath(size.width, size.height, radius.toPx().coerceAtMost(half)), color)
    }.clipToBounds()

// ───────────────────────── ★ 标志性组件：四分之三圆 + 勾 ─────────────────────────

/**
 * 课悟的标志性图形：**四分之三圆（270° 圆弧，缺口朝右上）+ 中间一个勾**。
 * 用法两处：① 状态卡里当作 170dp 的**超大水印**（Mishka 的手法：图标远大于卡片、
 * 用 offset(38,45) 塞到右下角、被卡片裁掉大半，只露出一道弧）；② 课程卡的状态徽章。
 */
@Composable
private fun ThreeQuarterCheck(
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val sw = s * 0.072f
        val inset = sw / 2f
        // 270° 圆弧：从 135° 起、扫 270°，缺口留在右上 —— 即"四分之三圆"
        drawArc(
            color = color.copy(alpha = alpha),
            startAngle = 135f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(s - sw, s - sw),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        // 中间的勾
        val check = Path().apply {
            moveTo(s * 0.31f, s * 0.50f)
            lineTo(s * 0.44f, s * 0.63f)
            lineTo(s * 0.69f, s * 0.37f)
        }
        drawPath(
            path = check,
            color = color.copy(alpha = alpha),
            style = Stroke(width = sw * 1.25f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

// ───────────────────────── 课堂主页 ─────────────────────────

/**
 * 课堂主页：整体照 Mishka（miuix）的版式 —— squircle 卡片、12.dp 间距、
 * 左侧大状态卡 + 右侧两张堆叠小卡、卡内 16.dp 内边距、超大水印图形。
 * 配色走 [MaterialTheme] 默认 colorScheme，不自定义背景色。
 *
 * @param onGoToTab 跳转到主界面某个 tab（头像 → 「我的」= 3）
 * @param onNavHide 滚动方向回调：true = 向下浏览（收起底部导航栏），false = 回顶（展开）
 */
@Composable
fun ClassroomPage(onGoToTab: (Int) -> Unit, onNavHide: (Boolean) -> Unit) {
    var showAll by remember { mutableStateOf(false) }
    val today = remember { mockToday() }
    val history = remember { mockHistory() }
    val courses = if (showAll) today + history else today

    val recording = remember { true } // mock：演示时始终在录音；真实接入后按状态切换
    val devices = remember {
        listOf(
            RecordingDevice("教室主机", "上传中"),
            RecordingDevice("我的手机", "已同步"),
            RecordingDevice("同桌平板", "上传中"),
        )
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

    Box(Modifier.fillMaxSize()) {
        // 背景层：M3 默认背景色，是玻璃的采样源
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(pageBackdrop)
                .background(MaterialTheme.colorScheme.background),
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 96.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            GreetingBar(name = "刘宇豪") { onGoToTab(3) }
            StatusSection(courses)
            if (recording) RecordingOverlay(devices, pageBackdrop)
            courses.forEachIndexed { i, c -> CourseCard(c, pageBackdrop, i) }
            MoreBar(showAll) { showAll = !showAll }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ───────────────────────── 顶部：问候栏 ─────────────────────────

@Composable
private fun GreetingBar(name: String, onAvatar: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(rememberGreeting(), color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
            Text(
                name, color = cs.onSurface, fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
        }
        Avatar(onAvatar)
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

@Composable
private fun Avatar(onAvatar: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(cs.secondaryContainer)
            .clickable { onAvatar() },
        contentAlignment = Alignment.Center,
    ) {
        Text("刘", color = cs.onSecondaryContainer, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

// ───────────────────────── 状态区（Mishka Home 版式） ─────────────────────────

/**
 * 左：大状态卡（超大水印「四分之三圆+勾」+ 状态标题 + 两行副文本）
 * 右：两张堆叠小卡（标签 13.sp + 数值 20.sp Bold）—— 对齐 Mishka 的 statusSection。
 */
@Composable
private fun StatusSection(courses: List<Course>) {
    val cs = MaterialTheme.colorScheme
    val live = courses.firstOrNull { it.status == CourseStatus.LIVE }
    val done = courses.count { it.status == CourseStatus.DONE }
    // 状态语义色：有课在上是「进行中」，否则「已就绪」（绿）
    val statusTint = if (live != null) cs.tertiary else KewuGreen
    val statusContainer = if (live != null) cs.tertiaryContainer else cs.secondaryContainer

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp, bottom = 6.dp)
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧大状态卡
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .squircleCard(16.dp, statusContainer),
        ) {
            // 超大水印：170dp 的四分之三圆+勾，offset 到右下角、被卡片裁掉大半。
            // 注意用 **lambda 版 offset**（px，纯视觉平移）：Dp 版 `offset(x, y)` 会把
            // x/y 加进 min intrinsic 尺寸，导致 `Row(height = IntrinsicSize.Min)` 被撑高 45dp。
            Box(
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset(52.dp.roundToPx(), 58.dp.roundToPx()) },
                contentAlignment = Alignment.BottomEnd,
            ) {
                ThreeQuarterCheck(
                    size = 170.dp,
                    color = statusTint,
                    alpha = 0.16f, // 水印淡一点，不与文字抢
                )
            }
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    if (live != null) "正在上课" else "今日已就绪",
                    color = statusTint, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (live != null) "${live.name} · ${live.room}" else "全部纪要已生成",
                    color = cs.onSurfaceVariant, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                )
                Text(
                    "已总结 $done / ${courses.size} 节",
                    color = cs.onSurfaceVariant, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                )
            }
        }

        // 右侧两张堆叠小卡
        Column(Modifier.weight(1f).fillMaxHeight()) {
            MiniStatCard(
                label = "今日课程",
                value = "${courses.size}",
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Spacer(Modifier.height(12.dp))
            MiniStatCard(
                label = "作业待交",
                value = "${courses.count { it.status == CourseStatus.DONE && it.homework }}",
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

@Composable
private fun MiniStatCard(label: String, value: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .squircleCard(16.dp, cs.surfaceVariant)
            .padding(16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column {
            Text(label, color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(value, color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

// ───────────────────────── 录音浮层（coui 毛玻璃） ─────────────────────────

@Composable
private fun RecordingOverlay(devices: List<RecordingDevice>, backdrop: LayerBackdrop) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    LiquidGlassSurface(
        backdrop = backdrop,
        shape = RoundedCornerShape(16.dp),
        blurRadius = 20.dp,
        tintAlpha = 0.3f,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Waveform()
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "高等数学 · 正在录音", color = cs.onSurface,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    )
                    Text(
                        "00:23:45 · 3 台设备采集中", color = cs.onSurfaceVariant,
                        fontSize = 13.sp, maxLines = 1,
                    )
                }
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(cs.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("3", color = cs.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                devices.forEach {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(7.dp).background(cs.primary, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            it.name, color = cs.onSurface, fontSize = 14.sp,
                            modifier = Modifier.weight(1f), maxLines = 1,
                        )
                        Text(it.state, color = cs.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Waveform() {
    val t by rememberInfiniteTransition().animateFloat(
        0f, 1f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
    )
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(36.dp, 26.dp)) {
        val n = 5
        val bw = size.width / (n * 2f)
        val pi = 3.1415927f
        for (i in 0 until n) {
            val phase = t * 2f * pi + i * 0.8f
            val h = (0.28f + 0.72f * abs(sin(phase))).coerceAtMost(1f) * size.height
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

@Composable
private fun CourseCard(course: Course, backdrop: LayerBackdrop, index: Int) {
    LiquidGlassSurface(
        backdrop = backdrop,
        shape = RoundedCornerShape(16.dp),
        blurRadius = 20.dp,
        tintAlpha = 0.34f,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .cardEnter(index),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
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
    val cs = MaterialTheme.colorScheme
    when (status) {
        // 已总结：标志性「四分之三圆 + 勾」
        CourseStatus.DONE -> ThreeQuarterCheck(size = 28.dp, color = KewuGreen)
        CourseStatus.LIVE -> {
            val pulse by rememberInfiniteTransition().animateFloat(
                0.65f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse),
            )
            Canvas(Modifier.size(28.dp)) {
                drawCircle(color = cs.tertiary.copy(alpha = 0.22f * pulse + 0.1f))
                drawCircle(color = cs.tertiary, radius = size.width * 0.17f * pulse)
            }
        }
        CourseStatus.SUMMARIZING -> {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = cs.primary, strokeWidth = 2.5.dp, strokeCap = StrokeCap.Round,
                )
            }
        }
        CourseStatus.FAILED -> {
            Canvas(Modifier.size(28.dp)) {
                drawCircle(color = cs.error)
                drawCircle(
                    color = Color.White, radius = size.width * 0.055f,
                    center = Offset(size.width / 2f, size.height * 0.71f),
                )
                drawRect(
                    color = Color.White,
                    topLeft = Offset(size.width * 0.455f, size.height * 0.28f),
                    size = Size(size.width * 0.09f, size.height * 0.28f),
                )
            }
        }
        CourseStatus.PENDING -> {
            Canvas(Modifier.size(28.dp)) {
                drawCircle(
                    color = cs.onSurfaceVariant.copy(alpha = 0.45f),
                    style = Stroke(width = size.width * 0.09f),
                )
            }
        }
    }
}

@Composable
private fun CourseContent(course: Course) {
    val cs = MaterialTheme.colorScheme
    // 标题行：课程名 + 时间（单行）
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            course.name, color = cs.onSurface, fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1,
        )
        if (course.time.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(course.time, color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
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
    val cs = MaterialTheme.colorScheme
    Spacer(Modifier.height(3.dp))
    MetaText("${course.room} · ${course.teacher}")
    Spacer(Modifier.height(8.dp))
    StatusTag("未开始", cs.surfaceVariant, cs.onSurfaceVariant)
}

@Composable
private fun LiveBody(course: Course) {
    val cs = MaterialTheme.colorScheme
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
    val cs = MaterialTheme.colorScheme
    Spacer(Modifier.height(3.dp))
    Text(
        course.summary.ifEmpty { "本节课暂无摘要" },
        color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 2,
    )
    if (course.devices > 0 || course.aiReady) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (course.devices > 0) StatusTag("${course.devices} 台设备", cs.primaryContainer, cs.onPrimaryContainer)
            if (course.aiReady) StatusTag("AI 已就绪", cs.primaryContainer, cs.onPrimaryContainer)
        }
    }
}

@Composable
private fun SummarizingBody(course: Course) {
    val cs = MaterialTheme.colorScheme
    Spacer(Modifier.height(3.dp))
    Text(
        "AI 正在整理课堂纪要…", color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1,
    )
    Spacer(Modifier.height(3.dp))
    Text(
        course.estWait, color = cs.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1,
    )
}

@Composable
private fun FailedBody(course: Course) {
    val cs = MaterialTheme.colorScheme
    Spacer(Modifier.height(3.dp))
    Text("录音上传不完整", color = cs.error, fontSize = 13.sp, maxLines = 1)
    Spacer(Modifier.height(8.dp))
    StatusTag("点击重试", cs.errorContainer, cs.onErrorContainer)
}

@Composable
private fun MetaText(text: String) {
    Text(
        text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1,
    )
}

/** Mishka 风格小标签：胶囊底 + 对应 on 色文字。 */
@Composable
private fun StatusTag(label: String, background: Color, content: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(label, color = content, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun MoreBar(showAll: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .squircleCard(16.dp, cs.surfaceVariant)
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (showAll) "收起历史课程" else "查看全部课程",
            color = cs.onSurfaceVariant, fontSize = 14.sp,
            fontWeight = FontWeight.Medium, maxLines = 1, textAlign = TextAlign.Center,
        )
    }
}

// 卡片入场：从下往上浮动，按 index 错峰。
// 注意：这里**不能**用 alpha() 包裹玻璃卡片 —— 会为子树创建离屏 layer，
// 而 coui 的 drawBackdrop 要把 backdrop 层映射到子树内，首帧极易算出非法尺寸导致
// RenderThread SIGSEGV。所以入场只做位移。
@Composable
private fun Modifier.cardEnter(index: Int): Modifier {
    var target by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) { delay((index * 35L).coerceAtMost(400L)); target = 1f }
    val p by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(360, easing = FastOutSlowInEasing),
    )
    return this.offset { IntOffset(0, ((1f - p) * 12f).dp.roundToPx()) }
}
