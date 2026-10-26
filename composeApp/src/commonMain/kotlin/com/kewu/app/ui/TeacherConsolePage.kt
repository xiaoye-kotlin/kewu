package com.kewu.app.ui

import kotlinx.coroutines.isActive
import com.kewu.app.LockTabSwipe
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
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
import com.kewu.app.RecordingManager
import com.kewu.app.RecordingStatus
import com.kewu.app.TeacherConsole
import com.kewu.app.haptic
import com.kewu.app.kvGet
import com.kewu.app.kvSet
import com.kewu.app.platformActivity
import com.kewu.app.requestAudioPermission
import com.kewu.app.todayCn
import com.kewu.app.Notifications
import com.kewu.app.currentRole
import com.kewu.app.ui.NotificationBellButton
import com.kewu.app.ui.NotificationSheet
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.basic.Badge
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.ProgressIndicatorDefaults
import io.github.suqi8.coui.kmp.basic.Surface
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.ChevronForward
import io.github.suqi8.coui.kmp.icon.extended.Mic
import io.github.suqi8.coui.kmp.preference.RadioButtonPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ───────────────────────── 教师端 · 控制台（tab 0） ─────────────────────────
//
// 版式延续学生端首页的 Mishka 语言（2026-10-19 用户要求）：
//   问候栏 → 状态区（**左一大卡 + 右两张堆叠小卡**，大卡带 170dp「大半圆+勾」超大水印）→
//   一键开课/上课中面板 → 课后报告 → 我的课程。
// 教师三态（学生端「无课/正在上课」的基础上加「即将上课」）：
//   正在上课 = 有进行中的签到（琥珀「!」）；即将上课 = 有课程但未开课（品牌蓝勾）；
//   无课 = 一门课都没有（品牌绿勾）。圆圈勾勾复用 [ThreeQuarterCheck]，仅换色与文案。
// 上下课闭环：一键发起签到（createAttendance）→ 建 8081 课堂协同录音会话（createSession）→
// 动态二维码 + 实时看板 + 课堂录音 → 结束课程（closeAttendance），进行中会话持久化 kv（teacher_active_session）。
//
// 组件约定（2026-10-23 全局 coui 化）：除液态玻璃（KewuGlassCard）与标志性绘制
// （ThreeQuarterCheck / ExclaimMark 描绘动画、QrCodeView 动态码）外，卡片 / 按钮 / 设置行 / 角标 /
// 进度 / 标签 / 弹窗 / 文字一律 coui 原生件，颜色一律读 COUITheme.colorScheme。

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
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    val snackbar = LocalKewuSnackbar.current
    val pageBackdrop = rememberGlassBackdrop()
    val scrollState = rememberScrollState()

    var drill by remember { mutableStateOf<ConsoleDrill>(ConsoleDrill.NONE) }
    BackHandler(enabled = drill != ConsoleDrill.NONE) { drill = ConsoleDrill.NONE }
    LockTabSwipe(drill != ConsoleDrill.NONE)

    // 数据
    var courses by remember { mutableStateOf<List<CourseInfo>?>(null) }
    var activeSession by remember { mutableStateOf<AttendanceSession?>(null) }
    var activeLoaded by remember { mutableStateOf(false) }
    // ★ 控制台聚合（2026-10-20 后端第 5 节）：今日课程/待批阅红点/今日签到率一次拿全
    var console by remember { mutableStateOf<TeacherConsole?>(null) }
    var teacherName by remember {
        mutableStateOf(KewuApi.displayName("老师"))
    }

    fun reload() {
        scope.launch {
            if (KewuApi.currentUser == null) KewuApi.me()
            teacherName = KewuApi.displayName(teacherName)
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
                if (s != null && !s.state.equals("closed", ignoreCase = true)) activeSession = s
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
                        ?.firstOrNull { it.state.equals("OPEN", ignoreCase = true) }
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

    // 通知中心：铃铛未读数 + 弹窗显隐（首页系统通知，与助教 AI 红点无关）
    var showNotif by remember { mutableStateOf(false) }
    var notifUnread by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        // 2026-10-26：不再种入演示通知（「张三」「成绩已公布」等虚构数据会误导用户），后端通知接口上线前显示空态
        notifUnread = Notifications.unreadCount()
    }

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
                .background(cs.surface),
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
                    unread = notifUnread,
                    onOpenNotifications = {
                        notifUnread = 0
                        Notifications.markAllRead()
                        showNotif = true
                    },
                    onGoToTab = onGoToTab,
                    onStarted = { s ->
                        activeSession = s
                        kvSet("teacher_active_session", s.id)
                        // ★ 开课后的两件附带事放在页面级 scope 里做：QuickStartCard 会随 activeSession
                        //   非空立刻离开组合，它自己的 rememberCoroutineScope 跟着取消，请求可能被掐断。
                        scope.launch {
                            // 同步开课状态到后端（POST /api/teacher/class-state START）；
                            // 失败不阻塞签到主流程（状态查的是另一份存储，重进时会再同步）
                            KewuApi.setClassState(s.courseId, "START")
                            // ★ 建 8081 课堂协同录音会话（10.5）：同课同日的教师端 / 学生端录音归到同一会话，
                            //   下课后才能多端择优融合。失败（如 Redis 降级）只提示，不影响上课。
                            KewuApi.createSession(KewuApi.classSessionId(s.courseId, todayCn()), s.courseId)
                                ?.let { err -> snackbar.toast("课堂录音会话创建失败：$err（不影响签到）") }
                        }
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
        NotificationSheet(show = showNotif, onDismiss = {
            showNotif = false
            notifUnread = Notifications.unreadCount()
        })
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
    unread: Int,
    onOpenNotifications: () -> Unit,
    onGoToTab: (Int) -> Unit,
    onStarted: (AttendanceSession) -> Unit,
    onClosed: () -> Unit,
    onOpenCourse: (CourseInfo) -> Unit,
) {
    val cs = COUITheme.colorScheme
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
                    Text(rememberGreeting(), color = cs.onSurfaceSecondary, fontSize = 13.sp, maxLines = 1)
                    Text(teacherName, color = cs.onSurface, fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                NotificationBellButton(unread = unread, onClick = onOpenNotifications)
                Spacer(Modifier.width(8.dp))
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

        // ── 一键开课 / 上课中面板（coui 白卡，非玻璃） ──
        // （2026-10-26：原「跨课程待批阅」联调测试块已移除——正式入口在「作业」tab 顶部的待批阅卡）
        val p1 = rememberEnterProgress(1, baseDelayMs = 200L)
        Box(Modifier.riseEnter(p1, 30f)) {
            if (!activeLoaded) {
                KewuLoading()
            } else if (activeSession == null) {
                QuickStartCard(courses = courses, onStarted = onStarted)
            } else {
                LiveSessionCard(session = activeSession, onClosed = onClosed)
            }
        }

        // ── 我的课程（点进看签到历史；与学生端首页「课程列表」同位置） ──
        val p4 = rememberEnterProgress(4, baseDelayMs = 380L)
        KewuSectionTitle("我的课程", Modifier.riseEnter(p4))
        when {
            courses == null -> KewuLoading()
            courses.isEmpty() -> KewuEmpty("暂无课程，可先到「我的 → 新建课程」创建")
            else -> courses.forEachIndexed { i, c ->
                val pc = rememberEnterProgress(5 + i, baseDelayMs = 440L, stepMs = 50L)
                // ★ 控制台聚合富信息：班级名 / 学生数 / 上课状态 / 今日签到率（文档 5.11 建议的课程卡片）
                val tc = console?.todayCourses?.firstOrNull { it.courseId == c.id }
                Box(Modifier.riseEnter(pc, 24f)) {
                    Card(
                        onClick = { haptic(HapticKind.Tick); onOpenCourse(c) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
                        cornerRadius = 16.dp,
                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        colors = kewuCardColors(),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                modifier = Modifier.size(40.dp),
                                shape = RoundedCornerShape(10.dp),
                                color = cs.tertiaryContainer,
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(c.name.take(2), color = cs.onTertiaryContainer,
                                        fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name, color = cs.onSurface, fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Medium, maxLines = 1)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    tc?.let {
                                        buildString {
                                            if (it.className.isNotBlank()) append(it.className)
                                            if (it.studentCount > 0) {
                                                if (isNotEmpty()) append(" · ")
                                                append("${it.studentCount} 名学生")
                                            }
                                            it.todayCheckRate?.let { r ->
                                                if (isNotEmpty()) append(" · ")
                                                append("今日签到 ${(r * 100).toInt()}%")
                                            }
                                            if (isEmpty()) append("ID: ${c.id}")
                                        }
                                    } ?: "ID: ${c.id}",
                                    color = cs.onSurfaceSecondary, fontSize = 12.sp, maxLines = 1,
                                )
                            }
                            // 上课状态胶囊（ON=上课中琥珀 / OFF=未开课灰）
                            tc?.let {
                                Spacer(Modifier.width(8.dp))
                                KewuTag(
                                    if (it.state == "ON") "上课中" else "未开课",
                                    if (it.state == "ON") Color(0xFFFFB300).copy(alpha = 0.18f)
                                    else cs.secondaryContainer,
                                    if (it.state == "ON") Color(0xFF8A5A00)
                                    else cs.onSurfaceSecondary,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("签到", color = cs.onSurfaceSecondary, fontSize = 13.sp)
                            Icon(COUIIcons.ChevronForward, contentDescription = null,
                                tint = cs.onSurfaceVariantSummary, modifier = Modifier.size(16.dp))
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
 * 2026-10-23：白卡改 coui [Card]（squircleSurface 自带按圆角裁剪子内容，水印不会溢出圆角）。
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
    val cs = COUITheme.colorScheme
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
            // 课程数为 0 时旧版写「今日课程已全部结束」，与事实不符
            line2 = "还没有创建课程"
            line3 = "在「我的 → 新建课程」创建后即可开课"
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
        // 左侧大状态卡（coui Card + 内容裁剪层 + 超大水印）
        Card(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            cornerRadius = 22.dp,
            colors = kewuCardColors(),
        ) {
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
                    Text(line2, color = cs.onSurfaceSecondary, fontSize = 14.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(line3, color = cs.onSurfaceSecondary, fontSize = 14.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }

        // 右侧两张堆叠小卡（点进助教 / 作业 tab）
        // ★ 数据走控制台聚合：今日签到率（todayCheckRate）+ 待批阅（pendingReview，hasPending 挂 coui 红点）
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

/** 与学生端 MiniStatCard（ClassroomPage，private）同款的堆叠小卡：标签 + 大数值 + 可点箭头 + 可选红点（coui Card + Badge）。 */
@Composable
private fun TeacherMiniStatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    badge: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val cs = COUITheme.colorScheme
    Card(
        modifier = modifier,
        cornerRadius = 22.dp,
        colors = kewuCardColors(),
        onClick = onClick?.let { cb -> { haptic(HapticKind.Tick); cb() } },
    ) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.CenterStart) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = cs.onSurfaceSecondary, fontSize = 13.sp, maxLines = 1)
                        if (badge) {
                            Spacer(Modifier.width(5.dp))
                            Badge()
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(value, color = cs.onSurface, fontSize = 20.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1)
                }
                if (onClick != null) {
                    Spacer(Modifier.width(6.dp))
                    Icon(COUIIcons.ChevronForward, contentDescription = null,
                        tint = cs.onSurfaceVariantSummary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** 问候栏头像（coui [Surface] 可点圆形，点进「我的」）。 */
@Composable
private fun TeacherAvatar(name: String, onClick: () -> Unit) {
    val cs = COUITheme.colorScheme
    Surface(
        onClick = { haptic(HapticKind.Tick); onClick() },
        modifier = Modifier.size(36.dp),
        shape = CircleShape,
        color = cs.secondaryContainer,
        contentColor = cs.onSecondaryVariant,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(name.firstOrNull()?.toString() ?: "教", color = cs.onSecondaryVariant,
                fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
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

// ───────────────────────── 一键开课卡（coui Card + 单选设置行 + Chip） ─────────────────────────

@Composable
private fun QuickStartCard(
    courses: List<CourseInfo>?,
    onStarted: (AttendanceSession) -> Unit,
) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var duration by remember { mutableStateOf("10") }
    var starting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
        cornerRadius = 22.dp,
        colors = kewuCardColors(),
    ) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp)) {
            Text("一键开课", color = cs.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("发起签到即开课，学生扫码 / 暗号打卡", color = cs.onSurfaceSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
        }
        if (!expanded) {
            KewuPrimaryButton(
                text = "开始上课",
                onClick = {
                    if (courses.isNullOrEmpty()) {
                        error = "暂无课程，请先在「我的」绑定课程"
                    } else {
                        expanded = true
                        if (selectedId == null) selectedId = courses.firstOrNull()?.id
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            )
        } else {
            Text("选择课程", color = cs.onSurfaceSecondary, fontSize = 12.5.sp,
                modifier = Modifier.padding(horizontal = 18.dp))
            // 课程单选：coui 单选设置行（整行可点，COUI 原生按压反馈）
            courses?.forEach { c ->
                RadioButtonPreference(
                    title = c.name,
                    selected = selectedId == c.id,
                    onClick = { haptic(HapticKind.Tick); selectedId = c.id },
                )
            }
            Column(Modifier.padding(horizontal = 18.dp)) {
                Spacer(Modifier.height(4.dp))
                Text("签到时长（分钟）", color = cs.onSurfaceSecondary, fontSize = 12.5.sp)
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
                TextButton(
                    text = if (starting) "正在发起…" else "发起签到并开课",
                    onClick = {
                        val cid = selectedId ?: return@TextButton
                        val cname = courses?.firstOrNull { it.id == cid }?.name ?: "课堂"
                        haptic(HapticKind.Light)
                        scope.launch {
                            starting = true
                            error = null
                            val publisher = KewuApi.nameForServer()
                            val s = KewuApi.createAttendance(
                                courseId = cid,
                                title = "$cname 课堂签到",
                                durationMinutes = duration.toIntOrNull() ?: 10,
                                publisher = publisher,
                            )
                            starting = false
                            if (s != null) {
                                haptic(HapticKind.Light)
                                // class-state START 与 8081 协同会话由页面级 onStarted 负责（见 TeacherConsolePage）
                                onStarted(s)
                            } else {
                                error = "发起失败，请检查网络后重试"
                            }
                        }
                    },
                    enabled = selectedId != null && !starting,
                    colors = ButtonDefaults.textButtonColorsPrimary(
                        color = KewuGreen,
                        disabledColor = KewuGreen.copy(alpha = 0.35f),
                        textColor = Color(0xFF0B3B26),
                        disabledTextColor = Color(0xFF0B3B26).copy(alpha = 0.5f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 18.dp)) {
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = cs.error, fontSize = 12.5.sp)
            }
        }
    }
}

// ───────────────────────── 上课中面板（二维码 + 实时看板 + 课堂录音 + 结束课程） ─────────────────────────

@Composable
private fun LiveSessionCard(
    session: AttendanceSession,
    onClosed: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    val snackbar = LocalKewuSnackbar.current
    var code by remember { mutableStateOf<AttendanceCode?>(null) }
    var remaining by remember { mutableStateOf(0) }
    var realtime by remember { mutableStateOf<AttendanceRealtime?>(null) }
    var stats by remember { mutableStateOf<AttendanceStats?>(null) }
    // 8081 简易签到缓存里的到场人数（录音端同步口径，10.5）；拿不到就不显示
    var syncedCount by remember { mutableStateOf<Int?>(null) }
    var closing by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }

    // 动态码：进场拉一次 + 倒计时归零自动刷新
    LaunchedEffect(session.id) {
        code = KewuApi.getAttendanceCode(session.id)?.also { remaining = it.remainingSeconds }
    }
    // ★ 2026-10-26：常驻循环；旧版刷新失败时 remaining 停在 0、循环退出不再重试，页面挂着过期码显示「0s 后刷新」
    LaunchedEffect(session.id) {
        while (isActive) {
            delay(1000)
            if (remaining > 0) remaining -= 1
            if (remaining <= 0) {
                val next = KewuApi.getAttendanceCode(session.id)
                if (next != null) {
                    code = next
                    remaining = next.remainingSeconds
                } else {
                    delay(3000)
                }
            }
        }
    }
    // 实时看板：5s 轮询（顺带拉 8081 签到缓存人数；失败保留上次值，首次失败则不显示）
    LaunchedEffect(session.id) {
        while (true) {
            KewuApi.getAttendanceRealtime(session.id)?.let { realtime = it }
            KewuApi.getAttendanceStats(session.id)?.let { stats = it }
            KewuApi.getCheckins(session.courseId)?.let { syncedCount = it.size }
            delay(5000)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
        cornerRadius = 22.dp,
        colors = kewuCardColors(),
    ) {
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
                        .border(1.dp, cs.dividerLine, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                ) {
                    QrCodeView(text = qrContent, modifier = Modifier.size(180.dp), dark = Color.Black, light = Color.White)
                }
                Spacer(Modifier.height(10.dp))
            }
            Text("暗号", color = cs.onSurfaceSecondary, fontSize = 12.sp)
            Text(code?.code ?: "…", color = KewuBlue, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text("${remaining}s 后自动刷新", color = cs.onSurfaceSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))

            // 实时签到看板
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val rt = realtime
                LiveStat("已签", "${rt?.checked ?: stats?.checked ?: 0}", cs.tertiaryContainer, cs.onTertiaryContainer)
                LiveStat("应到", "${rt?.expected ?: stats?.expected ?: 0}", cs.secondaryContainer, cs.onSurface)
                LiveStat("签到率", "${(((rt?.checkRate ?: stats?.checkRate) ?: 0.0) * 100).toInt()}%",
                    KewuGreen.copy(alpha = 0.16f), KewuGreen)
            }
            // 签到率进度条（coui 线性进度）
            val rate = ((realtime?.checkRate ?: stats?.checkRate) ?: 0.0).toFloat().coerceIn(0f, 1f)
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = rate,
                modifier = Modifier.fillMaxWidth(),
                colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = KewuGreen),
            )
            syncedCount?.let {
                Spacer(Modifier.height(8.dp))
                Text("录音端已同步到场 $it 人", color = cs.onSurfaceVariantSummary, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth())
            }
            realtime?.latest?.takeIf { it.isNotEmpty() }?.let { latest ->
                Spacer(Modifier.height(10.dp))
                Column(Modifier.fillMaxWidth()) {
                    latest.take(4).forEach {
                        Text("· ${it.studentName} 已签到（${it.secondsAfterStart}s）",
                            color = cs.onSurfaceSecondary, fontSize = 12.5.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // ★ 课堂录音（教师端以 role=teacher 上传并带协同会话 ID，多端融合时教师端优先）
            TeacherRecordingPanel(courseId = session.courseId)
            Spacer(Modifier.height(16.dp))

            // 结束课程（coui 确认弹窗二次确认防误触）
            KewuPrimaryButton(
                text = if (closing) "正在结束…" else "结束课程",
                onClick = { if (!closing) confirmClose = true },
                enabled = !closing,
                danger = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    KewuConfirmDialog(
        show = confirmClose,
        title = "结束本节课？",
        summary = "将关闭签到并同步下课状态；若课堂录音仍在进行，会先停止并上传。",
        confirmText = "确认结束",
        cancelText = "再想想",
        onConfirm = {
            if (closing) return@KewuConfirmDialog
            closing = true
            // 下课时录音还开着 → 先停并上传，避免麦克风常驻（上传由单例负责，不随页面销毁）
            if (RecordingManager.isRecording) RecordingManager.stopAndUpload()
            // ★ 修复闪退：先关弹窗触发退场动画，等动画播完（onDismissFinished）后再做网络请求 +
            //   结构切换（onClosed 会置空 activeSession，整张 LiveSessionCard 随之移出组合）。
            //   原本在 onConfirm 里直接 launch 调 onClosed，会让「弹窗退场动画」与「卡片销毁」并发，
            //   coui 玻璃采样层在采样正在销毁的 backdrop 时 SIGSEGV 闪退。
            confirmClose = false
        },
        onDismissFinished = {
            // 仅在确认结束（closing==true）时执行；点「再想想」取消时 closing 仍为 false，跳过。
            if (!closing) return@KewuConfirmDialog
            scope.launch {
                // ★ 2026-10-26：closeAttendance 失败是「返回错误文案」而不是抛异常，旧版忽略返回值 →
                //   后端签到其实还开着，本地却清掉了上课状态。现在失败就提示并保留上课面板，老师可重试。
                val err = try {
                    KewuApi.closeAttendance(session.id)
                } catch (e: Throwable) {
                    e.message ?: "网络异常"
                }
                if (err != null) {
                    closing = false
                    snackbar.toast("结束签到失败：$err，请重试")
                    return@launch
                }
                // 同步下课状态到后端（POST /api/teacher/class-state END）；失败只提示，签到已关不影响下课
                runCatching { KewuApi.setClassState(session.courseId, "END") }
                closing = false
                onClosed()
            }
        },
        onDismiss = { confirmClose = false },
    )
}

/**
 * 上课中面板里的课堂录音控制（2026-10-23）：状态单源 = [RecordingManager.status]（进程级单例，
 * 旋屏 / 切 tab 不中断录音）。麦克风权限做法与学生端 ClassroomPage.RecordingOverlay 一致。
 * RecordingManager 按 kv 角色自动以 role=teacher 上传，并带同课同日协同会话 ID（classSessionId）。
 */
@Composable
private fun TeacherRecordingPanel(courseId: String) {
    val cs = COUITheme.colorScheme
    val st = RecordingManager.status.collectAsState().value
    val recording = st is RecordingStatus.Recording

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
        if (RecordingManager.hasPermission()) {
            RecordingManager.start(courseId)
        } else {
            val act = platformActivity()
            if (act != null) requestAudioPermission(act) { granted ->
                if (granted) RecordingManager.start(courseId)
                else RecordingManager.showError("需要麦克风权限才能录音，请在系统弹窗中允许")
            } else {
                RecordingManager.showError("需要麦克风权限才能录音，请在系统弹窗中允许")
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = cs.surface,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(38.dp),
                    shape = CircleShape,
                    color = if (recording) cs.error.copy(alpha = 0.14f) else cs.tertiaryContainer,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(COUIIcons.Mic, contentDescription = null,
                            tint = if (recording) cs.error else cs.onTertiaryContainer,
                            modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("课堂录音", color = cs.onSurface, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Spacer(Modifier.height(1.dp))
                    val sub = when (st) {
                        is RecordingStatus.Recording -> "录音中 ${fmtTime(st.elapsedSec)}"
                        is RecordingStatus.Uploading -> "录音完成，正在上传"
                        is RecordingStatus.Success -> "已上传，下课后自动参与多端融合"
                        is RecordingStatus.Error -> "录音未完成"
                        RecordingStatus.Idle -> "教师端录音融合时优先采用"
                    }
                    Text(sub, color = if (recording) cs.error else cs.onSurfaceSecondary,
                        fontSize = 12.5.sp, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    text = when (st) {
                        is RecordingStatus.Recording -> "停止并上传"
                        is RecordingStatus.Uploading -> "上传中…"
                        else -> "开始录音"
                    },
                    onClick = { onRecordClick() },
                    enabled = st !is RecordingStatus.Uploading,
                    minHeight = 36.dp,
                    minWidth = ButtonDefaults.MinWidthSmall,
                    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    colors = if (recording) ButtonDefaults.textButtonColorsPrimary(color = cs.error)
                        else ButtonDefaults.textButtonColorsPrimary(),
                )
            }

            // 上传状态（错误态带「重试」，仅当本地文件还在）
            when (st) {
                is RecordingStatus.Uploading -> {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is RecordingStatus.Success -> {
                    Spacer(Modifier.height(10.dp))
                    KewuTag("已上传 · ${st.recordingId}", KewuGreen.copy(alpha = 0.18f), KewuGreen)
                }
                is RecordingStatus.Error -> {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(st.msg, color = cs.error, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                        if (st.filePath != null) {
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                text = "重试",
                                onClick = { haptic(HapticKind.Tick); RecordingManager.retryUpload() },
                                minHeight = ButtonDefaults.MinHeightSmall,
                                minWidth = ButtonDefaults.MinWidthSmall,
                                insideMargin = ButtonDefaults.InsideMarginSmall,
                                colors = ButtonDefaults.textButtonColorsPrimary(color = cs.error),
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
private fun RowScope.LiveStat(value: String, label: String, bg: Color, fg: Color) {
    Surface(
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(12.dp),
        color = bg,
        contentColor = fg,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, color = fg, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(label, color = fg.copy(alpha = 0.75f), fontSize = 11.sp)
        }
    }
}

// ───────────────────────── 课后报告卡片 ─────────────────────────

/** 学情画像摘要卡：真实接口 getAnalytics（掌握度条 + 薄弱知识点）。玻璃本体保留，内部件 coui。 */
@Composable
private fun AnalyticsSummaryCard(backdrop: LayerBackdrop, analytics: Analytics?) {
    val cs = COUITheme.colorScheme
    KewuGlassCard(
        backdrop = backdrop,
        shape = RoundedCornerShape(18.dp),
        blurRadius = 20.dp,
        tintAlpha = 0.30f,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .border(1.dp, cs.dividerLine, RoundedCornerShape(18.dp)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("知识点掌握度", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (analytics == null) {
                Text("暂无学情数据（发布作业并批阅后生成）", color = cs.onSurfaceSecondary, fontSize = 13.sp)
            } else {
                if (analytics.weakPoints.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("薄弱点", color = cs.onSurfaceSecondary, fontSize = 12.sp,
                            modifier = Modifier.padding(top = 3.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            analytics.weakPoints.take(3).forEach {
                                KewuTag(it, cs.errorContainer, cs.onErrorContainer)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                analytics.knowledgeMastery.take(4).forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Text(m.point, color = cs.onSurface, fontSize = 13.sp, modifier = Modifier.width(88.dp), maxLines = 1)
                        LinearProgressIndicator(
                            progress = m.avgScoreRate.toFloat().coerceIn(0f, 1f),
                            modifier = Modifier.weight(1f),
                            colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = KewuGreen),
                        )
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
    val cs = COUITheme.colorScheme
    KewuGlassCard(
        backdrop = backdrop,
        shape = RoundedCornerShape(18.dp),
        blurRadius = 20.dp,
        tintAlpha = 0.30f,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .border(1.dp, cs.dividerLine, RoundedCornerShape(18.dp)),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AI 课堂纪要", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (hasLive) "正在进行多源录音采集，结束后自动生成纪要"
                    else "下课结束后，AI 自动融合多端录音生成纪要",
                    color = cs.onSurfaceSecondary, fontSize = 12.5.sp, lineHeight = 18.sp,
                )
            }
            KewuTag(
                if (hasLive) "采集中" else "待生成",
                if (hasLive) KewuGreen.copy(alpha = 0.16f) else cs.secondaryContainer,
                if (hasLive) KewuGreen else cs.onSurfaceSecondary,
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
    val cs = COUITheme.colorScheme
    var sessions by remember { mutableStateOf<List<AttendanceSession>?>(null) }
    LaunchedEffect(course.id) { sessions = KewuApi.getAttendances(course.id) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
        KewuTopBar("${course.name} · 签到记录", onBack = onBack)
        Spacer(Modifier.height(4.dp))
        val list = sessions
        when {
            list == null -> KewuLoading()
            list.isEmpty() -> KewuEmpty("暂无签到记录")
            else -> list.forEach { s ->
                Card(
                    onClick = { haptic(HapticKind.Tick); onOpenSession(s.id) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
                    cornerRadius = 16.dp,
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    colors = kewuCardColors(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.title, color = cs.onSurface, fontSize = 14.5.sp,
                                fontWeight = FontWeight.Medium, maxLines = 1)
                            Spacer(Modifier.height(2.dp))
                            Text("${s.mode} · ${s.createdAt.ifBlank { "—" }}", color = cs.onSurfaceSecondary,
                                fontSize = 12.sp, maxLines = 1)
                        }
                        KewuTag(
                            if (s.state == "closed") "已结束" else "进行中",
                            if (s.state == "closed") cs.secondaryContainer else KewuGreen.copy(alpha = 0.16f),
                            if (s.state == "closed") cs.onSurfaceSecondary else KewuGreen,
                        )
                    }
                }
            }
        }
    }
}
