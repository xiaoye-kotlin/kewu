package com.kewu.admin.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
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
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.admin.AdminApi
import com.kewu.admin.AdminSession
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.SnackbarDuration
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch

enum class AdminSection(val title: String, val icon: ImageVector) {
    OVERVIEW("运行概览", Icons.Outlined.Dashboard),
    SERVER("服务器状态", Icons.Outlined.Monitor),
    CONFIG("密钥配置", Icons.Outlined.VpnKey),
    USERS("用户管理", Icons.Outlined.People),
    RECORDINGS("录音资源", Icons.Outlined.Mic),
    COURSES("课程与班级", Icons.Outlined.Class),
    ASSIGNMENTS("作业与批阅", Icons.Outlined.Assignment),
    ATTENDANCE("签到管理", Icons.Outlined.FactCheck),
}

private val NavWidth = 252.dp
private val NavItemHeight = 44.dp
private val NavItemGap = 4.dp

/**
 * 总控端外壳。图层（自下而上）：
 * 1. [AdminBackdropLayer]：灰底 + 品牌色光，唯一的玻璃采样源；
 * 2. 业务内容层（页面切换只做 offset 位移，页面内的玻璃页头 / 横幅 / 浮条采样第 1 层）；
 * 3. 左侧液态玻璃导航栏（采样第 1 层）。
 * 采样源是玻璃的兄弟层且位于其后，任何玻璃的祖先都没有 alpha / graphicsLayer。
 */
@Composable
fun AdminApp() {
    val api = remember { AdminApi() }
    var deployPromptCompleted by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<AdminSession?>(null) }
    var section by remember { mutableStateOf(AdminSection.OVERVIEW) }
    var showDeployment by remember { mutableStateOf(false) }
    val snackbar = LocalAdminSnackbar.current
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

    val notify: (String) -> Unit = { message ->
        scope.launch { snackbar.showSnackbar(message, duration = SnackbarDuration.Short) }
    }
    val openDeploy = { showDeployment = true }

    Box(Modifier.fillMaxSize()) {
        AdminBackdropLayer(backdrop)

        CompositionLocalProvider(LocalAdminBackdrop provides backdrop) {
            Row(Modifier.fillMaxSize()) {
                Spacer(Modifier.width(NavWidth + 4.dp))
                AdminPageHost(
                    target = section,
                    order = { it.ordinal },
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(top = 18.dp, end = 24.dp, bottom = 22.dp, start = 6.dp),
                ) { current ->
                    when (current) {
                        AdminSection.OVERVIEW -> OverviewPage(api, onDeploy = openDeploy)
                        AdminSection.SERVER -> ServerPage(api)
                        AdminSection.CONFIG -> ConfigPage(api, notify, onDeploy = openDeploy)
                        AdminSection.USERS -> UsersPage(api, notify)
                        AdminSection.RECORDINGS -> RecordingsPage(api, notify)
                        AdminSection.COURSES -> CoursesPage(api, notify)
                        AdminSection.ASSIGNMENTS -> AssignmentsPage(api, notify)
                        AdminSection.ATTENDANCE -> AttendancePage(api, notify)
                    }
                }
            }
        }

        AdminNavRail(
            backdrop = backdrop,
            section = section,
            session = currentSession,
            onSelect = { section = it },
            onLogout = { api.logout(); session = null },
        )

        if (showDeployment) {
            DeploymentDialog(onClose = { showDeployment = false })
        }
    }
}

/** 左侧液态玻璃导航栏：从左侧滑入凝结；选中指示块弹簧滑动；条目悬停浮色。 */
@Composable
private fun AdminNavRail(
    backdrop: io.github.suqi8.coui.kmp.blur.LayerBackdrop,
    section: AdminSection,
    session: AdminSession,
    onSelect: (AdminSection) -> Unit,
    onLogout: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    val p = rememberEnterProgress(0, baseDelayMs = 0L)
    AdminGlassCard(
        backdrop = backdrop,
        modifier = Modifier.width(NavWidth).fillMaxHeight().padding(10.dp).glassEnterX(p),
        shape = RoundedCornerShape(26.dp),
        blurRadius = glassCondenseBlur(p, 28f),
        tintAlpha = 0.38f * p.coerceIn(0f, 1f),
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp).alpha(p.coerceIn(0f, 1f))) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).background(KewuBlue, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("课", color = Color.White, fontWeight = FontWeight.Black, fontSize = 17.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("课悟总控端", fontWeight = FontWeight.Bold, color = cs.onSurface, fontSize = 16.sp)
                    Text("KEWU ADMIN", color = cs.onSurfaceSecondary, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(18.dp))

            // 选中指示块：在条目底下按弹簧滑到目标位置（纯位移，玻璃内部的子元素）
            Box(Modifier.fillMaxWidth()) {
                val indicatorY by animateDpAsState(
                    (NavItemHeight + NavItemGap) * section.ordinal,
                    spring(dampingRatio = 0.72f, stiffness = 420f),
                    label = "navIndicator",
                )
                Box(
                    Modifier
                        .offset(y = indicatorY)
                        .fillMaxWidth()
                        .height(NavItemHeight)
                        .background(KewuBlue.copy(alpha = 0.13f), RoundedCornerShape(14.dp)),
                )
                Column {
                    AdminSection.entries.forEach { item ->
                        NavItem(item, selected = section == item, onClick = { onSelect(item) })
                        Spacer(Modifier.height(NavItemGap))
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.40f), RoundedCornerShape(18.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(34.dp).background(KewuBlue, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                    Text(session.nickname.take(1), color = Color.White, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(session.nickname, color = cs.onSurface, fontSize = 14.sp, maxLines = 1)
                    Text("系统管理员", color = cs.onSurfaceSecondary, fontSize = 11.sp)
                }
                AdminIconAction(Icons.Outlined.Logout, "退出登录", onClick = onLogout)
            }
        }
    }
}

@Composable
private fun NavItem(item: AdminSection, selected: Boolean, onClick: () -> Unit) {
    val cs = COUITheme.colorScheme
    val interaction = rememberAdminInteraction()
    val hoverBg by animateColorAsState(
        if (interaction.hovered && !selected) cs.onSurface.copy(alpha = 0.05f) else Color.Transparent,
        tween(140),
        label = "navHover",
    )
    val fg by animateColorAsState(if (selected) KewuBlue else cs.onSurface, tween(180), label = "navFg")
    val iconTint by animateColorAsState(if (selected) KewuBlue else cs.onSurfaceSecondary, tween(180), label = "navIcon")
    val nudge by animateDpAsState(if (interaction.hovered && !selected) 3.dp else 0.dp, spring(stiffness = 600f), label = "navNudge")
    Row(
        Modifier
            .fillMaxWidth()
            .height(NavItemHeight)
            .pressScale(interaction.pressed, 0.97f)
            .clip(RoundedCornerShape(14.dp))
            .background(hoverBg)
            .hoverable(interaction.source)
            .clickable(interactionSource = interaction.source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.offset(x = nudge), verticalAlignment = Alignment.CenterVertically) {
            Icon(item.icon, null, tint = iconTint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(item.title, color = fg, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}
