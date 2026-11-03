package com.kewu.admin.ui

import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.Class
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kewu.admin.AdminApi
import com.kewu.admin.AdminSession
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch

enum class AdminSection(val title: String, val icon: ImageVector) {
    OVERVIEW("运行概览", Icons.Outlined.Dashboard),
    SERVER("服务器状态", Icons.Outlined.Monitor),
    USERS("用户管理", Icons.Outlined.People),
    RECORDINGS("录音资源", Icons.Outlined.Mic),
    COURSES("课程与班级", Icons.Outlined.Class),
    ASSIGNMENTS("作业与批阅", Icons.Outlined.Assignment),
    ATTENDANCE("签到管理", Icons.Outlined.FactCheck),
}

@Composable
fun AdminApp() {
    val api = remember { AdminApi() }
    var deployPromptCompleted by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<AdminSession?>(null) }
    var section by remember { mutableStateOf(AdminSection.OVERVIEW) }
    var showDeployment by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val backdrop = rememberAdminGlassBackdrop()
    DisposableEffect(Unit) { onDispose { api.close() } }

    // 启动时先询问是否从 GitHub 一键部署后端；部署完成或明确跳过后才进入登录/管理界面。
    if (!deployPromptCompleted) {
        DeploymentGate(onFinished = { deployPromptCompleted = true })
        return
    }

    val currentSession = session
    if (currentSession == null) {
        LoginScreen(api = api, onLogin = { session = it; section = AdminSection.OVERVIEW })
        return
    }

    val cs = COUITheme.colorScheme
    Box(Modifier.fillMaxSize()) {
        // 被玻璃采样的背景与右侧业务内容层。
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(cs.surface)
                .drawBehind {
                    val w = this.size.width
                    val h = this.size.height
                    val minD = minOf(w, h)
                    drawCircle(
                        color = KewuBlue.copy(alpha = 0.08f),
                        radius = minD * 0.34f,
                        center = Offset(w * 0.20f, h * 0.08f),
                    )
                    drawCircle(
                        color = KewuGreen.copy(alpha = 0.08f),
                        radius = minD * 0.30f,
                        center = Offset(w * 0.82f, h * 0.92f),
                    )
                },
        ) {
            Row(Modifier.fillMaxSize()) {
                // 左侧液态玻璃导航浮层占用的空间。
                Spacer(Modifier.width(252.dp))
                Box(Modifier.weight(1f).fillMaxHeight().padding(28.dp)) {
                    val notify: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }
                    when (section) {
                        AdminSection.OVERVIEW -> OverviewPage(api, onDeploy = { showDeployment = true })
                        AdminSection.SERVER -> ServerPage(api)
                        AdminSection.USERS -> UsersPage(api, notify)
                        AdminSection.RECORDINGS -> RecordingsPage(api, notify)
                        AdminSection.COURSES -> CoursesPage(api, notify)
                        AdminSection.ASSIGNMENTS -> AssignmentsPage(api, notify)
                        AdminSection.ATTENDANCE -> AttendancePage(api, notify)
                    }
                }
            }
        }

        // coui 采样层之外的液态玻璃导航（与学生端底部玻璃导航同一官方范式）。
        AdminGlassCard(
            backdrop = backdrop,
            modifier = Modifier.width(248.dp).fillMaxHeight().padding(10.dp),
            shape = RoundedCornerShape(26.dp),
            blurRadius = 28.dp,
            tintAlpha = 0.38f,
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).background(KewuBlue, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("课", color = Color.White, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("课悟总控端", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "KEWU ADMIN",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Spacer(Modifier.height(22.dp))
                AdminSection.entries.forEach { item ->
                    NavigationDrawerItem(
                        selected = section == item,
                        onClick = { section = item },
                        icon = { Icon(item.icon, null) },
                        label = { Text(item.title) },
                        modifier = Modifier.padding(vertical = 3.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = KewuBlue.copy(alpha = 0.13f),
                            selectedIconColor = KewuBlue,
                            selectedTextColor = KewuBlue,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.38f), RoundedCornerShape(18.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(34.dp).background(KewuBlue, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                        Text(currentSession.nickname.take(1), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(currentSession.nickname, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text("系统管理员", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { api.logout(); session = null }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Logout, "退出登录", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomEnd).padding(28.dp),
        )
        if (showDeployment) {
            DeploymentDialog(onClose = { showDeployment = false })
        }
    }
}
