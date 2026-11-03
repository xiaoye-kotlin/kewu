package com.kewu.admin.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kewu.admin.AdminApi
import com.kewu.admin.ServerMonitor
import com.kewu.admin.ServerRuntimeSnapshot
import com.kewu.admin.formatBytes
import com.kewu.admin.long
import com.kewu.admin.str
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 服务器本机实时状态：系统性能、磁盘、后端进程、Ktor / Redis / OBS 与运行时长。 */
@Composable
fun ServerPage(api: AdminApi) {
    var monitor by remember { mutableStateOf<ServerRuntimeSnapshot?>(null) }
    var health by remember { mutableStateOf<JsonObject?>(null) }
    var monitorError by remember { mutableStateOf<String?>(null) }
    var healthError by remember { mutableStateOf<String?>(null) }
    var updatedAt by remember { mutableStateOf("—") }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        runCatching { ServerMonitor.snapshot() }.onSuccess {
            monitor = it
            monitorError = null
        }.onFailure {
            monitorError = it.message ?: "本机状态读取失败"
        }
        runCatching { api.health() }.onSuccess {
            health = it
            healthError = null
        }.onFailure {
            healthError = it.message ?: "后端健康检查失败"
        }
        updatedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    }

    LaunchedEffect(Unit) {
        while (true) {
            refresh()
            delay(3_000)
        }
    }

    val m = monitor
    val h = health
    Column(Modifier.fillMaxSize()) {
        PageHeader("服务器实时状态", "系统性能、Ktor 服务、后端进程与运行时长，每 3 秒刷新") {
            StatusPill("实时监控中", StatusTone.SUCCESS)
            Text("更新于 $updatedAt", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { scope.launch { refresh() } }) {
                Icon(Icons.Outlined.Refresh, null)
                Spacer(Modifier.width(6.dp))
                Text("刷新")
            }
        }

        if (m == null && h == null && monitorError == null && healthError == null) {
            LoadingBlock("正在读取服务器状态…")
            return@Column
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel(Modifier.weight(1f)) {
                Column(Modifier.padding(22.dp)) {
                    SectionTitle(Icons.Outlined.Computer, "系统信息")
                    InfoLine("主机名", m?.hostname ?: "—")
                    InfoLine("操作系统", "${m?.osName ?: "—"} ${m?.osVersion ?: ""}".trim())
                    InfoLine("CPU", "${m?.cpuModel ?: "—"} · ${m?.cpuCores ?: "—"} 线程")
                    InfoLine("系统运行时长", formatDuration(m?.osUptimeSeconds))
                    monitorError?.let { WarningLine(it) }
                }
            }
            Panel(Modifier.weight(1f)) {
                Column(Modifier.padding(22.dp)) {
                    SectionTitle(Icons.Outlined.Speed, "实时性能")
                    if (m == null) {
                        Text("等待系统数据…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        UsageBar("CPU 负载", m.cpuLoad, null)
                        UsageBar("内存", 1.0 - m.freeRamBytes.toDouble() / m.totalRamBytes.coerceAtLeast(1), m.totalRamBytes - m.freeRamBytes to m.totalRamBytes)
                        UsageBar("系统盘", 1.0 - m.diskFreeBytes.toDouble() / m.diskTotalBytes.coerceAtLeast(1), m.diskTotalBytes - m.diskFreeBytes to m.diskTotalBytes)
                        UsageBar("总控端 JVM 堆", m.adminHeapUsedBytes.toDouble() / m.adminHeapMaxBytes.coerceAtLeast(1), m.adminHeapUsedBytes to m.adminHeapMaxBytes)
                    }
                }
            }
            Panel(Modifier.weight(1f)) {
                Column(Modifier.padding(22.dp)) {
                    SectionTitle(Icons.Outlined.Cloud, "Ktor / 依赖组件")
                    val backendUp = h.str("backend", "down").equals("up", true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(if (backendUp) "Ktor 8080 正常" else "Ktor 8080 异常", if (backendUp) StatusTone.SUCCESS else StatusTone.ERROR)
                        Spacer(Modifier.width(8.dp))
                        StatusPill("Redis ${h.str("redis", "—")}", if (h.str("redis", "down") == "up") StatusTone.SUCCESS else StatusTone.ERROR)
                        Spacer(Modifier.width(8.dp))
                        StatusPill("OBS ${h.str("obs", "—")}", if (h.str("obs", "down") == "up") StatusTone.SUCCESS else StatusTone.ERROR)
                    }
                    Spacer(Modifier.height(14.dp))
                    InfoLine("Ktor JVM 已用内存", "${h.long("jvmUsedMb")} MB")
                    InfoLine("服务器时间", h.str("time"))
                    InfoLine("管理接口状态", if (healthError == null) "API 接入正常" else "API 接入异常")
                    healthError?.let { WarningLine(it) }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel(Modifier.weight(1.35f)) {
                Column(Modifier.padding(22.dp)) {
                    SectionTitle(Icons.Outlined.MonitorHeart, "后端服务进程")
                    if (m?.backendPid == null) {
                        Text("未检测到本机 kewu-8080.jar 进程", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "如总控端不在服务器本机运行，进程指标不可用；Ktor 健康仍以右侧管理接口为准。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        InfoLine("进程 PID", m.backendPid.toString())
                        InfoLine("启动时间", formatEpoch(m.backendStartedAtEpochMs))
                        InfoLine("已运行", formatDuration(m.backendUptimeSeconds))
                        InfoLine("内存工作集", formatBytes(m.backendWorkingSetBytes ?: 0L))
                        InfoLine("累计 CPU", formatCpuSeconds(m.backendCpuSeconds))
                    }
                }
            }
            Panel(Modifier.weight(1f)) {
                Column(Modifier.padding(22.dp)) {
                    SectionTitle(Icons.Outlined.Memory, "内存明细")
                    InfoLine("物理内存", "${formatBytes(m?.freeRamBytes ?: 0L)} 可用 / ${formatBytes(m?.totalRamBytes ?: 0L)}")
                    InfoLine("总控端堆内存", "${formatBytes(m?.adminHeapUsedBytes ?: 0L)} / ${formatBytes(m?.adminHeapMaxBytes ?: 0L)}")
                    InfoLine("后端工作集", formatBytes(m?.backendWorkingSetBytes ?: 0L))
                    InfoLine("总控端进程 CPU", formatPercentValue(m?.adminProcessCpuLoad))
                }
            }
            Panel(Modifier.weight(1f)) {
                Column(Modifier.padding(22.dp)) {
                    SectionTitle(Icons.Outlined.Storage, "磁盘与采样")
                    InfoLine("当前系统盘", currentDriveLabel(m))
                    InfoLine("磁盘剩余", formatBytes(m?.diskFreeBytes ?: 0L))
                    InfoLine("磁盘总量", formatBytes(m?.diskTotalBytes ?: 0L))
                    InfoLine("数据采样间隔", "3 秒")
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Timer, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Text(
                            " 总控端持续采集本机指标；后端运行时长来自本机 Java 进程启动时间。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 14.dp)) {
        Box(
            Modifier.size(38.dp).background(KewuBlue.copy(alpha = 0.11f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = KewuBlue, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(112.dp), style = MaterialTheme.typography.bodyMedium)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun WarningLine(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun UsageBar(label: String, ratio: Double?, values: Pair<Long, Long>?) {
    val safeRatio = ratio?.coerceIn(0.0, 1.0) ?: 0.0
    val color = when {
        safeRatio >= 0.88 -> AdminRed
        safeRatio >= 0.70 -> AdminAmber
        else -> KewuBlue
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                values?.let { "${formatBytes(it.first)} / ${formatBytes(it.second)}" } ?: formatPercentValue(safeRatio),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(7.dp))
        LinearProgressIndicator(
            progress = { safeRatio.toFloat() },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

private fun formatDuration(seconds: Long?): String {
    if (seconds == null || seconds < 0L) return "—"
    val days = seconds / 86_400L
    val hours = seconds % 86_400L / 3_600L
    val minutes = seconds % 3_600L / 60L
    val secs = seconds % 60L
    return if (days > 0L) "%d 天 %02d:%02d:%02d".format(days, hours, minutes, secs)
    else "%02d:%02d:%02d".format(hours, minutes, secs)
}

private fun formatCpuSeconds(value: Double?): String = if (value == null) "—" else "%.1f 秒".format(value)

private fun formatPercentValue(value: Double?): String = if (value == null) "—" else "%.0f%%".format(value * 100.0)

private fun formatEpoch(epochMs: Long?): String {
    if (epochMs == null) return "—"
    val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
    return time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
}

private fun currentDriveLabel(snapshot: ServerRuntimeSnapshot?): String {
    if (snapshot == null) return "—"
    val cwd = java.io.File(".").absolutePath
    return cwd.take(3).trimEnd('\\', '/') + ":"
}
