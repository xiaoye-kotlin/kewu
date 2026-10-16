package com.kewu.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.kewu.app.KewuGreen
import com.kewu.app.ui.liquidglass.LiquidGlassSurface
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import kotlin.math.abs
import kotlin.math.sin
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

// ───────────────────────── 课堂主页 ─────────────────────────

/**
 * 课堂主页：仿 KernelSU 主页信息架构（大状态卡 + 统计条 + 列表卡流），
 * 配色全部走 [MaterialTheme] 的默认 colorScheme / typography，不自定义背景色。
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
    // 所以背景单独成层（挂 layerBackdrop），玻璃内容与之**平级** —— 与底部导航栏同一套结构。
    val pageBackdrop = rememberGlassBackdrop()
    val scrollState = rememberScrollState()

    // 滚动方向 → 收起/展开底部导航栏（向下浏览内容时收起，回到顶部时展开）
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
                .padding(horizontal = 16.dp)
                .padding(bottom = 96.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            GreetingBar(name = "刘宇豪") { onGoToTab(3) }
            Spacer(Modifier.height(10.dp))
            StatusCard(courses)
            Spacer(Modifier.height(13.dp))
            OverviewStrip(courses)
            if (recording) {
                Spacer(Modifier.height(13.dp))
                RecordingOverlay(devices, pageBackdrop)
            }
            Spacer(Modifier.height(6.dp))
            courses.forEachIndexed { i, c -> CourseCard(c, pageBackdrop, i) }
            Spacer(Modifier.height(6.dp))
            MoreBar(showAll) { showAll = !showAll }
        }
    }
}

// ───────────────────────── 顶部：问候栏 ─────────────────────────

@Composable
private fun GreetingBar(name: String, onAvatar: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(rememberGreeting(), color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Text(
                name, color = cs.onSurface, style = MaterialTheme.typography.headlineSmall,
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
            .size(42.dp)
            .clip(CircleShape)
            .background(cs.secondaryContainer)
            .clickable { onAvatar() },
        contentAlignment = Alignment.Center,
    ) {
        Text("刘", color = cs.onSecondaryContainer, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    }
}

// ───────────────────────── 大状态卡（KernelSU StatusCard 结构） ─────────────────────────

/**
 * 仿 KernelSU 的 StatusCard：`Surface(shapes.large)` 容器 + leading 状态徽章
 * + 标题/副标题两行 + trailing 环形进度。标志性的「绿色勾勾」放在 leading。
 */
@Composable
private fun StatusCard(courses: List<Course>) {
    val cs = MaterialTheme.colorScheme
    val live = courses.firstOrNull { it.status == CourseStatus.LIVE }
    val done = courses.count { it.status == CourseStatus.DONE }
    val percent = if (courses.isNotEmpty()) done.toFloat() / courses.size else 0f

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = cs.secondaryContainer,
        contentColor = cs.onSecondaryContainer,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CheckBadge(size = 46.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (live != null) "正在上课" else "今日课堂已就绪",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (live != null) "${live.name} · ${live.room}" else "全部课程已生成 AI 纪要",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSecondaryContainer.copy(alpha = 0.75f), maxLines = 1,
                )
            }
            Spacer(Modifier.width(10.dp))
            CircularProgress(percent)
        }
    }
}

/** 标志性「绿色勾勾」徽章：绿色实心圆 + 白色对勾（KernelSU CheckCircle 的放大加粗版）。 */
@Composable
private fun CheckBadge(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        drawCircle(color = KewuGreen)
        val path = Path().apply {
            moveTo(s * 0.27f, s * 0.51f)
            lineTo(s * 0.43f, s * 0.67f)
            lineTo(s * 0.73f, s * 0.34f)
        }
        drawPath(
            path = path,
            color = Color.White,
            style = Stroke(width = s * 0.095f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** 课程状态徽章：已总结=绿色勾勾，其余按状态画不同图形。 */
@Composable
private fun StatusBadge(status: CourseStatus) {
    val cs = MaterialTheme.colorScheme
    when (status) {
        CourseStatus.DONE -> CheckBadge(size = 28.dp)
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

// ───────────────────────── 今日概览（三格统计，永不换行） ─────────────────────────

/**
 * 今日概览：改成三等分「数字 + 短标签」格子。
 * 之前是一长串文字（「今天 6 节课 · 2 节已总结 · 1 个作业待交」）在窄屏会折到第二行，
 * 这里每格内容都很短并显式 maxLines = 1，保证任何宽度都单行。
 */
@Composable
private fun OverviewStrip(courses: List<Course>) {
    val cs = MaterialTheme.colorScheme
    val hw = courses.count { it.status == CourseStatus.DONE && it.homework }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = cs.surfaceVariant,
        contentColor = cs.onSurfaceVariant,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatCell("${courses.size}", "节课", Modifier.weight(1f))
            VDivider()
            StatCell("${courses.count { it.status == CourseStatus.DONE }}", "已总结", Modifier.weight(1f))
            VDivider()
            StatCell("$hw", "作业待交", Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCell(number: String, label: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            number, color = cs.onSurface, style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label, color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
            maxLines = 1, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun VDivider() {
    Box(
        Modifier
            .height(26.dp)
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun CircularProgress(percent: Float) {
    val cs = MaterialTheme.colorScheme
    val animated by animateFloatAsState(
        targetValue = percent,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 380f),
    )
    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { animated },
            modifier = Modifier.size(52.dp),
            color = cs.primary,
            strokeWidth = 5.dp,
            trackColor = cs.onSecondaryContainer.copy(alpha = 0.14f),
            strokeCap = StrokeCap.Round,
        )
        Text(
            "${(percent * 100).toInt()}%", color = cs.onSecondaryContainer,
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
        )
    }
}

// ───────────────────────── 录音浮层（coui 毛玻璃） ─────────────────────────

@Composable
private fun RecordingOverlay(devices: List<RecordingDevice>, backdrop: LayerBackdrop) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    LiquidGlassSurface(
        backdrop = backdrop,
        shape = RoundedCornerShape(18.dp),
        blurRadius = 20.dp,
        tintAlpha = 0.3f,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Waveform()
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "高等数学 · 正在录音", color = cs.onSurface,
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        "00:23:45 · 3 台设备采集中", color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium, maxLines = 1,
                    )
                }
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(cs.surfaceVariant),
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
                            it.name, color = cs.onSurface, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f), maxLines = 1,
                        )
                        Text(it.state, color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
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
    Canvas(Modifier.size(38.dp, 28.dp)) {
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
        shape = RoundedCornerShape(20.dp),
        blurRadius = 20.dp,
        tintAlpha = 0.34f,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .cardEnter(index),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusBadge(course.status)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) { CourseContent(course) }
        }
    }
}

@Composable
private fun CourseContent(course: Course) {
    val cs = MaterialTheme.colorScheme
    // 标题行：课程名 + 时间（单行）
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            course.name, color = cs.onSurface, style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1,
        )
        if (course.time.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                course.time, color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium, maxLines = 1,
            )
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
        color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2,
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
        "AI 正在整理课堂纪要…", color = cs.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall, maxLines = 1,
    )
    Spacer(Modifier.height(3.dp))
    Text(
        course.estWait, color = cs.onSurfaceVariant.copy(alpha = 0.7f),
        style = MaterialTheme.typography.labelSmall, maxLines = 1,
    )
}

@Composable
private fun FailedBody(course: Course) {
    val cs = MaterialTheme.colorScheme
    Spacer(Modifier.height(3.dp))
    Text("录音上传不完整", color = cs.error, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    Spacer(Modifier.height(8.dp))
    StatusTag("点击重试", cs.errorContainer, cs.onErrorContainer)
}

@Composable
private fun MetaText(text: String) {
    Text(
        text, color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall, maxLines = 1,
    )
}

/** KernelSU 风格的小标签（StatusTag）：胶囊底 + 对应 on 色文字。 */
@Composable
private fun StatusTag(label: String, background: Color, content: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(label, color = content, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun MoreBar(showAll: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(cs.surfaceVariant)
            .clickable { onClick() }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (showAll) "收起历史课程" else "查看全部课程",
            color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge, maxLines = 1,
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
