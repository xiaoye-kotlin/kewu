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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.kewu.admin.DeploymentService
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 应用启动时的部署入口：用户确认后才进入管理界面。 */
@Composable
fun DeploymentGate(onFinished: () -> Unit) {
    val cs = COUITheme.colorScheme
    val backdrop = rememberAdminGlassBackdrop()
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(cs.surface)
                .drawBehind {
                    val w = this.size.width
                    val h = this.size.height
                    val minD = minOf(w, h)
                    drawCircle(KewuBlue.copy(alpha = 0.15f), minD * 0.45f, Offset(w * 0.12f, h * 0.18f))
                    drawCircle(KewuGreen.copy(alpha = 0.13f), minD * 0.40f, Offset(w * 0.90f, h * 0.78f))
                },
        )
        AdminGlassCard(
            backdrop = backdrop,
            modifier = Modifier.width(880.dp).heightIn(max = 760.dp).align(Alignment.Center),
            shape = RoundedCornerShape(30.dp),
            blurRadius = 28.dp,
            tintAlpha = 0.44f,
        ) {
            DeploymentContent(
                compact = false,
                onSkip = onFinished,
                onDeploymentFinished = onFinished,
            )
        }
    }
}

/** 登录后从运行概览重新发起部署。 */
@Composable
fun DeploymentDialog(onClose: () -> Unit) {
    var canClose by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = { },
        title = { Text("一键部署后端服务") },
        text = {
            Box(Modifier.width(760.dp).heightIn(max = 620.dp)) {
                DeploymentContent(
                    compact = true,
                    onSkip = onClose,
                    onDeploymentFinished = onClose,
                    onBusyChange = { canClose = !it },
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose, enabled = canClose) { Text("关闭") } },
    )
}

@Composable
private fun DeploymentContent(
    compact: Boolean,
    onSkip: () -> Unit,
    onDeploymentFinished: () -> Unit,
    onBusyChange: (Boolean) -> Unit = {},
) {
    val service = remember { DeploymentService() }
    var detectedRoot by remember { mutableStateOf<String?>(null) }
    var serverRoot by remember { mutableStateOf("") }
    var zipUrl by remember { mutableStateOf(service.defaultBackendZipUrl) }
    var deploying by remember { mutableStateOf(false) }
    var success by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val logs = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        detectedRoot = service.detectServerRoot()
        serverRoot = detectedRoot ?: ""
    }

    fun startDeployment() {
        if (serverRoot.isBlank()) {
            error = "请填写服务器后端目录"
            return
        }
        scope.launch {
            deploying = true
            onBusyChange(true)
            success = false
            error = null
            logs.clear()
            logs.add("开始从 GitHub 拉取后端源码并部署……")
            val result = service.deploy(serverRoot.trim(), zipUrl.trim()) { logs.add(it) }
            deploying = false
            result.onSuccess {
                logs.add("部署完成，稍后进入管理界面。")
                success = true
                onBusyChange(false)
                if (compact) onDeploymentFinished() else {
                    scope.launch { delay(900); onDeploymentFinished() }
                }
            }.onFailure {
                error = it.message ?: "部署失败"
                logs.add("部署未完成，可查看日志修正后重试，或跳过进入管理界面。")
                onBusyChange(false)
            }
        }
    }

    Column(Modifier.padding(if (compact) 4.dp else 30.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(48.dp).height(48.dp).background(KewuBlue, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.CloudSync, null, tint = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("后端服务一键部署", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "从 GitHub 获取 kewu-8080 最新源码，在服务器本机构建、试启动、切换并自动回滚",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        if (detectedRoot == null) {
            Text(
                "未自动识别到服务器后端目录。总控端应放在服务器本机运行，并填写包含 deploy-8080.ps1 与 kewu-8080.jar 的目录。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
        } else {
            Text("已识别服务器后端目录：$detectedRoot", color = KewuGreen, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = serverRoot,
            onValueChange = { serverRoot = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("服务器后端目录") },
            singleLine = true,
            enabled = !deploying,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = zipUrl,
            onValueChange = { zipUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("后端源码 ZIP 地址") },
            singleLine = true,
            enabled = !deploying,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "要求：在服务器本机运行；使用管理员身份启动总控端；目录中保留服务器既有 deploy-8080.ps1。部署前会自动在 8090 试启动，失败不会替换线上 8080。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = ::startDeployment,
                enabled = !deploying,
                modifier = Modifier.height(46.dp),
                shape = RoundedCornerShape(13.dp),
            ) {
                if (deploying) CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.CloudSync, null)
                Spacer(Modifier.width(8.dp))
                Text(if (deploying) "部署中…" else "开始一键部署")
            }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(onClick = onSkip, enabled = !deploying, modifier = Modifier.height(46.dp), shape = RoundedCornerShape(13.dp)) {
                Icon(Icons.Outlined.SkipNext, null)
                Spacer(Modifier.width(6.dp))
                Text("跳过，进入管理界面")
            }
            if (success) {
                Spacer(Modifier.width(10.dp))
                StatusPill("部署完成", StatusTone.SUCCESS)
            }
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
        }
        Spacer(Modifier.height(14.dp))
        Panel(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 170.dp, max = if (compact) 320.dp else 420.dp)) {
            LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items(logs) { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (line.startsWith("[FAIL]")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
