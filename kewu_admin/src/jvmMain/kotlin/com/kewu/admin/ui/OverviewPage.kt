package com.kewu.admin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Class
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kewu.admin.AdminApi
import com.kewu.admin.formatBytes
import com.kewu.admin.formatCount
import com.kewu.admin.long
import com.kewu.admin.str
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun OverviewPage(api: AdminApi, onDeploy: () -> Unit) {
    var overview by remember { mutableStateOf<JsonObject?>(null) }
    var health by remember { mutableStateOf<JsonObject?>(null) }
    var obsRecordingCount by remember { mutableStateOf(0) }
    var obsError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshedAt by remember { mutableStateOf("—") }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        loading = true
        error = null
        try {
            overview = api.overview()
            health = api.health()
            runCatching { api.obsRecordings().size }.onSuccess {
                obsRecordingCount = it
                obsError = null
            }.onFailure {
                obsRecordingCount = 0
                obsError = it.message
            }
            refreshedAt = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            refresh()
            delay(30_000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader("运行概览", "平台数据与服务器组件状态，每 30 秒自动刷新") {
            OutlinedButton(onClick = onDeploy) {
                Icon(Icons.Outlined.CloudUpload, null)
                Spacer(Modifier.width(6.dp))
                Text("一键部署")
            }
            Text("上次刷新 $refreshedAt", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
            Button(onClick = { scope.launch { refresh() } }, enabled = !loading) {
                Icon(Icons.Outlined.Refresh, null)
                Spacer(Modifier.width(6.dp))
                Text("立即刷新")
            }
        }
        if (loading && overview == null) {
            LoadingBlock()
            return@Column
        }
        if (error != null && overview == null) {
            ErrorBlock(error ?: "加载失败") { scope.launch { refresh() } }
            return@Column
        }
        val data = overview ?: return@Column
        val state = health
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MetricCard("平台用户", formatCount(data.long("userCount")), "学生 / 教师 / 管理员", Icons.Outlined.Group, Modifier.weight(1f))
            MetricCard("课程与班级", "${data.long("courseCount")} / ${data.long("classCount")}", "课程数 / 班级数", Icons.Outlined.Class, Modifier.weight(1f), KewuGreen)
            MetricCard(
                "录音资源",
                formatCount(maxOf(data.long("recordingCount"), obsRecordingCount.toLong())),
                "数据库 ${data.long("recordingCount")} / OBS 对象 $obsRecordingCount",
                Icons.Outlined.Mic,
                Modifier.weight(1f),
                AdminAmber,
            )
            MetricCard("录音存储", formatBytes(data.long("recordingBytes")), "OBS 对象总容量", Icons.Outlined.Storage, Modifier.weight(1f), KewuDeepBlue)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MetricCard("作业总数", formatCount(data.long("assignmentCount")), "跨全部课程", Icons.Outlined.Assignment, Modifier.weight(1f))
            MetricCard("提交总数", formatCount(data.long("submissionCount")), "全部版本汇总", Icons.Outlined.Assignment, Modifier.weight(1f), KewuGreen)
            Panel(Modifier.weight(2f)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Memory, null, tint = KewuBlue)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("JVM 已用内存", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Text("${state.long("jvmUsedMb")} MB", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("磁盘剩余", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Text(formatBytes(state.long("diskFreeBytes")), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel(Modifier.weight(1.15f).fillMaxSize()) {
                Column(Modifier.padding(22.dp)) {
                    Text("服务健康度", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("由总控端管理接口实时探测", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(18.dp))
                    ServiceStatusRow("业务后端", "统一 8080 · 课程 / 作业 / 签到", state.str("backend", "down"), Icons.Outlined.Cloud)
                    ServiceStatusRow("Redis 缓存", "会话 / 雷达 / 课堂状态", state.str("redis", "down"), Icons.Outlined.Memory)
                    ServiceStatusRow("华为云 OBS", "录音文件与对象存储", state.str("obs", "down"), Icons.Outlined.Storage)
                    Spacer(Modifier.height(12.dp))
                    Text("服务器时间：${state.str("time")}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            Panel(Modifier.weight(1f).fillMaxSize()) {
                Column(Modifier.padding(22.dp)) {
                    Text("端侧接入链路", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("表示 API 可用性，不代表真实在线设备数", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(18.dp))
                    ServiceStatusRow("学生端", "Android / Desktop 业务 API", state.str("backend", "down"), Icons.Outlined.PhoneAndroid)
                    ServiceStatusRow("助教端", "教师控制台与课堂服务", state.str("backend", "down"), Icons.Outlined.Computer)
                    Spacer(Modifier.height(12.dp))
                    Text("真实端侧在线统计需后续接入全平台心跳聚合接口。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (error != null) {
            Text("自动刷新失败：$error（将保留上次数据并重试）", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp))
        }
        obsError?.let {
            Text("OBS 录音数读取失败：$it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun ServiceStatusRow(title: String, subtitle: String, status: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val up = status.equals("up", true)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (up) KewuGreen else AdminRed)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        StatusPill(if (up) "运行正常" else "异常", if (up) StatusTone.SUCCESS else StatusTone.ERROR)
    }
}
