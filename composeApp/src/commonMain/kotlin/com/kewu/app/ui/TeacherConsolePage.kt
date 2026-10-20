package com.kewu.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.Analytics
import com.kewu.app.AttendanceCode
import com.kewu.app.AttendanceRealtime
import com.kewu.app.AttendanceSession
import com.kewu.app.AttendanceStats
import com.kewu.app.BackHandler
import com.kewu.app.CourseInfo
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuBlue
import com.kewu.app.KewuGreen
import com.kewu.app.TeacherConsole
import com.kewu.app.haptic
import com.kewu.app.hapticClickable
import com.kewu.app.kvGet
import com.kewu.app.kvSet
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ───────────────────────── 教师端 · 控制台（tab 0） ─────────────────────────
//
// 版式延续学生端首页的 Mishka 语言（2026-10-19 用户要求）：
//   问候栏 → 状态区（**左一大卡 + 右两张堆叠小卡**，大卡带 170dp「大半圆+勾」超大水印）→
//   一键开课/上课中面板 → 课后报告 → 我的课程。
// 教师端三态（学生端「无课/正在上课」的基础上加「即将上课」）：
//   正在上课 = 有进行中的签到（琥珀「!」）；即将上课 = 有课程但未开课（品牌蓝勾）；
//   无课 = 一门课都没有（品牌绿勾）。圆圈勾勾复用 [ThreeQuarterCheck]，仅换色与文案。
// 上下课闭环：一键发起签到（createAttendance）→ 动态二维码 + 实时看板 → 结束课程（closeAttendance），
// 进行中会话持久化 kv（teacher_active_session）。
//
// 组件约定（2026-10-19 重构）：除液态玻璃（KewuGlassCard）外一律用 coui 原生组件
// （Button / Chip / Card 的按压反馈全走 COUI 原生 Tint + Scale），不再自造 Box 按钮。

/** 控制台根下钻：NONE=主页 / SESSIONS=课程签到历史 / DETAIL=签到详情（复用 AttendanceHub）。 */
private sealed class ConsoleDrill {
    data object NONE : ConsoleDrill()
    data class SESSIONS(val course: CourseInfo) : ConsoleDrill()
    data class DETAIL(val attendanceId: String) : ConsoleDrill()
}

@Composable
fun TeacherConsolePage(
    onGoToTab: (Int) -> Unit = {},
    onNavHide: (Boolean) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val pageBackdrop = rememberGlassBackdrop()
    val scrollState = rememberScrollState()

    var drill by remember { mutableStateOf<ConsoleDrill>(ConsoleDrill.NONE) }
    BackHandler(enabled = drill != ConsoleDrill.NONE) { drill = ConsoleDrill.NONE }

    // 数据
    var courses by remember { mutableStateOf<List<CourseInfo>?>(null) }
    var activeSession by remember { mutableStateOf<AttendanceSession?>(null) }
    var activeLoaded by remember { mutableStateOf(false) }
    // ★ 控制台聚合（2026-10-20 后端第 5 节）：今日课程/待批阅红点/今日签到率一次拿全
    var console by remember { mutableStateOf<TeacherConsole?>(null) }
    var teacherName by remember {
        mutableStateOf(KewuApi.currentUser?.user?.nickname ?: KewuApi.username ?: "老师")
    }

    fun reload() {
        scope.launch {
            if (KewuApi.currentUser == null) KewuApi.me()
            teacherName = KewuApi.currentUser?.user?.nickname ?: KewuApi.username ?: teacherName
            // ★ 控制台聚合优先：todayCourses 带班级名/学生数/上课状态/今日签到率
            val c = KewuApi.getTeacherConsole()
            console = c
            // 课程列表：聚合今日课程 → 已绑定课程 → 全量课程列表（逐级兜底，离线为空不崩）
            courses = c?.todayCourses
                ?.map { CourseInfo(it.courseId, it.courseName, "", teacherName) }
                ?.takeIf { it.isNotEmpty() }
                ?: KewuApi.currentUser?.courses?.takeIf { it.isNotEmpty() }
                ?: KewuApi.getCourses()
            // 恢复进行中的签到（kv 持久化）；若后端已关闭则清掉
            val sid = kvGet("teacher_active_session")
            if (!sid.isNullOrBlank()) {
                val s = KewuApi.getAttendance(sid)
                if (s != null && s.state != "closed") activeSession = s
                else kvSet("teacher_active_session", "")
            }
            // 无本地会话但后端课堂状态 ON（别处在上课/上次杀进程丢 kv）→ 提示仍在上课
            if (activeSession == null && sid.isNullOrBlank()) {
                val onCourseId = c?.todayCourses?.firstOrNull { it.state == "ON" }?.courseId
                    ?: courses?.firstNotNullOfOrNull { co ->
                        KewuApi.getClassState(co.id)?.takeIf { it.state == "ON" }?.let { co.id }
                    }
                if (onCourseId != null) {
                    // 找到该课今天的 OPEN 签到会话，恢复上课中面板
                    KewuApi.getAttendances(onCourseId)
                        ?.firstOrNull { it.state == "OPEN" || it.state == "open" }
                        ?.let { s ->
                            activeSession = s
                            kvSet("teacher_active_session", s.id)
                        }
                }
            }
            activeLoaded = true
        }
    }
    LaunchedEffect(Unit) { reload() }

    // 导航栏随滚动收起（与学生端同款增量判断）
    LaunchedEffect(scrollState) {
        var last = 0
        snapshotFlow { scrollState.value }.collect { cur ->
            if (cur <= 0) onNavHide(false)
            else {
                val d = cur - last
                if (d > 6) onNavHide(true) else if (d < -6) onNavHide(false)
            }
            last = cur
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(pageBackdrop)
                .background(cs.background),
        )
        AnimatedContent(
            targetState = drill,
            transitionSpec = {
                // 与全 app 下钻同款：仅 slide 无 fade 共享轴（placement 位移对采样者安全）
                slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { w -> w } togetherWith
                    slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { w -> -w / 5 }
            },
            modifier = Modifier.fillMaxSize(),
            label = "consoleNav",
        ) { d ->
            when (d) {
                ConsoleDrill.NONE -> ConsoleHome(
                    backdrop = pageBackdrop,
                    scrollState = scrollState,
                    teacherName = teacherName,
                    courses = courses,
                    console = console,
                    activeSession = activeSession,
                    activeLoaded = activeLoaded,
                    onGoToTab = onGoToTab,
                    onStarted = { s ->
                        activeSession = s
                        kvSet("teacher_active_session", s.id)
                    },
                    onClosed = {
                        activeSession = null
                        kvSet("teacher_active_session", "")
                        reload()
                    },
                    onOpenCourse = { drill = ConsoleDrill.SESSIONS(it) },
                )
                is ConsoleDrill.SESSIONS -> CourseSessionsScreen(
                    course = d.course,
                    onBack = { drill = ConsoleDrill.NONE },
                    onOpenSession = { drill = ConsoleDrill.DETAIL(it) },
                )
                is ConsoleDrill.DETAIL -> AttendanceDetailScreen(
                    attendanceId = d.attendanceId,
                    studentId = KewuApi.username ?: "",
                    studentName = teacherName,
                    onBack = { drill = ConsoleDrill.NONE },
                )
            }
        }
    }
}

// ───────────────────────── 控制台主页 ─────────────────────────

@Composable
private fun ConsoleHome(
    backdrop: LayerBackdrop,
    scrollState: androidx.compose.foundation.ScrollState,
    teacherName: String,
    courses: List<CourseInfo>?,
    console: TeacherConsole?,
    activeSession: AttendanceSession?,
    activeLoaded: Boolean,
    onGoToTab: (Int) -> Unit,
    onStarted: (AttendanceSession) -> Unit,
    onClosed: () -> Unit,
    onOpenCourse: (CourseInfo) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(bottom = 118.dp),
    ) {
        Spacer(Modifier.height(12.dp))

        // 问候栏（与学生端首页同款：问候语 + 姓名 + 头像 → 我的 tab）
        Box(Modifier.greetIn(40)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(rememberGreeting(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp, maxLines = 1)
                    Text(teacherName, color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                TeacherAvatar(teacherName) { onGoToTab(3) }
            }
        }

        // 状态区（Mishka 版式：左一大卡 + 右两张堆叠小卡）
        Box(Modifier.macPopIn(140)) {
            TeacherStatusSection(
                courseCount = courses?.size ?: 0,
                live = activeSession,
                loading = !activeLoaded,
                console = console,
                onAssistant = { onGoToTab(1) },
                onTask = { onGoToTab(2) },
            )
        }

        // ── 一键开课 / 上课中面板（与状态区统一的 squircle 白卡，非玻璃） ──
        val p1 = rememberEnterProgress(1, baseDelayMs = 200L)
        Box(Modifier.riseEnter(p1, 30f)) {
            if (!activeLoaded) {
                Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp, modifier = Modifier.size(26.dp))
                }
            } else if (activeSession == null) {
                QuickStartCard(courses = courses, onStarted = onStarted)
            } else {
                LiveSessionCard(session = activeSession, onClosed = onClosed)
            }
        }

        // ── 我的课程（点进看签到历史；与学生端首页「课程列表」同位置） ──
        val p4 = rememberEnterProgress(4, baseDelayMs = 380L)
        GroupLabel("我的课程", Modifier.riseEnter(p4))
        when {
            courses == null -> Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
            }
            courses.isEmpty() -> Text(
                "暂无课程，可先到「我的 → 新建课程」创建",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            else -> courses.forEachIndexed { i, c ->
                val pc = rememberEnterProgress(5 + i, baseDelayMs = 440L, stepMs = 50L)
                Box(Modifier.riseEnter(pc, 24f)) {
                    Card(
                        onClick = { haptic(HapticKind.Tick); onOpenCourse(c) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(c.name.take(2), color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                Spacer(Modifier.height(2.dp))
                                Text("ID: ${c.id}", fontSize = 12.sp, maxLines = 1)
                            }
                            Text("签到 ›", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

// ───────────────────────── 状态区（延续学生端 Mishka 版式，教师三态） ─────────────────────────

/**
 * 教师端三态（2026-10-19）：
 * - 正在上课：有进行中的签到 → 琥珀「!」（[ExclaimMark]），标题「! 当前正在上课」；
 * - 即将上课：有课程但还没开课 → 品牌蓝勾（[ThreeQuarterCheck] 蓝色变体），标题「即将上课」；
 * - 无课：一门课都没有 → 品牌绿勾（与学生端「无课」同语言）。
 * 左大卡（170dp 超大水印，右下角裁切）+ 右两张堆叠小卡，与学生端 [StatusSection] 同一套结构。
 */
@Composable
private fun TeacherStatusSection(
    courseCount: Int,
    live: AttendanceSession?,
    loading: Boolean,
    console: TeacherConsole?,
    onAssistant: () -> Unit,
    onTask: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val watermarkGreen = Color(0xFF0B8A54) // 与学生端 WatermarkGreen 同款深绿（ private，本地复写）

    val statusTint: Color
    val title: String
    val line2: String
    val line3: String
    when {
        live != null -> {
            statusTint = Color(0xFFFFB300) // 琥珀（与学生端「正在上课」同语义色）
            title = "！当前正在上课"
            line2 = live.title
            line3 = "动态码已生成 · 学生扫码中"
        }
        loading -> {
            statusTint = KewuBlue
            title = "正在同步…"
            line2 = "正在同步课程与签到…"
            line3 = "请稍候"
        }
        courseCount > 0 -> {
            statusTint = KewuBlue // 品牌蓝勾 = 即将上课（有课待开）
            title = "即将上课"
            line2 = "今天共 $courseCount 门课"
            line3 = "点击下方「开始上课」发起签到"
        }
        else -> {
            statusTint = KewuGreen
            title = "无课"
            line2 = "今日课程已全部结束"
            line3 = "好好休息，或提前准备教案"
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp, bottom = 6.dp)
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧大状态卡（与学生端同结构：软阴影兄弟层 + 卡片层 + 内容裁剪层 + 超大水印）
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.fillMaxSize().squircleShadow(22.dp))
            Box(Modifier.fillMaxSize().squircleCard(22.dp, cs.surface, clip = false)) {
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .offset { IntOffset(52.dp.roundToPx(), 58.dp.roundToPx()) },
                        contentAlignment = Alignment.BottomEnd,
                    ) {
                        if (live != null) {
                            // ★ 2026-10-21：alpha 0.30→0.45 —— 点在琥珀 tinted 卡上太浅看不清（用户反馈）
                            ExclaimMark(size = 170.dp, color = statusTint, alpha = 0.45f)
                        } else {
                            ThreeQuarterCheck(size = 170.dp, color = watermarkGreen, alpha = 0.55f)
                        }
                    }
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Text(title, color = statusTint, fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Spacer(Modifier.height(2.dp))
                        Text(line2, color = cs.onSurfaceVariant, fontSize = 14.sp,
                            fontWeight = FontWeight.Medium, maxLines = 1)
                        Text(line3, color = cs.onSurfaceVariant, fontSize = 14.sp,
                            fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }

        // 右侧两张堆叠小卡（点进助教 / 作业 tab）
        // ★ 数据走控制台聚合：今日签到率（todayCheckRate）+ 待批阅（pendingReview，hasPending 挂红点）
        Column(Modifier.weight(1f).fillMaxHeight()) {
            TeacherMiniStatCard(
                label = "今日签到率",
                value = console?.let { "${(it.todayCheckRate * 100).toInt()}%" } ?: "—",
                modifier = Modifier.fillMaxWidth().weight(1f),
                onClick = onAssistant,
            )
            Spacer(Modifier.height(12.dp))
            TeacherMiniStatCard(
                label = "待批阅作业",
                value = console?.pendingReview?.toString() ?: "—",
                badge = console?.hasPending == true,
                modifier = Modifier.fillMaxWidth().weight(1f),
                onClick = onTask,
            )
        }
    }
}

/** 与学生端 [MiniStatCard]（ClassroomPage，private）同款的堆叠小卡：标签 + 大数值 + 可点箭头 + 可选红点。 */
@Composable
private fun TeacherMiniStatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    badge: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.then(if (onClick != null) Modifier.hapticClickable { onClick() } else Modifier),
    ) {
        Box(Modifier.fillMaxSize().squircleShadow(22.dp))
        Box(
            Modifier.fillMaxSize().squircleCard(22.dp, cs.surface).padding(16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
                        if (badge) {
                            Spacer(Modifier.width(5.dp))
                            Box(Modifier.size(7.dp).clip(CircleShape).background(cs.error))
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(value, color = cs.onSurface, fontSize = 20.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1)
                }
                if (onClick != null) {
                    Spacer(Modifier.width(6.dp))
                    Canvas(Modifier.size(13.dp)) {
                        val sw = size.width * 0.14f
                        val p = Path().apply {
                            moveTo(size.width * 0.35f, size.height * 0.2f)
                            lineTo(size.width * 0.65f, size.height * 0.5f)
                            lineTo(size.width * 0.35f, size.height * 0.8f)
                        }
                        drawPath(p, cs.onSurfaceVariant,
                            style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
            }
        }
    }
}

@Composable
private fun TeacherAvatar(name: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(cs.secondaryContainer)
            .hapticClickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(name.firstOrNull()?.toString() ?: "教", color = cs.onSecondaryContainer,
            fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
private fun rememberGreeting(): String {
    val hour = remember { ((kotlin.time.Clock.System.now().toEpochMilliseconds() / 3_600_000L + 8L) % 24L).toInt() }
    return when {
        hour < 6 -> "凌晨好"
        hour < 12 -> "早上好"
        hour < 14 -> "中午好"
        hour < 18 -> "下午好"
        else -> "晚上好"
    }
}

// ───────────────────────── 一键开课卡（与状态区统一的 squircle 白卡） ─────────────────────────

@Composable
private fun QuickStartCard(
    courses: List<CourseInfo>?,
    onStarted: (AttendanceSession) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var duration by remember { mutableStateOf("10") }
    var starting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
        // 底层：软阴影（白卡层次感，与状态区同款）
        Box(Modifier.fillMaxSize().squircleShadow(22.dp))
        // 上层：squircle 白卡
        Box(Modifier.fillMaxSize().squircleCard(22.dp, cs.surface)) {
            Column(Modifier.padding(18.dp)) {
            Text("一键开课", color = cs.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("发起签到即开课，学生扫码 / 暗号打卡", color = cs.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
            if (!expanded) {
                Button(
                    onClick = {
                        if (courses.isNullOrEmpty()) {
                            error = "暂无课程，请先在「我的」绑定课程"
                        } else {
                            expanded = true
                            if (selectedId == null) selectedId = courses.firstOrNull()?.id
                        }
                    },
                    colors = ButtonDefaults.buttonColors(color = KewuBlue, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("开始上课", fontWeight = FontWeight.Bold)
                }
            } else {
                Text("选择课程", color = cs.onSurfaceVariant, fontSize = 12.5.sp)
                Spacer(Modifier.height(6.dp))
                courses?.forEach { c ->
                    val sel = selectedId == c.id
                    Card(
                        onClick = { haptic(HapticKind.Tick); selectedId = c.id },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    ) {
                        Text(
                            c.name,
                            fontSize = 14.sp,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("签到时长（分钟）", color = cs.onSurfaceVariant, fontSize = 12.5.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("5", "10", "15").forEach { d ->
                        Chip(
                            selected = duration == d,
                            onClick = { haptic(HapticKind.Tick); duration = d },
                            label = d,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        val cid = selectedId ?: return@Button
                        val cname = courses?.firstOrNull { it.id == cid }?.name ?: "课堂"
                        scope.launch {
                            starting = true
                            error = null
                            val publisher = KewuApi.currentUser?.user?.nickname ?: KewuApi.username ?: ""
                            val s = KewuApi.createAttendance(
                                courseId = cid,
                                title = "$cname 课堂签到",
                                durationMinutes = duration.toIntOrNull() ?: 10,
                                publisher = publisher,
                            )
                            starting = false
                            if (s != null) {
                                haptic(HapticKind.Light)
                                // ★ 同步开课状态到后端（POST /api/teacher/class-state START）；
                                //   失败不阻塞签到主流程（状态查的是另一份存储，重进时会再同步）
                                scope.launch { KewuApi.setClassState(cid, "START") }
                                onStarted(s)
                            } else {
                                error = "发起失败，请检查网络后重试"
                            }
                        }
                    },
                    enabled = selectedId != null && !starting,
                    colors = ButtonDefaults.buttonColors(color = KewuGreen, contentColor = Color(0xFF0B3B26)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (starting) "正在发起…" else "发起签到并开课", fontWeight = FontWeight.Bold)
                }
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = cs.error, fontSize = 12.5.sp)
            }
            }
        }
    }
}

// ───────────────────────── 上课中面板（二维码 + 实时看板 + 结束课程） ─────────────────────────

@Composable
private fun LiveSessionCard(
    session: AttendanceSession,
    onClosed: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<AttendanceCode?>(null) }
    var remaining by remember { mutableStateOf(0) }
    var realtime by remember { mutableStateOf<AttendanceRealtime?>(null) }
    var stats by remember { mutableStateOf<AttendanceStats?>(null) }
    var closing by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }

    // 动态码：进场拉一次 + 倒计时归零自动刷新
    LaunchedEffect(session.id) {
        code = KewuApi.getAttendanceCode(session.id)?.also { remaining = it.remainingSeconds }
    }
    LaunchedEffect(code?.sessionId) {
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
            if (remaining <= 0) {
                code = KewuApi.getAttendanceCode(session.id)?.also { remaining = it.remainingSeconds }
            }
        }
    }
    // 实时看板：5s 轮询
    LaunchedEffect(session.id) {
        while (true) {
            KewuApi.getAttendanceRealtime(session.id)?.let { realtime = it }
            KewuApi.getAttendanceStats(session.id)?.let { stats = it }
            delay(5000)
        }
    }

    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
        // 底层：软阴影（与状态区/一键开课卡同款白卡层次）
        Box(Modifier.fillMaxSize().squircleShadow(22.dp))
        // 上层：squircle 白卡
        Box(Modifier.fillMaxSize().squircleCard(22.dp, cs.surface)) {
        Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // 状态行：绿点 + 上课中
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(KewuGreen))
                Spacer(Modifier.width(7.dp))
                Text("上课中 · ${session.title}", color = cs.onSurface, fontSize = 16.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Spacer(Modifier.height(14.dp))

            // 二维码（白底保对比度可扫，编码 qrContent）
            val qrContent = code?.qrContent?.takeIf { it.isNotBlank() }
                ?: code?.code?.takeIf { it.isNotBlank() }
            if (qrContent != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White)
                        .padding(14.dp),
                ) {
                    QrCodeView(text = qrContent, modifier = Modifier.size(180.dp), dark = Color.Black, light = Color.White)
                }
                Spacer(Modifier.height(10.dp))
            }
            Text("暗号", color = cs.onSurfaceVariant, fontSize = 12.sp)
            Text(code?.code ?: "…", color = KewuBlue, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text("${remaining}s 后自动刷新", color = cs.onSurfaceVariant, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))

            // 实时签到看板
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val rt = realtime
                LiveStat("已签", "${rt?.checked ?: stats?.checked ?: 0}", cs.primaryContainer, cs.onPrimaryContainer)
                LiveStat("应到", "${rt?.expected ?: stats?.expected ?: 0}", cs.surfaceVariant, cs.onSurface)
                LiveStat("签到率", "${(((rt?.checkRate ?: stats?.checkRate) ?: 0.0) * 100).toInt()}%",
                    KewuGreen.copy(alpha = 0.16f), KewuGreen)
            }
            // 签到率进度条
            val rate = ((realtime?.checkRate ?: stats?.checkRate) ?: 0.0).toFloat().coerceIn(0f, 1f)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(cs.surfaceVariant)) {
                Box(Modifier.fillMaxWidth(rate).height(8.dp).clip(RoundedCornerShape(4.dp)).background(KewuGreen))
            }
            realtime?.latest?.takeIf { it.isNotEmpty() }?.let { latest ->
                Spacer(Modifier.height(10.dp))
                Column(Modifier.fillMaxWidth()) {
                    latest.take(4).forEach {
                        Text("· ${it.studentName} 已签到（${it.secondsAfterStart}s）",
                            color = cs.onSurfaceVariant, fontSize = 12.5.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // 结束课程（二次确认防误触；coui 原生按钮）
            if (!confirmClose) {
                Button(
                    onClick = { confirmClose = true },
                    colors = ButtonDefaults.buttonColors(color = cs.error, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("结束课程", fontWeight = FontWeight.Bold) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { confirmClose = false },
                        colors = ButtonDefaults.buttonColors(
                            color = cs.surfaceVariant, contentColor = cs.onSurfaceVariant),
                        modifier = Modifier.weight(1f),
                    ) { Text("再想想") }
                    Button(
                        onClick = {
                            if (closing) return@Button
                            closing = true
                            scope.launch {
                                KewuApi.closeAttendance(session.id)
                                // ★ 同步下课状态到后端（POST /api/teacher/class-state END）
                                KewuApi.setClassState(session.courseId, "END")
                                closing = false
                                onClosed()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(color = cs.error, contentColor = Color.White),
                        modifier = Modifier.weight(1f),
                    ) { Text(if (closing) "正在结束…" else "确认结束", fontWeight = FontWeight.Bold) }
                }
            }
        }
        }
    }
}

@Composable
private fun RowScope.LiveStat(value: String, label: String, bg: Color, fg: Color) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(vertical = 10.dp).weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = fg, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(label, color = fg.copy(alpha = 0.75f), fontSize = 11.sp)
    }
}

// ───────────────────────── 课后报告卡片 ─────────────────────────

/** 学情画像摘要卡：真实接口 getAnalytics（掌握度条 + 薄弱知识点）。 */
@Composable
private fun AnalyticsSummaryCard(backdrop: LayerBackdrop, analytics: Analytics?) {
    val cs = MaterialTheme.colorScheme
    KewuGlassCard(
        backdrop = backdrop,
        shape = RoundedCornerShape(18.dp),
        blurRadius = 20.dp,
        tintAlpha = 0.30f,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(18.dp)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("知识点掌握度", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (analytics == null) {
                Text("暂无学情数据（发布作业并批阅后生成）", color = cs.onSurfaceVariant, fontSize = 13.sp)
            } else {
                if (analytics.weakPoints.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("薄弱点", color = cs.onSurfaceVariant, fontSize = 12.sp,
                            modifier = Modifier.padding(top = 3.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            analytics.weakPoints.take(3).forEach {
                                StatusTag(it, cs.errorContainer, cs.onErrorContainer)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                analytics.knowledgeMastery.take(4).forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Text(m.point, color = cs.onSurface, fontSize = 13.sp, modifier = Modifier.width(88.dp), maxLines = 1)
                        Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(cs.surfaceVariant)) {
                            Box(Modifier.fillMaxWidth(m.avgScoreRate.toFloat().coerceIn(0f, 1f))
                                .height(10.dp).clip(RoundedCornerShape(5.dp)).background(KewuGreen))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("${(m.avgScoreRate * 100).toInt()}%", color = cs.onSurface,
                            fontSize = 12.sp, modifier = Modifier.width(36.dp))
                    }
                }
            }
        }
    }
}

/** AI 纪要状态卡：后端纪要/融合接口尚未接 UI（见交接文档第八节），这里做状态占位展示。 */
@Composable
private fun AiSummaryCard(backdrop: LayerBackdrop, hasLive: Boolean) {
    val cs = MaterialTheme.colorScheme
    KewuGlassCard(
        backdrop = backdrop,
        shape = RoundedCornerShape(18.dp),
        blurRadius = 20.dp,
        tintAlpha = 0.30f,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(18.dp)),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AI 课堂纪要", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (hasLive) "正在进行多源录音采集，结束后自动生成纪要"
                    else "下课结束后，AI 自动融合多端录音生成纪要",
                    color = cs.onSurfaceVariant, fontSize = 12.5.sp, lineHeight = 18.sp,
                )
            }
            StatusTag(
                if (hasLive) "采集中" else "待生成",
                if (hasLive) KewuGreen.copy(alpha = 0.16f) else cs.surfaceVariant,
                if (hasLive) KewuGreen else cs.onSurfaceVariant,
            )
        }
    }
}

// ───────────────────────── 课程签到历史（coui Card 列表） ─────────────────────────

/** 某门课的签到历史列表（getAttendances），点进复用 AttendanceHub 详情。 */
@Composable
private fun CourseSessionsScreen(
    course: CourseInfo,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var sessions by remember { mutableStateOf<List<AttendanceSession>?>(null) }
    LaunchedEffect(course.id) { sessions = KewuApi.getAttendances(course.id) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
        HubTopBar("${course.name} · 签到记录", onBack)
        Spacer(Modifier.height(4.dp))
        when {
            sessions == null -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = cs.primary, strokeWidth = 3.dp, modifier = Modifier.size(26.dp))
            }
            sessions!!.isEmpty() -> Text(
                "暂无签到记录", color = cs.onSurfaceVariant, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            else -> sessions!!.forEach { s ->
                Card(
                    onClick = { haptic(HapticKind.Tick); onOpenSession(s.id) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.title, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            Spacer(Modifier.height(2.dp))
                            Text("${s.mode} · ${s.createdAt.ifBlank { "—" }}", fontSize = 12.sp, maxLines = 1)
                        }
                        StatusTag(
                            if (s.state == "closed") "已结束" else "进行中",
                            if (s.state == "closed") cs.surfaceVariant else KewuGreen.copy(alpha = 0.16f),
                            if (s.state == "closed") cs.onSurfaceVariant else KewuGreen,
                        )
                    }
                }
            }
        }
    }
}
