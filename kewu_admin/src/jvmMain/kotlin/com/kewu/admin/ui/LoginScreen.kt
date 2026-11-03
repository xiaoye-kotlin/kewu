package com.kewu.admin.ui

import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kewu.admin.AdminApi
import com.kewu.admin.AdminSession
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
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (username.isBlank() || password.isBlank()) {
            error = "请输入管理员账号和密码"
            return
        }
        scope.launch {
            loading = true
            error = null
            try {
                onLogin(api.login(username.trim(), password))
            } catch (e: Exception) {
                error = e.message ?: "登录失败，请稍后重试"
            } finally {
                loading = false
            }
        }
    }

    val cs = COUITheme.colorScheme
    val backdrop = rememberAdminGlassBackdrop()
    Box(Modifier.fillMaxSize()) {
        // 被玻璃采样的课悟品牌背景：coui 灰底 + 蓝绿品牌光斑。
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(cs.surface)
                .drawBehind {
                    val w = this.size.width
                    val h = this.size.height
                    val minD = minOf(w, h)
                    drawCircle(KewuBlue.copy(alpha = 0.16f), minD * 0.46f, Offset(w * 0.16f, h * 0.16f))
                    drawCircle(KewuGreen.copy(alpha = 0.14f), minD * 0.42f, Offset(w * 0.86f, h * 0.84f))
                    drawCircle(KewuBlue.copy(alpha = 0.08f), minD * 0.35f, Offset(w * 0.72f, h * 0.08f))
                },
        )

        // 采样层之外的官方范式液态玻璃登录卡。
        AdminGlassCard(
            backdrop = backdrop,
            modifier = Modifier.width(460.dp).align(Alignment.Center),
            shape = RoundedCornerShape(30.dp),
            blurRadius = 28.dp,
            tintAlpha = 0.46f,
        ) {
            Column(Modifier.padding(40.dp)) {
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
                Spacer(Modifier.height(36.dp))
                Text("管理员登录", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("集中监控服务状态并管理平台数据", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(24.dp))
                AdminLoginField(username, { username = it }, "管理员账号", enabled = !loading)
                Spacer(Modifier.height(12.dp))
                AdminLoginField(password, { password = it }, "密码", password = true, enabled = !loading)
                if (error != null) {
                    Text(
                        error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = ::submit,
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
                    "仅限 ADMIN 角色账号 · 凭证仅保存在本次运行内存中",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 18.dp),
                )
            }
        }
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
