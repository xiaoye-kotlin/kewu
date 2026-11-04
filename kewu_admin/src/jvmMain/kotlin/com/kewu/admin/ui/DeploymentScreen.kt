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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.SkipNext
import io.github.suqi8.coui.kmp.basic.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import io.github.suqi8.coui.kmp.basic.Text
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
import androidx.compose.ui.unit.sp
import com.kewu.admin.DeploymentService
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JFileChooser

/** 应用启动时的部署入口：用户确认后才进入管理界面。 */
@Composable
fun DeploymentGate(onFinished: () -> Unit) {
    val cs = COUITheme.colorScheme
    val backdrop = rememberAdminGlassBackdrop()
    Box(Modifier.fillMaxSize()) {
        AdminBackdropLayer(backdrop, strength = 1.25f)
        val p = rememberEnterProgress(0, baseDelayMs = 60L)
        AdminGlassCard(
            backdrop = backdrop,
            modifier = Modifier.width(880.dp).heightIn(max = 760.dp).align(Alignment.Center).glassEnter(p, 40f),
            shape = RoundedCornerShape(30.dp),
            blurRadius = glassCondenseBlur(p, 28f),
            tintAlpha = 0.44f * p.coerceIn(0f, 1f),
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
        title = { Text("一键部署后端服务", fontSize = 19.sp, fontWeight = FontWeight.SemiBold) },
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
        confirmButton = { AdminButton("关闭", onClose, style = AdminButtonStyle.Secondary, enabled = canClose) },
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
    var jarUrl by remember { mutableStateOf(service.defaultPrebuiltJarUrl) }
    var onlineBuild by remember { mutableStateOf(false) }
    var deploying by remember { mutableStateOf(false) }
    var success by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
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
            logs.add(if (onlineBuild) "开始从 GitHub 拉取源码并在服务器构建部署……" else "开始从 GitHub Release 下载预构建后端包并部署……")
            val result = service.deploy(serverRoot.trim(), jarUrl.trim(), zipUrl.trim(), onlineBuild) { logs.add(it) }
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
                "未自动识别到既有部署目录。总控端应在服务器本机运行；点「浏览…」选择目录即可，全新服务器直接选一个空目录，首次部署会自动初始化。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
        } else {
            Text("已识别服务器后端目录：$detectedRoot", color = KewuGreen, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = serverRoot,
                onValueChange = { serverRoot = it },
                modifier = Modifier.weight(1f),
                label = "服务器后端目录",
                singleLine = true,
                enabled = !deploying,
            )
            Spacer(Modifier.width(8.dp))
            AdminButton(
                "浏览…",
                {
                    val chooser = JFileChooser().apply {
                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        dialogTitle = "选择服务器后端目录（全新服务器可直接选一个空目录）"
                        if (serverRoot.isNotBlank()) currentDirectory = File(serverRoot)
                    }
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        serverRoot = chooser.selectedFile.absolutePath
                    }
                },
                icon = Icons.Outlined.FolderOpen,
                style = AdminButtonStyle.Secondary,
                enabled = !deploying,
                compact = true,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(
                selected = !onlineBuild,
                onClick = { onlineBuild = false },
                label = { Text("预构建包部署（推荐）") },
                enabled = !deploying,
            )
            FilterChip(
                selected = onlineBuild,
                onClick = { onlineBuild = true },
                label = { Text("服务器在线构建") },
                enabled = !deploying,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (onlineBuild) "从 GitHub 拉源码，在服务器本机跑 Gradle 构建（慢、依赖多，仅作备选）。"
            else "直接下载 GitHub Release 上由开发机构建验证过的 jar，服务器不跑 Gradle，几分钟完成。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = if (onlineBuild) zipUrl else jarUrl,
            onValueChange = { if (onlineBuild) zipUrl = it else jarUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = if (onlineBuild) "后端源码 ZIP 地址" else "预构建 jar 下载地址（GitHub Release）",
            singleLine = true,
            enabled = !deploying,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "要求：在服务器本机以管理员身份运行。目录无需预先准备任何文件——首次部署会自动安装 JDK 21、生成 kewu.env、创建开机启动任务；已有部署的目录会自动安全升级（8090 试启动，失败自动回滚）。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AdminButton(
                if (deploying) "部署中…" else "开始一键部署",
                ::startDeployment,
                icon = Icons.Outlined.CloudSync,
                loading = deploying,
            )
            Spacer(Modifier.width(10.dp))
            AdminButton(
                "跳过，进入管理界面",
                onSkip,
                icon = Icons.Outlined.SkipNext,
                style = AdminButtonStyle.Secondary,
                enabled = !deploying,
            )
            Spacer(Modifier.width(10.dp))
            AdminButton(
                if (copied) "已复制" else "复制日志",
                {
                    val text = logs.joinToString("\n")
                    runCatching {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
                    }.onSuccess {
                        copied = true
                        scope.launch { delay(1600); copied = false }
                    }
                },
                icon = Icons.Outlined.ContentCopy,
                style = AdminButtonStyle.Secondary,
                enabled = !deploying && logs.isNotEmpty(),
            )
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
