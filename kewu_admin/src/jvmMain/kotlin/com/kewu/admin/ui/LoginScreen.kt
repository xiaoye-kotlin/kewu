package com.kewu.admin.ui

import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import io.github.suqi8.coui.kmp.basic.Icon
import androidx.compose.material3.MaterialTheme
import io.github.suqi8.coui.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.admin.AdminApi
import com.kewu.admin.AdminSession
import com.kewu.admin.DeploymentState
import com.kewu.admin.LoginCredentials
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldDefaults
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(api: AdminApi, onLogin: (AdminSession) -> Unit) {
    // 优先用本地「记住密码」保存的凭证；否则从本机 kewu.env 自动填入（解决随机密码看不到的问题）。
    val remembered = remember { LoginCredentials.load() }
    val envCredentials = remember { DeploymentState().adminCredentials() }
    val initial = remembered?.takeIf { it.remember || it.autoLogin }
    var username by remember { mutableStateOf(initial?.username ?: envCredentials?.first ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: envCredentials?.second ?: "") }
    var remember by remember { mutableStateOf(initial?.remember ?: false) }
    var autoLogin by remember { mutableStateOf(initial?.autoLogin ?: false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit(): kotlinx.coroutines.Job = scope.launch {
        if (username.isBlank() || password.isBlank()) {
            error = "请输入管理员账号和密码"
            return@launch
        }
        loading = true
        error = null
        try {
            api.resolveBaseUrl()
            val session = api.login(username.trim(), password)
            // 登录成功才保存凭证（避免记错密码）
            LoginCredentials.save(username.trim(), password, remember, autoLogin)
            onLogin(session)
        } catch (e: Exception) {
            error = e.message ?: "登录失败，请稍后重试"
        } finally {
            loading = false
        }
    }

    // 自动登录：仅在用户勾选过且凭证齐全时尝试一次；失败则停留在登录页让用户手动处理。
    val autoTried = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!autoTried.value && remembered?.autoLogin == true && remembered.username.isNotBlank() && remembered.password.isNotBlank()) {
            autoTried.value = true
            username = remembered.username
            password = remembered.password
            submit().join()
        }
    }

    val cs = COUITheme.colorScheme
    val backdrop = rememberAdminGlassBackdrop()
    Box(Modifier.fillMaxSize()) {
        // 被玻璃采样的课悟品牌背景：coui 灰底 + 品牌色光（玻璃的兄弟层、位于其后）。
        AdminBackdropLayer(backdrop, strength = 1.3f)

        // 采样层之外的官方范式液态玻璃登录卡。
        val p = rememberEnterProgress(0, baseDelayMs = 80L)
        AdminGlassCard(
            backdrop = backdrop,
            modifier = Modifier.width(460.dp).align(Alignment.Center).glassEnter(p, 48f),
            shape = RoundedCornerShape(30.dp),
            blurRadius = glassCondenseBlur(p, 28f),
            tintAlpha = 0.46f * p.coerceIn(0f, 1f),
        ) {
            Column(Modifier.padding(40.dp).alpha(p.coerceIn(0f, 1f))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(52.dp).background(KewuBlue, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.AdminPanelSettings, null, tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("课悟总控端", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("KEWU ADMIN CONSOLE", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.height(34.dp))
                Text("管理员登录", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("集中监控服务状态并管理平台数据", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(22.dp))
                AdminLoginField(username, { username = it }, "管理员账号", enabled = !loading)
                Spacer(Modifier.height(12.dp))
                AdminLoginField(password, { password = it }, "密码", password = true, enabled = !loading)
                Spacer(Modifier.height(14.dp))

                // 记住密码 / 自动登录
                CheckOption("记住密码", remember, enabled = !loading) {
                    remember = it
                    if (!it) autoLogin = false // 取消记住密码时自动登录也失效
                }
                Spacer(Modifier.height(6.dp))
                CheckOption("自动登录（启动时免输入）", autoLogin, enabled = !loading && remember) {
                    autoLogin = it
                    if (it) remember = true
                }

                if (error != null) {
                    Text(
                        error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                Spacer(Modifier.height(22.dp))
                Button(
                    onClick = { submit() },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 14.dp,
                    minHeight = 50.dp,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (loading) {
                            InfiniteProgressIndicator(color = COUITheme.colorScheme.onPrimary, size = 20.dp, strokeWidth = 2.dp)
                        } else {
                            Text("进入管理控制台", color = COUITheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Text(
                    "凭证仅保存在本机（混淆存储），自动登录需先勾选记住密码",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 18.dp),
                )
            }
        }
    }
}

/** 轻量勾选行：用 coui 文本 + material 图标自绘，避免额外依赖。 */
@Composable
private fun CheckOption(label: String, checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(30.dp).alpha(if (enabled) 1f else 0.45f).clickable(enabled = enabled) { onChecked(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (checked) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            null,
            tint = if (checked) KewuBlue else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
    }
}

/** coui 原生输入框：与学生端登录页同款 Rectangle 模式。 */
@Composable
private fun AdminLoginField(
    value: String,
    onValueChange: (String) -> Unit,
    title: String,
    password: Boolean = false,
    enabled: Boolean = true,
) {
    val cs = COUITheme.colorScheme
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        backgroundMode = TextFieldMode.Rectangle,
        colors = TextFieldDefaults.textFieldColors(backgroundColor = cs.surfaceContainer.copy(alpha = 0.82f)),
        label = title,
        useLabelAsPlaceholder = false,
        singleLine = true,
        enabled = enabled,
        showClearButton = !password,
        showPasswordToggle = password,
    )
}
