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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
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

// ───────────────────────── 数据模型（mock） ─────────────────────────

/** 课程卡片状态：待上课 / 正在上课 / 已结束已总结 / 总结生成中 / 上传或总结失败 */
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
    Course("高等数学", room = "主楼 305", teacher = "王老师", time = "14:00 - 15:40", status = CourseStatus.LIVE),
    Course(
        "大学英语", room = "外语楼 202", teacher = "李老师", time = "10:00 - 11:40",
        status = CourseStatus.DONE, summary = "讲了定语从句与虚拟语气的嵌套用法，以及长难句拆解思路",
        homework = true, devices = 3, aiReady = true, highlights = 2,
    ),
    Course(
        "线性代数", room = "主楼 401", teacher = "张老师", time = "08:00 - 09:40",
        status = CourseStatus.DONE, summary = "特征值与特征向量的几何意义，以及相似对角化条件",
        devices = 3, aiReady = true, highlights = 3,
    ),
    Course("大学物理", room = "实验楼 110", teacher = "陈老师", time = "明天 08:00", status = CourseStatus.PENDING),
    Course(
        "程序设计", room = "机房 B205", teacher = "刘老师", time = "昨天 15:00",
        status = CourseStatus.SUMMARIZING, estWait = "约 2 分钟",
    ),
    Course("马克思主义原理", room = "主楼 210", teacher = "赵老师", time = "前天 14:00",
        status = CourseStatus.FAILED),
)

private fun mockHistory(): List<Course> = listOf(
    Course("高等数学", room = "主楼 305", teacher = "王老师", time = "上周一 14:00",
        status = CourseStatus.DONE, summary = "多元函数偏导数的计算与链式法则",
        devices = 2, aiReady = true, highlights = 2),
    Course("大学英语", room = "外语楼 202", teacher = "李老师", time = "上周一 10:00",
        status = CourseStatus.DONE, summary = "英语议论文结构与论点展开练习", devices = 3, aiReady = true, highlights = 1),
)

// ───────────────────────── 主题色 ─────────────────────────

private val StatusPending = Color(0xFF8A93A6)
private val StatusLive = Color(0xFFFFB300)
private val StatusDone = Color(0xFF2BD17A)
private val StatusSummarizing = Color(0xFF3FA9F5)
private val StatusFailed = Color(0xFFFF5A5F)

private val TxtPrimary = Color(0xFFFFFFFF)
private val TxtSecondary = Color(0xFFC9D2DD)
private val TxtTertiary = Color(0xFF92A0AE)

// 课堂主页背景：深绿到墨绿的竖直渐变，玻璃卡片采样后透出淡淡绿调，衬出「绿色勾勾」主题。
private val PageBg = Brush.verticalGradient(
    0f to Color(0xFF12382C),
    0.55f to Color(0xFF0B241C),
    1f to Color(0xFF06120E),
)

// ───────────────────────── 课堂主页 ─────────────────────────

@Composable
fun ClassroomPage(onGoToTab: (Int) -> Unit) {
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

    // ★ coui 玻璃的硬约束：**采样者必须在被采样层之外**。
    // 底部导航栏能正常工作，正是因为它是「内容层（挂 layerBackdrop）」的兄弟节点。
    // 而课程卡片若直接放在内容层**里面**、去采样包含自己的层，实测会让 RenderThread 在
    // hwui 的 DamageAccumulator::dirty 里 SIGSEGV（整个进程秒崩，且是 native 崩溃、无 Java 异常）。
    // 所以这里单独开一个 pageBackdrop：背景层只画渐变，玻璃内容与它**平级**，采样背景。
    val pageBackdrop = rememberGlassBackdrop()

    Box(Modifier.fillMaxSize()) {
        // 背景层：只画渐变，是玻璃的采样源；固定不随内容滚动
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(pageBackdrop)
                .background(PageBg),
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 96.dp),
        ) {
            GreetingBar(name = "田冀雪") { onGoToTab(3) }
            TodayOverview(courses)
            if (recording) RecordingOverlay(devices, pageBackdrop)
            Spacer(Modifier.height(2.dp))
            courses.forEachIndexed { i, c -> CourseCard(c, pageBackdrop, i) }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(KewuGreen.copy(alpha = 0.12f))
                    .clickable { showAll = !showAll },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (showAll) "收起历史课程" else "查看全部课程",
                    color = KewuGreen, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun GreetingBar(name: String, onAvatar: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(rememberGreeting(), color = TxtSecondary, fontSize = 15.sp)
            Text(
                name, color = TxtPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = androidx.compose.ui.Modifier.padding(top = 2.dp),
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
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(KewuGreen.copy(alpha = 0.25f))
            .border(1.5.dp, KewuGreen, CircleShape)
            .clickable { onAvatar() },
        contentAlignment = Alignment.Center,
    ) {
        Text("田", color = TxtPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

@Composable
private fun TodayOverview(courses: List<Course>) {
    val total = courses.size
    val done = courses.count { it.status == CourseStatus.DONE }
    val hw = courses.count { it.status == CourseStatus.DONE && it.homework }
    val percent = if (total > 0) done.toFloat() / total else 0f
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("今日概览", color = TxtSecondary, fontSize = 13.sp)
            Text(
                "今天 $total 节课 · $done 节已总结 · $hw 个作业待交",
                color = TxtPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        CircularProgress(percent)
    }
}

@Composable
private fun CircularProgress(percent: Float) {
    val animated by animateFloatAsState(
        targetValue = percent,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 380f),
    )
    Box(Modifier.size(66.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { animated },
            modifier = Modifier.size(66.dp),
            color = KewuGreen,
            strokeWidth = 7.dp,
            trackColor = Color.White.copy(alpha = 0.16f),
            strokeCap = StrokeCap.Round,
        )
        Text("${(percent * 100).toInt()}%", color = TxtPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RecordingOverlay(devices: List<RecordingDevice>, backdrop: LayerBackdrop) {
    var expanded by remember { mutableStateOf(false) }
    LiquidGlassSurface(
        backdrop = backdrop,
        shape = RoundedCornerShape(18.dp),
        blurRadius = 20.dp,
        tintAlpha = 0.24f,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Waveform()
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("高等数学 · 正在录音 00:23:45", color = TxtPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("3 台设备采集中", color = TxtSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                }
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("3", color = TxtPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                devices.forEach {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(7.dp).background(KewuGreen, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Text(it.name, color = TxtPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(it.state, color = TxtSecondary, fontSize = 12.sp)
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
    Canvas(Modifier.size(42.dp, 30.dp)) {
        val n = 5
        val bw = size.width / (n * 2f)
        val pi = 3.1415927f
        for (i in 0 until n) {
            val phase = t * 2f * pi + i * 0.8f
            val h = (0.28f + 0.72f * abs(sin(phase))).coerceAtMost(1f) * size.height
            drawRoundRect(
                color = KewuGreen,
                topLeft = androidx.compose.ui.geometry.Offset(i * 2f * bw + bw * 0.25f, (size.height - h) / 2f),
                size = androidx.compose.ui.geometry.Size(bw * 0.5f, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw * 0.25f),
            )
        }
    }
}

// ───────────────────────── 课程卡片 ─────────────────────────

@Composable
private fun CourseCard(course: Course, backdrop: LayerBackdrop, index: Int) {
    LiquidGlassSurface(
        backdrop = backdrop,
        shape = RoundedCornerShape(20.dp),
        blurRadius = 20.dp,
        tintAlpha = 0.18f,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 7.dp)
            .cardEnter(index),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
        ) {
            StatusBar(course.status)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.fillMaxWidth()) { CourseContent(course) }
        }
    }
}

@Composable
private fun StatusBar(status: CourseStatus) {
    val color = when (status) {
        CourseStatus.PENDING -> StatusPending
        CourseStatus.LIVE -> StatusLive
        CourseStatus.DONE -> StatusDone
        CourseStatus.SUMMARIZING -> StatusSummarizing
        CourseStatus.FAILED -> StatusFailed
    }
    if (status == CourseStatus.LIVE) {
        val pulse by rememberInfiniteTransition().animateFloat(
            0.55f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        )
        Box(
            Modifier
                .width(6.dp)
                .fillMaxHeight()
                .alpha(pulse)
                .background(color, RoundedCornerShape(3.dp)),
        )
    } else {
        Box(
            Modifier
                .width(6.dp)
                .fillMaxHeight()
                .background(color, RoundedCornerShape(3.dp)),
        )
    }
}

@Composable
private fun CourseContent(course: Course) {
    // 标题行：课程名 + 时间
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(course.name, color = TxtPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (course.time.isNotEmpty()) {
            Text(course.time, color = TxtSecondary, fontSize = 13.sp)
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
    if (course.room.isNotEmpty() || course.teacher.isNotEmpty()) {
        MetaText("${course.room} · ${course.teacher}")
    }
    Spacer(Modifier.height(10.dp))
    PillButton("签到未开始", color = Color.White, enabled = false)
}

@Composable
private fun LiveBody(course: Course) {
    if (course.room.isNotEmpty()) MetaText(course.room)
    Spacer(Modifier.height(8.dp))
    // 录音中标签 + 跳动小圆点
    Row(verticalAlignment = Alignment.CenterVertically) {
        LiveDot()
        Spacer(Modifier.width(6.dp))
        Text("录音中", color = StatusLive, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
    if (course.homework) {
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(StatusLive.copy(alpha = 0.18f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text("有新的随堂作业", color = StatusLive, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
    Spacer(Modifier.height(10.dp))
    PillButton("签到", color = StatusLive, enabled = true)
}

@Composable
private fun DoneBody(course: Course) {
    Spacer(Modifier.height(6.dp))
    Text(
        course.summary.ifEmpty { "本节课暂无摘要" },
        color = TxtPrimary, fontSize = 14.sp, lineHeight = 20.sp,
    )
    Spacer(Modifier.height(8.dp))
    if (course.devices > 0 || course.aiReady || course.highlights > 0) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (course.devices > 0) CheckTag("已融合 ${course.devices} 台设备录音")
            if (course.aiReady) CheckTag("AI 助教已就绪")
            if (course.highlights > 0) CheckTag("老师强调了 ${course.highlights} 个重点")
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton("看总结", color = KewuGreen, modifier = Modifier.weight(1f))
        PillButton("问助教", color = KewuGreen, modifier = Modifier.weight(1f))
        PillButton(
            "交作业", color = StatusFailed, modifier = Modifier.weight(1f),
            enabled = course.homework, badge = course.homework,
        )
    }
}

@Composable
private fun SummarizingBody(course: Course) {
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            color = StatusSummarizing, strokeWidth = 3.dp, strokeCap = StrokeCap.Round,
        )
        Spacer(Modifier.width(8.dp))
        Text("AI 正在整理课堂纪要…", color = TxtSecondary, fontSize = 14.sp)
    }
    Spacer(Modifier.height(6.dp))
    Text(course.estWait, color = TxtTertiary, fontSize = 12.sp)
}

@Composable
private fun FailedBody(course: Course) {
    Spacer(Modifier.height(8.dp))
    Text("录音上传不完整，点击重试", color = StatusFailed, fontSize = 14.sp)
    Spacer(Modifier.height(10.dp))
    PillButton("重试", color = StatusFailed, enabled = true)
}

@Composable
private fun MetaText(text: String) {
    Text(text, color = TxtSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun LiveDot() {
    val t by rememberInfiniteTransition().animateFloat(
        0.6f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse),
    )
    Box(Modifier.size(9.dp).scale(t).background(StatusLive, CircleShape))
}

@Composable
private fun CheckTag(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(KewuGreen.copy(alpha = 0.16f))
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text("✓ $text", color = KewuGreen, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PillButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    badge: Boolean = false,
    onClick: () -> Unit = {},
) {
    Box(
        modifier
            .height(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) color.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f))
            .clickable(enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                color = if (enabled) color else Color.White.copy(alpha = 0.4f),
                fontSize = 13.sp, fontWeight = FontWeight.Medium,
            )
            if (badge) {
                Spacer(Modifier.width(5.dp))
                Box(Modifier.size(7.dp).background(StatusFailed, CircleShape))
            }
        }
    }
}

// 卡片入场：从下往上浮动，按 index 错峰，营造列表浮现感（这是 Composable 扩展，内部持有动画状态）。
// 注意：这里**不能**用 alpha() 包裹玻璃卡片 —— alpha 会为子树创建离屏 RenderNode layer，
// 而 coui 的 drawBackdrop 要把 backdrop 层内容映射到这棵子树内，首帧 alpha≈0 时极易算出非法尺寸，
// 实测会让 RenderThread 在 hwui 的 DamageAccumulator::dirty 里 SIGSEGV（整个进程直接崩）。
// 所以入场只做位移，透明度变化放到卡片**内部**的普通内容上去做。
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
