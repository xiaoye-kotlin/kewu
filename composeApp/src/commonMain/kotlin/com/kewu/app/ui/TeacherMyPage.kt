package com.kewu.app.ui

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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.BackHandler
import com.kewu.app.KewuApi
import com.kewu.app.KewuBlue
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop

// ───────────────────────── 教师端 · 我的（tab 2） ─────────────────────────
//
// 结构对齐学生端 MyPage：资料头玻璃卡 + 分组功能行（coui Card）+ 退出登录；
// 子页路由用「仅 slide 无 fade」的 AnimatedContent + BackHandler（与学生端同款，防 coui SIGSEGV）。
// 功能分组：
//   教学管理：课程绑定（BindCourseScreen）/ 发起签到（CreateAttendanceScreen）/ 发布笔记（PostNoteScreen）
//   通用：通知设置 / 关于我们（复用 MyPage 已置 internal 的子页）

@Composable
fun TeacherMyPage(onLogout: () -> Unit, onNavHide: (Boolean) -> Unit = {}) {
    // 0=主页 1=课程绑定 2=发起签到 3=发布笔记 6=通知设置 7=关于我们（编号与学生端对齐）
    var screen by remember { mutableStateOf(0) }
    val pageBackdrop = rememberGlassBackdrop()
    val scrollState = rememberScrollState()

    var name by remember { mutableStateOf(KewuApi.currentUser?.user?.nickname ?: "") }
    var account by remember { mutableStateOf(KewuApi.username ?: "") }
    LaunchedEffect(Unit) {
        if (KewuApi.currentUser == null) KewuApi.me()
        name = KewuApi.currentUser?.user?.nickname ?: name
        account = KewuApi.username ?: account
    }

    BackHandler(enabled = screen != 0) { screen = 0 }

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
                .background(MaterialTheme.colorScheme.background),
        )
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                if (targetState > initialState) {
                    slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { w -> w } togetherWith
                        slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { w -> -w / 5 }
                } else {
                    slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { w -> -w / 5 } togetherWith
                        slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { w -> w }
                }
            },
            modifier = Modifier.fillMaxSize(),
            label = "teacherMyNav",
        ) { s ->
            when (s) {
                0 -> TeacherMyHome(
                    name = name, account = account, backdrop = pageBackdrop, scrollState = scrollState,
                    onBindCourse = { screen = 1 },
                    onCreateAttendance = { screen = 2 },
                    onPostNote = { screen = 3 },
                    onNotification = { screen = 6 },
                    onAbout = { screen = 7 },
                    onLogout = onLogout,
                )
                1 -> BindCourseScreen(pageBackdrop, onBack = { screen = 0 })
                2 -> CreateAttendanceScreen(pageBackdrop, onBack = { screen = 0 })
                3 -> PostNoteScreen(pageBackdrop, onBack = { screen = 0 })
                6 -> NotificationSettingsPage(pageBackdrop, onBack = { screen = 0 })
                7 -> AboutPage(pageBackdrop, onBack = { screen = 0 })
            }
        }
    }
}

@Composable
private fun TeacherMyHome(
    name: String,
    account: String,
    backdrop: io.github.suqi8.coui.kmp.blur.LayerBackdrop,
    scrollState: androidx.compose.foundation.ScrollState,
    onBindCourse: () -> Unit,
    onCreateAttendance: () -> Unit,
    onPostNote: () -> Unit,
    onNotification: () -> Unit,
    onAbout: () -> Unit,
    onLogout: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(bottom = 118.dp),
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
                .border(1.dp, cs.outlineVariant, RoundedCornerShape(20.dp)),
        ) {
            Row(
                Modifier.fillMaxWidth().alpha(p0).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(62.dp)
                        .clip(CircleShape)
                        .background(cs.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(name.firstOrNull()?.toString() ?: "师", color = cs.onPrimaryContainer,
                        fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name.ifBlank { "老师" }, color = cs.onSurface, fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(KewuBlue.copy(alpha = 0.12f))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text("教师", color = KewuBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("账号 $account", color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
                }
            }
        }

        GroupLabel("教学管理", Modifier.riseEnter(rememberEnterProgress(1)))
        FuncRow(0, "课程绑定", "绑定课程后可发起签到、发布笔记与作业", backdrop,
            onClick = onBindCourse, modifier = Modifier.riseEnter(rememberEnterProgress(2)))
        FuncRow(1, "发起签到", "创建签到会话，学生扫码 / 暗号打卡", backdrop,
            onClick = onCreateAttendance, modifier = Modifier.riseEnter(rememberEnterProgress(3)))
        FuncRow(2, "发布笔记", "记录课堂要点，学生端即时可见", backdrop,
            onClick = onPostNote, modifier = Modifier.riseEnter(rememberEnterProgress(4)))

        GroupLabel("通用", Modifier.riseEnter(rememberEnterProgress(5)))
        FuncRow(2, "通知设置", "签到提醒、作业提醒与权限管理", backdrop,
            onClick = onNotification, modifier = Modifier.riseEnter(rememberEnterProgress(6)))
        FuncRow(3, "关于我们", "课悟 · 基于华为云的多端协同 AI 课堂", backdrop,
            onClick = onAbout, modifier = Modifier.riseEnter(rememberEnterProgress(7)))

        Spacer(Modifier.height(14.dp))
        GroupLabel("账号", Modifier.riseEnter(rememberEnterProgress(8)))
        val p9 = rememberEnterProgress(9)
        KewuGlassCard(
            backdrop = backdrop,
            shape = RoundedCornerShape(16.dp),
            blurRadius = glassCondenseBlur(p9, 18f),
            tintAlpha = 0.26f * p9,
            onClick = onLogout,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .glassEnter(p9)
                .border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp)),
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
}
