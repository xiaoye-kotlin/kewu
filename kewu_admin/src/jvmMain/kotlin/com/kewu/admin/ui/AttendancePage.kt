package com.kewu.admin.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.kewu.admin.array
import com.kewu.admin.formatPercent
import com.kewu.admin.long
import com.kewu.admin.double
import com.kewu.admin.obj
import com.kewu.admin.statusLabel
import com.kewu.admin.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import javax.swing.JFileChooser

@Composable
fun AttendancePage(api: AdminApi, notify: (String) -> Unit) {
    var sessions by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var courseId by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<JsonObject?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        loading = true
        error = null
        try {
            sessions = api.list("/api/admin/attendance", mapOf("courseId" to courseId, "from" to from, "to" to to))
        } catch (e: Exception) {
            error = e.message ?: "签到记录加载失败"
        } finally {
            loading = false
        }
    }
    fun export(item: JsonObject) {
        scope.launch {
            try {
                val csv = api.exportAttendance(item.str("id", ""))
                val chooser = JFileChooser().apply {
                    dialogTitle = "导出签到 CSV"
                    selectedFile = File("kewu-attendance-${item.str("id", "export")}.csv")
                }
                if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                    withContext(Dispatchers.IO) {
                        chooser.selectedFile.writeText("﻿$csv", Charsets.UTF_8)
                    }
                    notify("签到记录已导出到 ${chooser.selectedFile.absolutePath}")
                }
            } catch (e: Exception) {
                notify(e.message ?: "导出失败")
            }
        }
    }
    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize()) {
        PageHeader("签到管理", "查看签到率、到课明细与风险分，导出 CSV 报表") {
            OutlinedButton(onClick = { scope.launch { reload() } }) { Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(6.dp)); Text("刷新") }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SearchField(courseId, { courseId = it }, "课程 ID", Modifier.weight(1f))
            SearchField(from, { from = it }, "开始日期 YYYY-MM-DD", Modifier.weight(1f))
            SearchField(to, { to = it }, "结束日期 YYYY-MM-DD", Modifier.weight(1f))
            Button(onClick = { scope.launch { reload() } }, modifier = Modifier.height(48.dp)) { Text("查询") }
        }
        when {
            loading && sessions.isEmpty() -> LoadingBlock()
            error != null && sessions.isEmpty() -> ErrorBlock(error ?: "加载失败") { scope.launch { reload() } }
            sessions.isEmpty() -> EmptyBlock("暂无签到会话")
            else -> DataTable(
                sessions,
                listOf(TableColumn("签到主题", 1.6f), TableColumn("课程", 1f), TableColumn("状态", .8f), TableColumn("实到 / 应到", .9f), TableColumn("签到率", .7f), TableColumn("发起时间", 1.1f), TableColumn("操作", 1f)),
                Modifier.weight(1f),
                key = { it.str("id", "") },
                onRowClick = { selected = it },
            ) { item, column ->
                when (column.title) {
                    "签到主题" -> TableText(item.str("title"), bold = true)
                    "课程" -> TableText(item.str("courseName", item.str("courseId")))
                    "状态" -> StatusPill(statusLabel(item.str("state", "")), if (item.str("state", "") == "OPEN") StatusTone.SUCCESS else StatusTone.NEUTRAL)
                    "实到 / 应到" -> TableText("${item.long("checked")} / ${item.long("expected")}")
                    "签到率" -> TableText(formatPercent(item.double("checkRate")))
                    "发起时间" -> TableText(item.str("createdAt"))
                    else -> Row {
                        TextButton(onClick = { selected = item }) { Text("明细") }
                        TextButton(onClick = { export(item) }) { Text("导出") }
                    }
                }
            }
        }
    }
    selected?.let { item ->
        AttendanceDetailDialog(api, item, { selected = null }) { export(item) }
    }
}

@Composable
private fun AttendanceDetailDialog(api: AdminApi, item: JsonObject, onDismiss: () -> Unit, onExport: () -> Unit) {
    var detail by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(item) {
        try {
            detail = api.detail("/api/admin/attendance/${item.str("id", "")}")
        } catch (e: Exception) {
            error = e.message
        }
    }
    val session = detail.obj("session") ?: detail ?: item
    val records = detail.array("records")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(session.str("title", "签到明细")) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("实到 ${session.long("checked")} / 应到 ${session.long("expected")}")
                    Text("迟到 ${session.long("late")}")
                    Text("缺勤 ${session.long("absent")}")
                    Text("签到率 ${formatPercent(session.double("checkRate"))}")
                }
                Spacer(Modifier.height(12.dp))
                when {
                    error != null -> Text(error ?: "加载失败", color = MaterialTheme.colorScheme.error)
                    detail == null -> LoadingBlock("正在加载签到明细…")
                    records.isEmpty() -> Text("暂无签到记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(records, key = { it.str("id", it.str("studentId", "")) }) { record ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1.2f)) {
                                    Text(record.str("studentName"), fontWeight = FontWeight.Medium)
                                    Text(record.str("studentId"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                }
                                TableText(record.str("checkedInAt"))
                                StatusPill(statusLabel(record.str("status", "")), if (record.str("status", "") == "LATE") StatusTone.WARNING else StatusTone.SUCCESS)
                                Spacer(Modifier.width(8.dp))
                                Text("风险 ${record.long("riskScore")}", color = if (record.long("riskScore") >= 30) AdminRed else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onExport) { Icon(Icons.Outlined.Download, null); Spacer(Modifier.width(6.dp)); Text("导出 CSV") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
