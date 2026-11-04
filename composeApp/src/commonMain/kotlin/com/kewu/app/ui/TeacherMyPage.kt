package com.kewu.app.ui

import com.kewu.app.LockTabSwipe
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.kewu.app.kvGet
import com.kewu.app.kvSet
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Edit
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.AiNav
import com.kewu.app.BackHandler
import com.kewu.app.HapticKind
import com.kewu.app.haptic
import com.kewu.app.KewuApi
import com.kewu.app.KewuBlue
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.theme.COUITheme

// ───────────────────────── 教师端 · 我的（tab 2） ─────────────────────────
//
// 结构对齐学生端 MyPage：资料头玻璃卡 + 分组功能行（coui ArrowPreference 分组卡）+ 退出登录（二次确认）；
// 子页路由用「仅 slide 无 fade」的 AnimatedContent + BackHandler（与学生端同款，防 coui SIGSEGV）。
// 功能分组：
//   教学管理：课程绑定（BindCourseScreen）/ 发起签到（CreateAttendanceScreen）/ 发布笔记（PostNoteScreen）
//   通用：通知设置 / 关于我们（复用 MyPage 已置 internal 的子页）

@Composable
fun TeacherMyPage(onLogout: () -> Unit, onNavHide: (Boolean) -> Unit = {}) {
    // 0=主页 1=课程绑定 2=发起签到 3=发布笔记 4=新建课程 5=班级管理 6=通知设置 7=关于我们 8=内存管理
    // 9=同学管理 10=编辑资料 11=录音管理（2026-11-02）
    var screen by remember { mutableStateOf(0) }
    LockTabSwipe(screen != 0)

    // ★ A2（2026-11-05）：进子页时展开底部导航栏（与学生端「我的」同款修复）
    LaunchedEffect(screen) {
        if (screen != 0) onNavHide(false)
    }

    // 2026-10-24：AI 助教代操作 —— 小悟可经 AiNav 直接打开教师「我的」的子页（发起签到/发布笔记/班级管理等）
    LaunchedEffect(AiNav.teacherMyScreen.value) {
        val req = AiNav.teacherMyScreen.value
        if (req != 0) {
            screen = req
            AiNav.teacherMyScreen.value = 0
        }
    }
    val pageBackdrop = rememberGlassBackdrop()
    val scrollState = rememberScrollState()

    val profileScope = rememberCoroutineScope()
    val snackbar = LocalKewuSnackbar.current
    // 资料头与学生端一致（2026-11-02）：工号 + 学校 + 所教班级；先显示本地缓存，再拉云端，失败不清空
    val classesKey = "kewu_tclasses_" + KewuApi.username.orEmpty()
    var profile by remember {
        KewuApi.primeFromCache()
        val uname = KewuApi.username.orEmpty()
        val stored = ProfileStore.load(uname) ?: defaultProfile
        val u = KewuApi.currentUser?.user
        mutableStateOf(stored.copy(
            name = u?.nickname ?: stored.name.ifBlank { KewuApi.displayName("") },
            studentId = uname.ifBlank { stored.studentId },
            school = u?.school?.ifBlank { null } ?: stored.school,
            className = kvGet(classesKey) ?: stored.className,
        ))
    }
    LaunchedEffect(screen == 0) {
        if (screen != 0) return@LaunchedEffect
        KewuApi.me(force = true)
        val classes = KewuApi.getTeacherClasses()?.classes?.map { it.name }?.filter { it.isNotBlank() }?.distinct()
        if (classes != null) kvSet(classesKey, classes.joinToString("、"))
        val u = KewuApi.currentUser?.user
        val uname = KewuApi.username.orEmpty()
        profile = profile.copy(
            name = u?.nickname?.ifBlank { null } ?: profile.name,
            studentId = uname.ifBlank { profile.studentId },
            school = u?.school?.ifBlank { null } ?: profile.school,
            className = classes?.joinToString("、") ?: profile.className,
            phone = u?.phone ?: profile.phone,
            email = u?.email ?: profile.email,
            subject = u?.subject ?: profile.subject,
            bio = u?.bio ?: profile.bio,
        )
        ProfileStore.save(uname, profile)
    }

    BackHandler(enabled = screen != 0) { screen = 0 }

    LaunchedEffect(scrollState) {
        // 2026-11-05（A2）：基线重建首帧不动作（与学生端同款修复），防子页返回时误判下滑藏导航。
        var last = -1
        snapshotFlow { scrollState.value }.collect { cur ->
            if (cur <= 0) onNavHide(false)
            else {
                val d = cur - last
                if (last >= 0 && d > 6) onNavHide(true) else if (d < -6) onNavHide(false)
            }
            last = cur
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(pageBackdrop)
                .background(COUITheme.colorScheme.surface),
        )
        // ★ 2026-10-26 Telegram 式推入 / 返回（同学生端「我的」）；右滑返回只开在非表单页
        //   （发起签到 / 发布笔记 / 新建课程是表单，一滑会丢输入）
        KewuSlideHost(
            targetState = screen,
            modifier = Modifier.fillMaxSize(),
            isForward = { a, b -> b > a },
            // 2026-11-04（P1）：二级页右滑退出禁用，防误触
            swipeEnabled = false,
            swipeNeighbor = { from, d -> if (d < 0 && from != 0) 0 else null },
            onSwipeCommit = { screen = it },
        ) { s ->
            when (s) {
                0 -> TeacherMyHome(
                    profile = profile, backdrop = pageBackdrop, scrollState = scrollState,
                    onEdit = { screen = 10 },
                    onRecording = { screen = 11 },
                    onBindCourse = { screen = 1 },
                    onCreateAttendance = { screen = 2 },
                    onPostNote = { screen = 3 },
                    onCreateCourse = { screen = 4 },
                    onClasses = { screen = 5 },
                    onNotification = { screen = 6 },
                    onAbout = { screen = 7 },
                    onStorage = { screen = 8 },
                    onDirectory = { screen = 9 },
                    onLogout = onLogout,
                )
                1 -> BindCourseScreen(pageBackdrop, onBack = { screen = 0 })
                2 -> CreateAttendanceScreen(pageBackdrop, onBack = { screen = 0 })
                3 -> PostNoteScreen(pageBackdrop, onBack = { screen = 0 })
                4 -> CreateCourseScreen(pageBackdrop, onBack = { screen = 0 })
                5 -> TeacherClassesScreen(pageBackdrop, onBack = { screen = 0 })
                6 -> NotificationSettingsPage(pageBackdrop, onBack = { screen = 0 })
                7 -> AboutPage(pageBackdrop, onBack = { screen = 0 })
                8 -> StorageManagePage(onBack = { screen = 0 })
                9 -> StudentDirectoryScreen(onBack = { screen = 0 })
                10 -> EditProfilePage(
                    profile, pageBackdrop, teacher = true,
                    onBack = { screen = 0 },
                    onSave = {
                        profile = it
                        ProfileStore.save(KewuApi.username.orEmpty(), it)
                        val edited = it
                        profileScope.launch {
                            KewuApi.updateProfile(
                                edited.name, edited.school.ifBlank { null }, null, edited.phone, edited.email,
                                subject = edited.subject, bio = edited.bio,
                            )?.let { err -> snackbar.toast("同步失败：$err") }
                        }
                        screen = 0
                    },
                )
                11 -> TeacherRecordingScreen(onBack = { screen = 0 })
            }
        }
    }
}

@Composable
private fun TeacherMyHome(
    profile: UserProfile,
    backdrop: io.github.suqi8.coui.kmp.blur.LayerBackdrop,
    scrollState: androidx.compose.foundation.ScrollState,
    onEdit: () -> Unit,
    onRecording: () -> Unit,
    onBindCourse: () -> Unit,
    onCreateAttendance: () -> Unit,
    onPostNote: () -> Unit,
    onCreateCourse: () -> Unit,
    onClasses: () -> Unit,
    onNotification: () -> Unit,
    onAbout: () -> Unit,
    onStorage: () -> Unit,
    onDirectory: () -> Unit,
    onLogout: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    // 退出登录二次确认（coui OverlayDialog），与学生端一致
    var confirmLogout by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(bottom = KewuNavClearance),
    ) {
        Spacer(Modifier.height(12.dp))

        // 资料头：液态玻璃大卡（教师徽章区分身份）
        val p0 = rememberEnterProgress(0)
        KewuGlassCard(
            backdrop = backdrop,
            shape = RoundedCornerShape(20.dp),
            blurRadius = glassCondenseBlur(p0, 22f),
            tintAlpha = 0.30f * p0,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp)
                .glassEnter(p0)
                .border(1.dp, cs.dividerLine, RoundedCornerShape(20.dp)),
        ) {
            Row(
                Modifier.fillMaxWidth().alpha(p0).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(62.dp)
                        .clip(CircleShape)
                        .background(cs.tertiaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(profile.name.firstOrNull()?.toString() ?: "师", color = cs.onTertiaryContainer,
                        fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(14.dp))
                // 与学生端资料头同一结构（2026-11-02）：姓名 / 工号胶囊 / 学校 / 所教班级（多个逐行）
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(profile.name.ifBlank { "老师" }, color = cs.onSurface, fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp))
                        KewuTag("教师", KewuBlue.copy(alpha = 0.12f), KewuBlue)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KewuTag("工号", cs.secondaryContainer, cs.onSecondaryVariant)
                        Spacer(Modifier.width(7.dp))
                        Text(profile.studentId, color = cs.onSurface, fontSize = 14.sp,
                            fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp, maxLines = 1)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(profile.school.ifBlank { "未填写学校" }, color = cs.onSurfaceSecondary, fontSize = 13.sp, maxLines = 1)
                    val classes = profile.className.split("、").filter { it.isNotBlank() }
                    if (classes.isEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("暂无班级", color = cs.onSurfaceSecondary, fontSize = 13.sp, maxLines = 1)
                    } else classes.forEach {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = cs.onSurfaceSecondary, fontSize = 13.sp, maxLines = 1)
                    }
                }
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

        // 教学管理：五行并成一张 coui 分组卡（ArrowPreference），整组错峰入场
        GroupLabel("教学管理", Modifier.riseEnter(rememberEnterProgress(1)))
        KewuCardGroup(Modifier.riseEnter(rememberEnterProgress(2))) {
            FuncRow(6, "新建课程", "开课、建班、发作业", backdrop, onClick = onCreateCourse)
            FuncRow(5, "班级管理", "建班与邀请码", backdrop, onClick = onClasses)
            FuncRow(5, "同学管理", "班级成员", backdrop, onClick = onDirectory)
            FuncRow(0, "课程绑定", "绑定已有课程", backdrop, onClick = onBindCourse)
            FuncRow(7, "发起签到", "扫码 / 暗号签到", backdrop, onClick = onCreateAttendance)
            FuncRow(8, "发布笔记", "课堂要点", backdrop, onClick = onPostNote)
            FuncRow(1, "录音管理", "课堂录音回放", backdrop, onClick = onRecording)
        }

        GroupLabel("通用", Modifier.riseEnter(rememberEnterProgress(5)))
        KewuCardGroup(Modifier.riseEnter(rememberEnterProgress(6))) {
            FuncRow(2, "通知设置", "提醒与权限", backdrop, onClick = onNotification)
            FuncRow(9, "内存管理", "清理缓存", backdrop, onClick = onStorage)
            FuncRow(3, "关于我们", "版本与服务状态", backdrop, onClick = onAbout)
        }

        Spacer(Modifier.height(2.dp))
        GroupLabel("账号", Modifier.riseEnter(rememberEnterProgress(8)))
        val p9 = rememberEnterProgress(9)
        KewuGlassCard(
            backdrop = backdrop,
            shape = RoundedCornerShape(16.dp),
            blurRadius = glassCondenseBlur(p9, 18f),
            tintAlpha = 0.26f * p9,
            onClick = { haptic(HapticKind.Tick); confirmLogout = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .glassEnter(p9)
                .border(1.dp, cs.dividerLine, RoundedCornerShape(16.dp)),
        ) {
            Row(
                Modifier.fillMaxWidth().alpha(p9).padding(vertical = 15.dp, horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("退出登录", color = cs.error, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    // ★ 与「结束课程」同因同解：onConfirm 里若立刻 onLogout() 触发整页重组（主界面→登录页），
    // 弹窗退场动画还在采样玻璃背景，coui 玻璃采样到正在销毁的父节点会 SIGSEGV 闪退。
    // 故 onConfirm 只置位 + 收起弹窗，真正退出延后到 onDismissFinished（弹窗完全退场后）执行。
    var logoutConfirmed by remember { mutableStateOf(false) }
    KewuConfirmDialog(
        show = confirmLogout,
        title = "退出登录",
        summary = "退出后需重新登录",
        confirmText = "退出登录",
        onConfirm = { logoutConfirmed = true; confirmLogout = false },
        onDismiss = { confirmLogout = false },
        onDismissFinished = {
            if (logoutConfirmed) {
                logoutConfirmed = false
                onLogout()
            }
        },
    )
    }
}
