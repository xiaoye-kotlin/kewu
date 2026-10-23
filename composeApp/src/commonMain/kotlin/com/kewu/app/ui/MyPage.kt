package com.kewu.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.kewu.app.BackHandler
import com.kewu.app.CourseInfo
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuGreen
import com.kewu.app.ObsHealth
import com.kewu.app.RecordingFuseItem
import com.kewu.app.RecordingFuseResult
import com.kewu.app.RecordingManager
import com.kewu.app.RecordingRecord
import com.kewu.app.haptic
import com.kewu.app.isAudioPermissionGranted
import com.kewu.app.isCameraPermissionGranted
import com.kewu.app.isNotificationPermissionGranted
import com.kewu.app.kvGet
import com.kewu.app.kvSet
import com.kewu.app.openAppSettings
import com.kewu.app.platformActivity
import com.kewu.app.platformDeviceId
import com.kewu.app.requestAudioPermission
import com.kewu.app.requestCameraPermission
import com.kewu.app.requestNotificationPermission
import com.kewu.app.shareText
import com.kewu.app.todayCn
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.AddCircle
import io.github.suqi8.coui.kmp.icon.extended.Alarm
import io.github.suqi8.coui.kmp.icon.extended.Back
import io.github.suqi8.coui.kmp.icon.extended.Community
import io.github.suqi8.coui.kmp.icon.extended.Edit
import io.github.suqi8.coui.kmp.icon.extended.Info
import io.github.suqi8.coui.kmp.icon.extended.More
import io.github.suqi8.coui.kmp.icon.extended.Notes
import io.github.suqi8.coui.kmp.icon.extended.RecordingTape
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.suqi8.coui.kmp.icon.extended.Scan
import io.github.suqi8.coui.kmp.icon.extended.Weeks
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

// ───────────────────────── 我的页（tab 3） ─────────────────────────

/**
 * 「我的」页：顶部个人资料（头像 + 名字 + **学号** + 学校/班级），下方校园功能分组，最底退出登录。
 * 2026-10-23 全局 coui 化：功能行 = coui `ArrowPreference`（同组放进一张 [KewuCardGroup]），
 * 分组标题 = coui `SmallTitle`，颜色读 [COUITheme]；资料头 / 退出登录 / 课程卡仍是液态玻璃。
 *
 * 子界面（编辑资料 / 录音管理 / …）用与课表页一致的「仅 slide、无 fade」共享轴切换 —— 避免给玻璃子树
 * 套 alpha 离屏层导致 coui 采样首帧 SIGSEGV。
 *
 * @param onLogout 「退出登录」→ 回启动屏（状态提升到 [App]，跨 tab 不丢）
 * @param onNavHide 滚动方向回调（向下收起底部导航栏）
 */
@Composable
fun MyPage(onLogout: () -> Unit, onNavHide: (Boolean) -> Unit) {
    var profile by remember { mutableStateOf(defaultProfile) }
    // 0=主页 1=编辑资料 2=录音管理 3=课程绑定 4=加入班级 6=通知设置 7=关于我们
    // （原 4/5 教师工具的发起签到/发布笔记 —— 学生端已移除，2026-10-18）
    var screen by remember { mutableStateOf(0) }

    val pageBackdrop = rememberGlassBackdrop()
    val scrollState = rememberScrollState()

    // ★ 接入真实资料：拉取 /user/me，映射到 UserProfile。
    // 后端 UserInfo 仅含 id/username/nickname，无 学号/学校/班级/手机/邮箱，
    // 这些字段用默认值补；学号 = 登录账号(username)。失败则保留默认资料（离线也可用）。
    LaunchedEffect(Unit) {
        if (KewuApi.currentUser == null) KewuApi.me()
        val u = KewuApi.currentUser
        val uname = KewuApi.username ?: u?.user?.id ?: ""
        profile = defaultProfile.copy(
            name = (u?.user?.nickname ?: uname).ifBlank { defaultProfile.name },
            studentId = uname.ifBlank { defaultProfile.studentId },
        )
    }

    // ★ 内部子页返回：screen != 0 时（编辑资料 / 录音管理 / 课程绑定 / 通知设置 / 关于我们）
    // 按返回键回到主页（screen=0），而不是直接 finish Activity 退出软件。
    BackHandler(enabled = screen != 0) { screen = 0 }

    Box(Modifier.fillMaxSize()) {
        // 背景层：coui 页面灰底，是玻璃的采样源（采集层与玻璃平级 ✓）
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(pageBackdrop)
                .background(COUITheme.colorScheme.surface),
        )
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                if (targetState > initialState) {
                    slideInHorizontally(tween<IntOffset>(durationMillis = 320, easing = FastOutSlowInEasing)) { w -> w } togetherWith
                        slideOutHorizontally(tween<IntOffset>(durationMillis = 320, easing = FastOutSlowInEasing)) { w -> -w / 5 }
                } else {
                    slideInHorizontally(tween<IntOffset>(durationMillis = 320, easing = FastOutSlowInEasing)) { w -> -w / 5 } togetherWith
                        slideOutHorizontally(tween<IntOffset>(durationMillis = 320, easing = FastOutSlowInEasing)) { w -> w }
                }
            },
            modifier = Modifier.fillMaxSize(),
            label = "myPageNav",
        ) { s ->
            when (s) {
                0 -> MyHome(profile, pageBackdrop, scrollState, onNavHide,
                    onEdit = { screen = 1 },
                    onRecording = { screen = 2 },
                    onBindCourse = { screen = 3 },
                    onJoinClass = { screen = 4 },
                    onNotification = { screen = 6 },
                    onAbout = { screen = 7 },
                    onLogout = onLogout,
                )
                1 -> EditProfilePage(
                    profile, pageBackdrop,
                    onBack = { screen = 0 },
                    onSave = { profile = it; screen = 0 },
                )
                2 -> RecordingManagePage(pageBackdrop, onBack = { screen = 0 })
                3 -> BindCourseScreen(pageBackdrop, onBack = { screen = 0 })
                4 -> JoinClassScreen(pageBackdrop, onBack = { screen = 0 })
                6 -> NotificationSettingsPage(pageBackdrop, onBack = { screen = 0 })
                7 -> AboutPage(pageBackdrop, onBack = { screen = 0 })
            }
        }
    }
}

// ───────────────────────── 主页：资料头 + 功能列表 ─────────────────────────

/**
 * 仿 MIUI / coui 的错峰进场：元素从下方上浮归位，按 [index] 级联。
 * - 玻璃卡（[glass]=true）：只做 placement 位移，**不**叠 alpha/graphicsLayer（coui 采样者被包裹
 *   有首帧 SIGSEGV 风险）；
 * - 普通元素：位移 + alpha 淡入，观感是「内容逐层落位」。
 * 首次组合触发（切到「我的」tab 时 MyHome 重新挂载即播放）。
 */
@Composable
private fun Modifier.myEnter(index: Int, glass: Boolean = false): Modifier {
    var target by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) { delay((index * 55L).coerceAtMost(520L)); target = 1f }
    val p by animateFloatAsState(
        // 低阻尼弹簧 → 落位时轻微过冲再回弹（用户要求的「有回弹」手感）
        targetValue = target,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 380f),
        label = "myEnter$index",
    )
    return if (glass) {
        // 玻璃卡（coui 采样者）：只做 placement 位移，禁叠 scale/alpha（首帧 SIGSEGV 风险）
        this.offset { IntOffset(0, ((1f - p) * 26f).dp.roundToPx()) }
    } else {
        // 普通元素：位移 + 缩放弹入（0.94→1）+ 淡入 —— "有回弹 + 缩放出现"（与任务页一致的 Mac 手感）
        this.offset { IntOffset(0, ((1f - p) * 26f).dp.roundToPx()) }
            .scale(lerp(0.94f, 1f, p.coerceIn(0f, 1f)))
            .alpha(p.coerceIn(0f, 1f))
    }
}

@Composable
private fun MyHome(
    profile: UserProfile,
    backdrop: LayerBackdrop,
    scrollState: androidx.compose.foundation.ScrollState,
    onNavHide: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRecording: () -> Unit,
    onBindCourse: () -> Unit,
    onJoinClass: () -> Unit,
    onNotification: () -> Unit,
    onAbout: () -> Unit,
    onLogout: () -> Unit,
) {
    val cs = COUITheme.colorScheme
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

    // 「我的课程」状态提升到本层：退出课程的底部弹层要渲染在滚动区之外的根层，
    // 课程移除也要在这里改列表。
    var myCourses by remember { mutableStateOf<List<CourseInfo>?>(null) }
    var coursesLoading by remember { mutableStateOf(true) }
    var exitTarget by remember { mutableStateOf<CourseInfo?>(null) }
    // 退出登录二次确认（coui OverlayDialog）：误触一下就丢登录态太伤
    var confirmLogout by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        myCourses = KewuApi.getCourses()
        coursesLoading = false
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 96.dp),
        ) {
        Spacer(Modifier.height(12.dp))

        // 资料头：液态玻璃大卡，头像 + 名字 + 学号 + 学校/班级 + 编辑入口
        KewuGlassCard(
            backdrop = backdrop,
            shape = RoundedCornerShape(20.dp),
            blurRadius = 22.dp,
            tintAlpha = 0.30f,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp)
                .myEnter(0, glass = true),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 大头像：初始字 + 主色浅底
                Box(
                    Modifier
                        .size(62.dp)
                        .clip(CircleShape)
                        .background(cs.tertiaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(profile.name.first().toString(), color = cs.onTertiaryContainer,
                        fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile.name, color = cs.onSurface, fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    // ★ 学号：用胶囊标签强提示（必显字段，文档明确要求）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KewuTag("学号", cs.secondaryContainer, cs.onSecondaryVariant)
                        Spacer(Modifier.width(7.dp))
                        Text(profile.studentId, color = cs.onSurface, fontSize = 14.sp,
                            fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp, maxLines = 1)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(profile.school, color = cs.onSurfaceSecondary, fontSize = 13.sp, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    Text(profile.className, color = cs.onSurfaceSecondary, fontSize = 13.sp, maxLines = 1)
                }
                // 编辑入口：coui 图标按钮（铅笔，轻提示「资料可改」）
                IconButton(
                    onClick = { haptic(HapticKind.Tick); onEdit() },
                    backgroundColor = cs.secondaryContainer,
                    cornerRadius = 17.dp,
                    minWidth = 34.dp,
                    minHeight = 34.dp,
                ) {
                    Icon(COUIIcons.Edit, contentDescription = "编辑资料", tint = cs.onSurfaceSecondary,
                        modifier = Modifier.size(18.dp))
                }
            }
        }

        // 我的课程列表
        GroupLabel("我的课程", Modifier.myEnter(1))
        MyCoursesList(myCourses, coursesLoading, backdrop, Modifier.myEnter(2)) { exitTarget = it }

        // 校园功能（学生端：教师工具已移除，2026-10-18）—— 五行并成一张 coui 分组卡
        GroupLabel("校园功能", Modifier.myEnter(3))
        KewuCardGroup(Modifier.myEnter(4)) {
            FuncRow(0, "课程绑定", "绑定课表，按时自动录音", backdrop, onClick = onBindCourse)
            FuncRow(5, "加入班级", "输入老师发的 6 位邀请码加入班级", backdrop, onClick = onJoinClass)
            FuncRow(1, "录音管理", "本地录音、手动重传与多端择优融合", backdrop, onClick = onRecording)
        }
        KewuCardGroup(Modifier.myEnter(5)) {
            FuncRow(2, "通知设置", "签到提醒、纪要推送与权限管理", backdrop, onClick = onNotification)
            FuncRow(3, "关于我们", "课悟 · 基于华为云的多端协同 AI 课堂", backdrop, onClick = onAbout)
        }

        Spacer(Modifier.height(2.dp))
        GroupLabel("账号", Modifier.myEnter(8))
        // 退出登录：玻璃按钮 + 错误色文字（不造背景色，用 error 语义）；点了先弹确认
        KewuGlassCard(
            backdrop = backdrop,
            shape = RoundedCornerShape(16.dp),
            blurRadius = 18.dp,
            tintAlpha = 0.26f,
            onClick = { haptic(HapticKind.Tick); confirmLogout = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .myEnter(9, glass = true),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 15.dp, horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 「门 + 出门箭头」coui-icons 里没有对应图标，保留自绘字形
                ProfileGlyph(4, cs.error, 22.dp)
                Spacer(Modifier.width(14.dp))
                Text("退出登录", color = cs.error, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(24.dp))
        }

        // ── 退出课程：coui 底部弹层（仅一个「退出课程」确认钮），渲染在滚动列之外的根层 ──
        val exitCourse = exitTarget
        if (exitCourse != null) {
            KewuBottomSheet(
                show = true,
                onDismiss = { exitTarget = null },
                title = "退出课程",
            ) { close ->
                Text(
                    exitCourse.name,
                    color = cs.onSurfaceSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
                SheetConfirmButton(
                    text = "退出课程",
                    danger = true,
                ) {
                    // 后端暂无退出课程接口（KewuApi 无 leaveCourse）：本地移出列表，
                    // 接口后补后在这里调 KewuApi.leaveCourse(courseId)
                    myCourses = myCourses?.filter { it.id != exitCourse.id }
                    close()
                }
            }
        }

        KewuConfirmDialog(
            show = confirmLogout,
            title = "退出登录",
            summary = "退出后需要重新登录才能上课录音、签到和提交作业",
            confirmText = "退出登录",
            onConfirm = { confirmLogout = false; onLogout() },
            onDismiss = { confirmLogout = false },
        )
    }
}

/** 分组小标题（coui `SmallTitle`）。跨页共享（课堂页 / 教师端页也在用），签名保持不变。 */
@Composable
internal fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    KewuSectionTitle(text, modifier)
}

/**
 * 功能行（2026-10-23 换 coui `ArrowPreference`）：图标 + 标题 + 副标题 + 右侧箭头，
 * 按压反馈 / 箭头 / 字号全走 coui 原生。**行本身不带卡片**，同组几行放进同一张 [KewuCardGroup]。
 * [glyph] 沿用 [ProfileGlyph] 的编号，映射到语义最接近的 coui 图标；[backdrop] 保留仅为签名兼容。
 */
@Composable
internal fun FuncRow(
    glyph: Int,
    title: String,
    subtitle: String,
    @Suppress("UNUSED_PARAMETER") backdrop: LayerBackdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = COUITheme.colorScheme
    ArrowPreference(
        title = title,
        summary = subtitle,
        modifier = modifier,
        startAction = {
            Icon(funcIcon(glyph), contentDescription = null, tint = cs.onSurface,
                modifier = Modifier.padding(end = 6.dp).size(22.dp))
        },
        onClick = { haptic(HapticKind.Tick); onClick() },
    )
}

/** [ProfileGlyph] 编号 → coui 图标（0 课程 1 录音 2 通知 3 关于 5 班级 6 新建课程 7 签到 8 笔记）。 */
private fun funcIcon(glyph: Int) = when (glyph) {
    0 -> COUIIcons.Weeks
    1 -> COUIIcons.RecordingTape
    2 -> COUIIcons.Alarm
    3 -> COUIIcons.Info
    5 -> COUIIcons.Community
    6 -> COUIIcons.AddCircle
    7 -> COUIIcons.Scan
    8 -> COUIIcons.Notes
    else -> COUIIcons.Info
}

// ───────────────────────── 编辑资料页 ─────────────────────────

/**
 * 修改个人信息：头像 + 名字 + 学号 + 学校/班级 + 手机 + 邮箱，逐行可编辑（coui TextField）。
 * 顶栏 coui `SmallTopAppBar`（返回）+ 右侧 coui 小号「保存」按钮。保存后写回 [MyPage] 的状态。
 */
@Composable
private fun EditProfilePage(
    profile: UserProfile,
    @Suppress("UNUSED_PARAMETER") backdrop: LayerBackdrop,
    onBack: () -> Unit,
    onSave: (UserProfile) -> Unit,
) {
    val cs = COUITheme.colorScheme
    var name by remember { mutableStateOf(profile.name) }
    var studentId by remember { mutableStateOf(profile.studentId) }
    var school by remember { mutableStateOf(profile.school) }
    var className by remember { mutableStateOf(profile.className) }
    var phone by remember { mutableStateOf(profile.phone) }
    var email by remember { mutableStateOf(profile.email) }
    val scrollState = rememberScrollState()

    Column(Modifier.fillMaxSize()) {
        KewuTopBar("编辑资料", onBack = onBack, actions = {
            SmallButton("保存", primary = true, modifier = Modifier.padding(end = 12.dp)) {
                onSave(
                    profile.copy(
                        name = name.trim().ifEmpty { profile.name },
                        studentId = studentId.trim(),
                        school = school.trim(),
                        className = className.trim(),
                        phone = phone.trim(),
                        email = email.trim(),
                    ),
                )
            }
        })

        Column(
            Modifier.fillMaxWidth().verticalScroll(scrollState).padding(bottom = 40.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            // 头像（编辑页只读预览，真实可换图留接口）
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(cs.tertiaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(name.firstOrNull()?.toString() ?: "我", color = cs.onTertiaryContainer,
                        fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(16.dp))
            EditField("姓名", name) { name = it }
            // ★ 学号单独成行并强提示，且输入为数字
            EditField("学号", studentId, keyboardNum = true) { studentId = it }
            EditField("学校", school) { school = it }
            EditField("班级", className) { className = it }
            EditField("手机号", phone, keyboardType = KeyboardType.Phone) { phone = it }
            EditField("邮箱", email, keyboardType = KeyboardType.Email) { email = it }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 单行编辑字段：分组小标题 + coui 圆角矩形输入框（自带清除按钮）。 */
@Composable
private fun EditField(
    label: String,
    value: String,
    keyboardNum: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        KewuSectionTitle(label)
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            backgroundMode = TextFieldMode.Rectangle,
            label = label,
            singleLine = true,
            showClearButton = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (keyboardNum) KeyboardType.Number else keyboardType,
            ),
        )
    }
}

/** coui 小号按钮（行内 / 顶栏用）：[primary]=主色实心，[danger]=错误色实心，否则灰底。 */
@Composable
private fun SmallButton(
    text: String,
    primary: Boolean = false,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    TextButton(
        text = text,
        onClick = { haptic(HapticKind.Light); onClick() },
        modifier = modifier,
        enabled = enabled,
        minWidth = ButtonDefaults.MinWidthSmall,
        minHeight = ButtonDefaults.MinHeightSmall,
        cornerRadius = ButtonDefaults.CornerRadiusSmall,
        insideMargin = ButtonDefaults.InsideMarginSmall,
        colors = when {
            danger -> ButtonDefaults.textButtonColorsPrimary(color = cs.error, disabledColor = cs.error.copy(alpha = 0.3f))
            primary -> ButtonDefaults.textButtonColorsPrimary()
            else -> ButtonDefaults.textButtonColors()
        },
        textStyle = COUITheme.textStyles.button.copy(fontSize = 13.sp),
    )
}

// ───────────────────────── 录音管理页（2026-10-23 接真数据） ─────────────────────────

/**
 * 录音管理（文档 9.4 / 10.3 / 10.4）：数据来自 [RecordingManager.history]（本地 kv 持久化的上传记录）。
 * - 顶部玻璃概览：总数 / 待重传 / 已上传；
 * - 按协同会话 sessionId 分组（同课同日教师端 + 学生端落同一会话），组内逐条展示
 *   课程 / 时间 / 时长 / 大小 / 端角色 / 质量分 / 信噪比 / 电平 / 上传状态；
 * - 失败项「重传」→ [RecordingManager.retryRecord]；已上传项「分享链接」；任意项可删除本地记录；
 * - 组内已上传 ≥1 条可「择优融合」→ 8081 `/api/recordings/fuse`，结果卡标出主音源。
 */
@Composable
private fun RecordingManagePage(backdrop: LayerBackdrop, onBack: () -> Unit) {
    val cs = COUITheme.colorScheme
    val history by RecordingManager.history.collectAsState()
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val snackbar = LocalKewuSnackbar.current

    // classId → 课程名：先用 /user/me 带回的课程，再用 /api/courses 补全（录音的 classId 即课程 id）
    var courseNames by remember {
        mutableStateOf(KewuApi.currentUser?.courses.orEmpty().associate { it.id to it.name })
    }
    LaunchedEffect(Unit) {
        KewuApi.getCourses()?.let { list -> courseNames = courseNames + list.associate { it.id to it.name } }
    }
    fun courseName(classId: String): String =
        courseNames[classId]?.takeIf { it.isNotBlank() }
            ?: if (classId.isBlank() || classId == "unknown") "未绑定课程" else "课程 $classId"

    // 重传中的 localId / 融合中的 sessionId / 各会话最近一次融合结果
    var retrying by remember { mutableStateOf(setOf<String>()) }
    var fusing by remember { mutableStateOf(setOf<String>()) }
    var fuseResults by remember { mutableStateOf(mapOf<String, RecordingFuseResult>()) }
    var deleteTarget by remember { mutableStateOf<RecordingRecord?>(null) }

    fun retry(r: RecordingRecord) {
        retrying = retrying + r.localId
        RecordingManager.retryRecord(r) { err ->
            // 回调在录音单例的后台线程：切回 UI 作用域再改状态 / 发提示
            scope.launch {
                retrying = retrying - r.localId
                snackbar.toast(err ?: "重传成功")
            }
        }
    }

    fun fuse(sessionId: String, records: List<RecordingRecord>) {
        val items = records.filter { it.uploaded && it.recordingId.isNotBlank() }.map { r ->
            RecordingFuseItem(
                recordingId = r.recordingId, classId = r.classId, sessionId = r.sessionId,
                deviceId = platformDeviceId(), format = "wav", sampleRate = 44100,
                durationSec = r.durationSec.toDouble(), sizeBytes = r.sizeBytes,
                storedUrl = r.url.ifBlank { null }, role = r.role.ifBlank { null },
                avgLevelDb = r.avgLevelDb, snrDb = r.snrDb,
            )
        }
        if (items.isEmpty()) return
        fusing = fusing + sessionId
        scope.launch {
            val res = KewuApi.fuseRecordings(sessionId, items)
            fusing = fusing - sessionId
            if (res != null) fuseResults = fuseResults + (sessionId to res)
            snackbar.toast(if (res != null) "已选出主音源" else "融合失败，请稍后重试")
        }
    }

    Column(Modifier.fillMaxSize()) {
        KewuTopBar("录音管理", onBack = onBack)

        Column(
            Modifier.fillMaxWidth().verticalScroll(scrollState).padding(bottom = 40.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            // 概览玻璃头：总数 / 待重传 / 已上传
            val failed = history.count { !it.uploaded }
            val uploaded = history.size - failed
            KewuGlassCard(
                backdrop = backdrop,
                shape = RoundedCornerShape(16.dp),
                blurRadius = 20.dp,
                tintAlpha = 0.30f,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    OverviewCell("本地录音", "${history.size} 份", cs.onSurface, Modifier.weight(1f), Alignment.Start)
                    OverviewCell("待重传", "$failed 份", if (failed > 0) cs.error else KewuGreen,
                        Modifier.weight(1f), Alignment.CenterHorizontally)
                    OverviewCell("已上传", "$uploaded 份", cs.primary, Modifier.weight(1f), Alignment.End)
                }
            }

            if (history.isEmpty()) {
                KewuEmpty("还没有录音记录\n在课堂页开始录音，结束后会自动上传并出现在这里")
            } else {
                // 按协同会话分组（history 新的在前，groupBy 保持首次出现顺序 → 最近的会话在上）
                history.groupBy { it.sessionId }.forEach { (sid, records) ->
                    val first = records.first()
                    KewuSectionTitle("${courseName(first.classId)} · ${fmtDay(first.at)}")
                    val result = fuseResults[sid]
                    records.forEach { r ->
                        RecordingRow(
                            item = r,
                            courseName = courseName(r.classId),
                            retrying = r.localId in retrying,
                            isPrimary = result != null && r.recordingId.isNotBlank() &&
                                r.recordingId == result.primaryRecordingId,
                            isAbnormal = result != null && r.recordingId in result.abnormalIds,
                            onRetry = { retry(r) },
                            onShare = { haptic(HapticKind.Tick); shareText(r.url) },
                            onDelete = { deleteTarget = r },
                        )
                    }
                    FuseCard(
                        sessionId = sid,
                        uploadedCount = records.count { it.uploaded && it.recordingId.isNotBlank() },
                        fusing = sid in fusing,
                        result = result,
                        records = records,
                        onFuse = { fuse(sid, records) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    KewuConfirmDialog(
        show = deleteTarget != null,
        title = "删除这条录音记录？",
        summary = "只移除本机记录与重传入口，已上传到云端的录音不会被删除",
        confirmText = "删除",
        onConfirm = {
            deleteTarget?.let { RecordingManager.removeRecord(it.localId) }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    )
}

@Composable
private fun OverviewCell(label: String, value: String, valueColor: Color, modifier: Modifier, align: Alignment.Horizontal) {
    val cs = COUITheme.colorScheme
    Column(modifier, horizontalAlignment = align) {
        Text(label, color = cs.onSurfaceSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = valueColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

/** 单条录音（coui Card）：课程 + 端角色 + 状态胶囊 / 时间·时长·大小 / 质量指标 / 操作按钮。 */
@Composable
private fun RecordingRow(
    item: RecordingRecord,
    courseName: String,
    retrying: Boolean,
    isPrimary: Boolean,
    isAbnormal: Boolean,
    onRetry: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(16.dp),
        colors = kewuCardColors(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(courseName, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            val teacher = item.role == "teacher"
            KewuTag(if (teacher) "教师端" else "学生端",
                if (teacher) cs.tertiaryContainer else cs.secondaryContainer,
                if (teacher) cs.onTertiaryContainer else cs.onSecondaryVariant)
            if (isPrimary) {
                Spacer(Modifier.width(6.dp))
                KewuTag("主音源", KewuGreen.copy(alpha = 0.16f), KewuGreen)
            }
            if (isAbnormal) {
                Spacer(Modifier.width(6.dp))
                KewuTag("疑似中断", cs.errorContainer, cs.onErrorContainer)
            }
            Spacer(Modifier.weight(1f))
            val (label, bg, fg) = when {
                retrying -> Triple("上传中", cs.secondaryContainer, cs.onSecondaryVariant)
                item.uploaded -> Triple("已上传", cs.tertiaryContainer, cs.onTertiaryContainer)
                else -> Triple("上传失败", cs.errorContainer, cs.onErrorContainer)
            }
            KewuTag(label, bg, fg)
        }
        Spacer(Modifier.height(4.dp))
        Text("${fmtDay(item.at)} ${fmtClock(item.at)} · ${fmtDuration(item.durationSec)} · ${fmtSize(item.sizeBytes)}",
            color = cs.onSurfaceSecondary, fontSize = 12.5.sp, maxLines = 1)
        // 质量指标：只有上传成功才有；信噪比 / 电平服务端仅对 WAV/PCM 算得出，有值才显示
        if (item.uploaded) {
            val metrics = buildList {
                add("质量分 ${fmt2(item.score)}")
                item.snrDb?.let { add("信噪比 ${fmt1(it)} dB") }
                item.avgLevelDb?.let { add("电平 ${fmt1(it)} dBFS") }
            }
            Spacer(Modifier.height(2.dp))
            Text(metrics.joinToString(" · "), color = cs.onSurfaceVariantSummary, fontSize = 12.sp, maxLines = 1)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            SmallButton("删除", onClick = onDelete, enabled = !retrying)
            if (item.uploaded && item.url.isNotBlank()) SmallButton("分享链接", onClick = onShare)
            if (!item.uploaded) {
                if (retrying) InfiniteProgressIndicator(size = 20.dp)
                else SmallButton("重传", danger = true, onClick = onRetry)
            }
        }
    }
}

/** 会话融合卡：「择优融合」按钮 + 最近一次结果（策略 / 主音源 / 选中端 / 是否回退 / 异常条目）。 */
@Composable
private fun FuseCard(
    sessionId: String,
    uploadedCount: Int,
    fusing: Boolean,
    result: RecordingFuseResult?,
    records: List<RecordingRecord>,
    onFuse: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    KewuCardGroup {
        BasicComponent(
            title = "多端择优融合",
            summary = if (uploadedCount == 0) "本会话还没有已上传的录音"
                else "会话 $sessionId · $uploadedCount 条已上传，教师端优先、异常回退学生端",
            endActions = {
                if (fusing) InfiniteProgressIndicator(size = 20.dp)
                else SmallButton(if (result == null) "择优融合" else "重新融合", primary = true,
                    enabled = uploadedCount > 0, onClick = onFuse)
            },
        )
        if (result != null) {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            // 主音源：在本组记录里定位是哪一条（课程端角色 + 时间），找不到就显示原始 ID
            val primary = records.firstOrNull { it.recordingId == result.primaryRecordingId }
            val primaryDesc = primary?.let {
                "${if (it.role == "teacher") "教师端" else "学生端"} · ${fmtClock(it.at)} · ${fmtDuration(it.durationSec)}"
            } ?: result.primaryRecordingId.ifBlank { "无" }
            FuseLine("策略", result.strategy.ifBlank { "—" })
            FuseLine("主音源", primaryDesc)
            FuseLine("选中端", when (result.selectedRole) {
                "teacher" -> "教师端"; "student" -> "学生端"; else -> result.selectedRole.ifBlank { "—" }
            })
            FuseLine("回退", if (result.fallback) "是（教师端录音异常，已回退学生端）" else "否",
                if (result.fallback) cs.error else cs.onSurface)
            if (result.abnormalIds.isNotEmpty()) {
                FuseLine("异常录音", "${result.abnormalIds.size} 条（时长或大小不到全场最大的一半）", cs.error)
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun FuseLine(label: String, value: String, valueColor: Color = COUITheme.colorScheme.onSurface) {
    val cs = COUITheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
        Text(label, color = cs.onSurfaceSecondary, fontSize = 13.sp, modifier = Modifier.width(72.dp), maxLines = 1)
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// ── 录音展示用格式化（commonMain 无 String.format / kotlinx-datetime，手写；时间按东八区） ──

private fun two(v: Long) = if (v < 10) "0$v" else "$v"

/** 「今天」/「MM-dd」。 */
private fun fmtDay(at: Long): String {
    val d = todayCn(at)
    return if (d == todayCn()) "今天" else d.substring(5)
}

/** 东八区 `HH:mm`。 */
private fun fmtClock(at: Long): String {
    val minOfDay = ((at + 8 * 3_600_000L).mod(86_400_000L)) / 60_000L
    return "${two(minOfDay / 60)}:${two(minOfDay % 60)}"
}

private fun fmtDuration(sec: Int): String {
    val s = sec.coerceAtLeast(0).toLong()
    return if (s >= 3600) "${s / 3600}:${two(s % 3600 / 60)}:${two(s % 60)}" else "${two(s / 60)}:${two(s % 60)}"
}

private fun fmtSize(bytes: Long): String =
    if (bytes < 1_048_576L) "${(bytes + 1023) / 1024} KB"
    else (bytes * 10 / 1_048_576L).let { "${it / 10}.${it % 10} MB" }

private fun fmt1(v: Double): String {
    val t = (v * 10).roundToLong()
    val sign = if (t < 0) "-" else ""
    val a = kotlin.math.abs(t)
    return "$sign${a / 10}.${a % 10}"
}

private fun fmt2(v: Double): String {
    val t = (v * 100).roundToLong().coerceAtLeast(0)
    return "${t / 100}.${two(t % 100)}"
}

// ───────────────────────── 小图标：返回 ─────────────────────────

/** 返回图标（2026-10-23 改用 coui-icons `Back`）。签名保留供旧调用点使用。 */
@Composable
internal fun BackGlyph(color: Color, sizeDp: Dp) {
    Icon(COUIIcons.Back, contentDescription = null, tint = color, modifier = Modifier.size(sizeDp))
}

// ───────────────────────── 通知设置页（coui 组件，2026-10-18 新增） ─────────────────────────

/**
 * 通知设置：提醒开关（签到 / 作业截止 / 纪要生成，kv 本地持久化）+ 权限管理
 * （通知、麦克风、相机：查看状态、一键申请、去系统设置）。
 * 开关 = coui `SwitchPreference`，权限行 = coui `BasicComponent` + 小号按钮，同组放一张分组卡。
 */
@Composable
internal fun NotificationSettingsPage(backdrop: LayerBackdrop, onBack: () -> Unit) {
    val cs = COUITheme.colorScheme
    val scrollState = rememberScrollState()

    // 权限状态：进入页面读一次，申请回调里更新
    var notifGranted by remember { mutableStateOf(isNotificationPermissionGranted()) }
    var audioGranted by remember { mutableStateOf(isAudioPermissionGranted()) }
    var cameraGranted by remember { mutableStateOf(isCameraPermissionGranted()) }

    // 提醒开关（kv 持久化："off" = 关，缺省 = 开）
    var remindCheckin by remember { mutableStateOf(kvGet("ntf_checkin") != "off") }
    var remindHomework by remember { mutableStateOf(kvGet("ntf_homework") != "off") }
    var remindSummary by remember { mutableStateOf(kvGet("ntf_summary") != "off") }

    fun requestPerm(kind: String) {
        val act = platformActivity() ?: return
        when (kind) {
            "notif" -> requestNotificationPermission(act) { granted -> notifGranted = granted }
            "audio" -> requestAudioPermission(act) { granted -> audioGranted = granted }
            "camera" -> requestCameraPermission(act) { granted -> cameraGranted = granted }
        }
    }

    Column(Modifier.fillMaxSize()) {
        KewuTopBar("通知设置", onBack = onBack)

        Column(Modifier.fillMaxWidth().verticalScroll(scrollState).padding(bottom = 40.dp)) {
            Spacer(Modifier.height(8.dp))
            // 说明玻璃卡
            KewuGlassCard(
                backdrop = backdrop,
                shape = RoundedCornerShape(16.dp),
                blurRadius = 20.dp,
                tintAlpha = 0.28f,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(COUIIcons.Alarm, contentDescription = null, tint = cs.primary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "开启后，签到开始、作业截止、纪要生成会第一时间提醒你",
                        color = cs.onSurfaceSecondary, fontSize = 13.sp, lineHeight = 19.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            GroupLabel("提醒开关")
            KewuCardGroup {
                SwitchRow("签到提醒", "老师发起签到时立即通知", remindCheckin) {
                    remindCheckin = it; kvSet("ntf_checkin", if (it) null else "off")
                }
                SwitchRow("作业截止提醒", "截止前 2 小时提醒未交作业", remindHomework) {
                    remindHomework = it; kvSet("ntf_homework", if (it) null else "off")
                }
                SwitchRow("课堂纪要提醒", "AI 纪要生成后推送", remindSummary) {
                    remindSummary = it; kvSet("ntf_summary", if (it) null else "off")
                }
            }

            GroupLabel("权限管理")
            KewuCardGroup {
                PermissionRow(
                    "通知", "接收签到、作业、纪要推送", notifGranted,
                    onAction = { if (notifGranted) openAppSettings() else requestPerm("notif") },
                )
                PermissionRow(
                    "麦克风", "课堂录音需要", audioGranted,
                    onAction = { if (audioGranted) openAppSettings() else requestPerm("audio") },
                )
                PermissionRow(
                    "相机", "扫码签到需要", cameraGranted,
                    onAction = { if (cameraGranted) openAppSettings() else requestPerm("camera") },
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 提醒开关行（coui `SwitchPreference`，整行可点切换）。 */
@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SwitchPreference(
        checked = checked,
        onCheckedChange = { onChange(it); haptic(HapticKind.Tick) },
        title = title,
        summary = subtitle,
    )
}

/** 权限管理行：名称 + 用途 + 状态 + coui 小号按钮（未授予=申请，已授予=去设置）。 */
@Composable
private fun PermissionRow(title: String, subtitle: String, granted: Boolean, onAction: () -> Unit) {
    val cs = COUITheme.colorScheme
    BasicComponent(
        title = title,
        summary = subtitle,
        endActions = {
            Text(
                if (granted) "已开启" else "未开启",
                color = if (granted) KewuGreen else cs.error,
                fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            Spacer(Modifier.width(10.dp))
            SmallButton(if (granted) "去设置" else "申请", primary = !granted,
                modifier = Modifier.align(Alignment.CenterVertically), onClick = onAction)
        },
    )
}

// ───────────────────────── 关于我们页（2026-10-18 新增） ─────────────────────────

/**
 * 关于我们：品牌头 + 项目介绍 + 服务状态（10.2 / 10.5 健康检查）+ 团队信息
 * （作者：第一队 · 重庆对外经贸学院）。
 */
@Composable
internal fun AboutPage(backdrop: LayerBackdrop, onBack: () -> Unit) {
    val cs = COUITheme.colorScheme
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    // ── 服务状态：8080 业务 / 8081 录音存储 / 8081 背后的 Redis 课堂缓存 ──
    // checking=true 期间各行显示转圈；obs==null 且已检测 = 8081 连不上（Redis 状态随之未知）
    var checking by remember { mutableStateOf(true) }
    var bizUp by remember { mutableStateOf(false) }
    var obs by remember { mutableStateOf<ObsHealth?>(null) }
    var probe by remember { mutableStateOf<Job?>(null) }
    fun refresh() {
        if (probe?.isActive == true) return // 防连点：上一轮没测完不重复发
        checking = true
        probe = scope.launch {
            // 两个服务并行探测，总耗时取较慢的一个
            val b = async { KewuApi.isBusinessUp() }
            val o = async { KewuApi.obsHealthInfo() }
            bizUp = b.await()
            obs = o.await()
            checking = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize()) {
        KewuTopBar("关于我们", onBack = onBack)

        Column(
            Modifier.fillMaxWidth().verticalScroll(scrollState).padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(10.dp))
            // 品牌头：玻璃卡 + 课字标
            KewuGlassCard(
                backdrop = backdrop,
                shape = RoundedCornerShape(24.dp),
                blurRadius = 22.dp,
                tintAlpha = 0.30f,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(68.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(cs.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("课", color = cs.onPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("课悟 · Kewu", color = cs.onSurface, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("高校课堂的多端 AI 助手", color = cs.onSurfaceSecondary, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    // 与 composeApp/build.gradle.kts 的 versionName 保持一致
                    KewuTag("v1.0.0", KewuGreen.copy(alpha = 0.16f), KewuGreen)
                }
            }
            Spacer(Modifier.height(14.dp))

            // 项目简介（coui Card）
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                cornerRadius = 16.dp,
                insideMargin = PaddingValues(16.dp),
                colors = kewuCardColors(),
            ) {
                Text("项目介绍", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "课悟把课堂录音、扫码签到、作业批阅和 AI 助教小悟装进一个 App：\n" +
                        "· 上课自动录音，课后生成 AI 纪要\n" +
                        "· 老师发起签到，学生扫码/暗号一键完成\n" +
                        "· 作业在线提交，AI 预批阅即时反馈\n" +
                        "· 小悟 AI 助教，随时追问课堂没听懂的地方",
                    color = cs.onSurfaceSecondary, fontSize = 13.sp, lineHeight = 21.sp,
                )
            }

            // 服务状态（10.2 健康检查 + 10.5 Redis 降级）
            GroupLabel("服务状态", Modifier.fillMaxWidth())
            KewuCardGroup {
                val obsUp = obs?.status.equals("up", ignoreCase = true) || obs?.status.equals("ok", ignoreCase = true)
                val redis = obs?.redis.orEmpty()
                ServiceRow("业务服务", "8080 · 课程、作业、签到等业务接口", checking,
                    if (bizUp) ServiceState.Up else ServiceState.Down)
                ServiceRow("录音存储服务", "8081 · 录音上传、质量评估与多端融合", checking,
                    when {
                        obs == null -> ServiceState.Down
                        obsUp -> ServiceState.Up
                        else -> ServiceState.Down
                    })
                ServiceRow(
                    "课堂缓存（Redis）",
                    if (redis.equals("down", ignoreCase = true)) "已降级：课堂纪要、协同会话暂时无法保存"
                        else "课堂纪要、协同会话与简易签到的暂存",
                    checking,
                    when {
                        obs == null -> ServiceState.Unknown
                        redis.equals("up", ignoreCase = true) -> ServiceState.Up
                        redis.equals("down", ignoreCase = true) -> ServiceState.Degraded
                        else -> ServiceState.Unknown
                    },
                )
                BasicComponent(
                    title = if (checking) "检测中…" else "重新检测",
                    titleColor = io.github.suqi8.coui.kmp.basic.BasicComponentDefaults.titleColor(color = cs.primary),
                    summary = obs?.time?.takeIf { it.isNotBlank() && !checking }?.let { "录音服务时间 $it" },
                    enabled = !checking,
                    onClick = { haptic(HapticKind.Tick); refresh() },
                    endActions = {
                        if (checking) InfiniteProgressIndicator(size = 20.dp, modifier = Modifier.align(Alignment.CenterVertically))
                        else Icon(COUIIcons.Refresh, contentDescription = "刷新", tint = cs.primary,
                            modifier = Modifier.size(20.dp).align(Alignment.CenterVertically))
                    },
                )
            }

            // 信息行
            KewuCardGroup {
                InfoRow("开发团队", "我是第一对不队 · 重庆对外经贸学院")
                InfoRow("技术栈", "Kotlin Multiplatform + Compose Multiplatform")
                InfoRow("云服务", "后端与录音存储部署于华为云")
                InfoRow("支持平台", "Android · Desktop · iOS")
            }

            Spacer(Modifier.height(6.dp))
            Text("让每一节课，都被记录、被理解", color = cs.onSurfaceSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text("© 2026 课悟团队", color = cs.onSurfaceVariantSummary, fontSize = 11.sp)
            Spacer(Modifier.height(12.dp))
        }
    }
}

private enum class ServiceState { Up, Down, Degraded, Unknown }

/** 服务状态行（coui `BasicComponent`）：检测中转圈，结束后显示状态胶囊。 */
@Composable
private fun ServiceRow(title: String, summary: String, checking: Boolean, state: ServiceState) {
    val cs = COUITheme.colorScheme
    BasicComponent(
        title = title,
        summary = summary,
        endActions = {
            Box(Modifier.align(Alignment.CenterVertically)) {
                if (checking) InfiniteProgressIndicator(size = 18.dp)
                else when (state) {
                    ServiceState.Up -> KewuTag("正常", KewuGreen.copy(alpha = 0.16f), KewuGreen)
                    ServiceState.Down -> KewuTag("不可用", cs.errorContainer, cs.onErrorContainer)
                    ServiceState.Degraded -> KewuTag("降级", cs.errorContainer, cs.onErrorContainer)
                    ServiceState.Unknown -> KewuTag("未知", cs.secondaryContainer, cs.onSecondaryVariant)
                }
            }
        },
    )
}

/** 关于页信息行（coui `BasicComponent`：标题 = 项，摘要 = 值）。 */
@Composable
private fun InfoRow(label: String, value: String) {
    BasicComponent(title = label, summary = value)
}

// ───────────────────────── 我的课程列表 ─────────────────────────

@Composable
private fun MyCoursesList(
    courses: List<CourseInfo>?,
    loading: Boolean,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    onRequestExit: (CourseInfo) -> Unit,
) {
    val cs = COUITheme.colorScheme

    if (loading) {
        Box(
            modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            InfiniteProgressIndicator()
        }
    } else if (courses.isNullOrEmpty()) {
        KewuGlassCard(
            backdrop = backdrop,
            shape = RoundedCornerShape(16.dp),
            blurRadius = 20.dp,
            tintAlpha = 0.25f,
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)
        ) {
            Box(
                Modifier.fillMaxWidth().padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("暂无课程", color = cs.onSurfaceSecondary, fontSize = 14.sp)
            }
        }
    } else {
        Column(modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            courses.take(3).forEachIndexed { i, course ->
                CourseCardCompact(course, backdrop, i, onRequestExit)
            }
            if (courses.size > 3) {
                Text(
                    "共 ${courses.size} 门课程",
                    color = cs.onSurfaceSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun CourseCardCompact(
    course: CourseInfo,
    backdrop: LayerBackdrop,
    index: Int,
    onRequestExit: (CourseInfo) -> Unit,
) {
    val cs = COUITheme.colorScheme
    // 错峰入场：从零上浮「长」出来（与全 app 动画语言统一）
    val p = rememberEnterProgress(index, baseDelayMs = 240L, stepMs = 60L)

    KewuGlassCard(
        backdrop = backdrop,
        shape = RoundedCornerShape(14.dp),
        blurRadius = glassCondenseBlur(p, 18f),
        tintAlpha = 0.25f * p,
        modifier = Modifier
            .fillMaxWidth()
            .glassEnter(p, 40f)
    ) {
        Row(
            Modifier.fillMaxWidth().alpha(p).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 课程图标
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(cs.tertiaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    course.name.take(2),
                    color = cs.onTertiaryContainer,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.width(12.dp))

            // 课程信息
            Column(Modifier.weight(1f)) {
                Text(
                    course.name,
                    color = cs.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    course.teacher.ifBlank { "未知教师" },
                    color = cs.onSurfaceSecondary,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }

            // 菜单按钮（⋯）：coui 图标按钮 → 弹出「退出课程」底部弹层
            IconButton(
                onClick = { haptic(HapticKind.Tick); onRequestExit(course) },
                backgroundColor = cs.secondaryContainer,
                cornerRadius = 16.dp,
                minWidth = 32.dp,
                minHeight = 32.dp,
            ) {
                Icon(COUIIcons.More, contentDescription = "更多", tint = cs.onSurfaceSecondary,
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}
