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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kewu.admin.AdminApi
import com.kewu.admin.PageResult
import com.kewu.admin.double
import com.kewu.admin.formatBytes
import com.kewu.admin.long
import com.kewu.admin.roleLabel
import com.kewu.admin.str
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.awt.Desktop
import java.net.URI

@Composable
fun RecordingsPage(api: AdminApi, notify: (String) -> Unit) {
    var result by remember { mutableStateOf(PageResult(emptyList(), 0, 1, 1000)) }
    var merged by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var obsError by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(1) }
    var keyword by remember { mutableStateOf("") }
    var classId by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<JsonObject?>(null) }
    var deleteTarget by remember { mutableStateOf<JsonObject?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        loading = true
        error = null
        obsError = null
        var dbItems = result.items
        var dbFailed: String? = null
        try {
            result = api.page(
                "/api/admin/recordings",
                mapOf("page" to "1", "size" to "1000", "keyword" to keyword, "classId" to classId, "role" to role),
            )
            dbItems = result.items
        } catch (e: Exception) {
            dbFailed = e.message ?: "录音数据库列表加载失败"
            error = dbFailed
        }

        val obsResult = runCatching { api.obsRecordings() }
        val obsItems = obsResult.getOrElse {
            obsError = it.message ?: "OBS 录音列表读取失败"
            emptyList()
        }
        merged = mergeRecordings(dbItems, obsItems)
        if (dbFailed != null && obsItems.isNotEmpty()) {
            // 数据库异常但 OBS 可读时仍展示 OBS 对象，同时在页面给出警告。
            error = null
        }
        loading = false
    }
    LaunchedEffect(role) { page = 1; reload() }

    val visible = merged.filter { recording ->
        val searchText = keyword.trim().lowercase()
        val keywordOk = searchText.isBlank() || listOf(
            recording.str("id", recording.str("recordingId")),
            recording.str("studentName"),
            recording.str("deviceId"),
            recording.str("obsKey"),
        ).any { it.lowercase().contains(searchText) }
        val classOk = classId.isBlank() || listOf(
            recording.str("classId"), recording.str("courseId"), recording.str("sessionId"), recording.str("obsKey"),
        ).any { it.contains(classId, true) }
        val roleOk = role.isBlank() || recording.str("role", "").equals(role, true)
        keywordOk && classOk && roleOk
    }
    val pageItems = visible.drop((page - 1).coerceAtLeast(0) * 20).take(20)

    Column(Modifier.fillMaxSize()) {
        PageHeader("录音资源", "查看课堂录音质量，试听、下载或软删除录音") {
            OutlinedButton(onClick = { scope.launch { reload() } }) { Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(6.dp)); Text("刷新") }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SearchField(keyword, { keyword = it }, "搜索 ID / 学生 / 对象键", Modifier.weight(1f))
            SearchField(classId, { classId = it }, "班级 / 课堂 / 会话 ID", Modifier.weight(1f))
            OptionSelector(role, listOf("" to "全部来源", "teacher" to "教师端", "student" to "学生端")) { role = it; page = 1 }
            Button(onClick = { page = 1; scope.launch { reload() } }, modifier = Modifier.height(48.dp)) { Text("查询") }
        }
        Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill("合并后 ${visible.size} 条", StatusTone.INFO)
            StatusPill("OBS 对象 ${merged.count { it.str("source") == "OBS" || it.str("source") == "数据库 + OBS" }} 条", StatusTone.SUCCESS)
            obsError?.let { StatusPill("OBS 读取异常", StatusTone.WARNING) }
        }
        when {
            loading && visible.isEmpty() -> LoadingBlock()
            error != null && visible.isEmpty() -> ErrorBlock(error ?: "加载失败") { scope.launch { reload() } }
            visible.isEmpty() -> EmptyBlock("暂无录音", "数据库和华为云 OBS 桶均未找到符合条件的录音")
            else -> DataTable(
                pageItems,
                listOf(TableColumn("录音 ID", 1.4f), TableColumn("学生 / 设备", 1.2f), TableColumn("班级", 1f), TableColumn("来源", .7f), TableColumn("质量分", .7f), TableColumn("时长", .7f), TableColumn("上传时间", 1.2f), TableColumn("操作", .6f)),
                Modifier.weight(1f),
                key = { it.str("id", it.str("recordingId", "")) + ":" + it.str("source") },
                onRowClick = { selected = it },
            ) { recording, column ->
                when (column.title) {
                    "录音 ID" -> TableText(recording.str("id", recording.str("recordingId")), bold = true)
                    "学生 / 设备" -> TableText(recording.str("studentName", recording.str("deviceId", if (recording.str("source") == "OBS") "未落库对象" else "")))
                    "班级" -> TableText(recording.str("classId", recording.str("courseId")))
                    "来源" -> {
                        val source = recording.str("source")
                        when {
                            source == "OBS" -> StatusPill("OBS", StatusTone.INFO)
                            source == "数据库 + OBS" -> StatusPill("数据库 + OBS", StatusTone.SUCCESS)
                            else -> StatusPill(roleLabel(recording.str("role", "")), if (recording.str("role", "") == "teacher") StatusTone.INFO else StatusTone.NEUTRAL)
                        }
                    }
                    "质量分" -> {
                        val score = recording.double("score")
                        TableText(if (score > 0.0) "${(score * if (score <= 1.0) 100 else 1).toInt()} 分" else "—")
                    }
                    "时长" -> {
                        val duration = recording.double("durationSec")
                        TableText(if (duration > 0.0) "${duration.toInt()} 秒" else formatBytes(recording.long("sizeBytes")))
                    }
                    "上传时间" -> TableText(recording.str("createdAt", recording.str("storedAt", recording.str("lastModified"))))
                    else -> TextButton(onClick = { selected = recording }) { Text("详情") }
                }
            }
        }
        obsError?.let {
            Text("OBS 补充读取失败：$it（已显示数据库记录）", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        PaginationBar(page, 20, visible.size) { page = it }
    }

    selected?.let { item ->
        RecordingDetailDialog(api, item, onDismiss = { selected = null }, onDelete = { deleteTarget = item })
    }
    deleteTarget?.let { item ->
        ConfirmDialog("删除录音", "确定删除录音 ${item.str("id", item.str("recordingId"))} 吗？列表将隐藏该记录，OBS 原文件保留以便追溯。", "确认删除", true,
            onConfirm = {
                scope.launch {
                    working = true
                    try {
                        api.remove("/api/admin/recordings/${item.str("id", item.str("recordingId"))}")
                        deleteTarget = null
                        selected = null
                        notify("录音已删除")
                        reload()
                    } catch (e: Exception) {
                        notify(e.message ?: "删除失败")
                    } finally {
                        working = false
                    }
                }
            },
            onDismiss = { if (!working) deleteTarget = null },
        )
    }
}

@Composable
private fun RecordingDetailDialog(api: AdminApi, item: JsonObject, onDismiss: () -> Unit, onDelete: () -> Unit) {
    var detail by remember { mutableStateOf(item) }
    val id = item.str("id", item.str("recordingId"))
    val obsOnly = item.str("source") == "OBS"
    LaunchedEffect(id, obsOnly) {
        if (!obsOnly) {
            runCatching { api.detail("/api/admin/recordings/$id") }.onSuccess { dbDetail ->
                detail = if (item.str("source") == "数据库 + OBS") {
                    mergeRecordings(listOf(dbDetail), listOf(item)).firstOrNull() ?: dbDetail
                } else dbDetail
            }
        }
    }
    val url = detail.str("playUrl", detail.str("url", detail.str("storedUrl", "")))
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("录音详情") },
        text = {
            Column {
                DetailRow("录音 ID", detail.str("id", detail.str("recordingId")))
                DetailRow("存储来源", when (detail.str("source")) {
                    "OBS" -> "仅华为云 OBS 对象（暂未落库）"
                    "数据库 + OBS" -> "MySQL 元数据 + OBS 对象"
                    else -> "MySQL 数据库"
                })
                if (detail.str("obsKey").isNotBlank()) DetailRow("OBS 对象", detail.str("obsKey"))
                if (detail.long("sizeBytes") > 0) DetailRow("对象大小", formatBytes(detail.long("sizeBytes")))
                DetailRow("学生", detail.str("studentName"))
                DetailRow("班级 / 课程", "${detail.str("classId")} / ${detail.str("courseId")}")
                DetailRow("会话 ID", detail.str("sessionId"))
                DetailRow("设备", detail.str("deviceId"))
                DetailRow("来源", roleLabel(detail.str("role", "")))
                DetailRow("质量分", "${detail.double("score")}")
                DetailRow("平均电平", "${detail.str("avgLevelDb")} dB")
                DetailRow("信噪比", "${detail.str("snrDb")} dB")
                DetailRow("时长", "${detail.double("durationSec")} 秒")
                DetailRow("格式", detail.str("format"))
                DetailRow("上传时间", detail.str("createdAt", detail.str("storedAt")))
                if (url.isNotBlank()) {
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { openUrl(url) }) { Text("试听") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                val downloadUrl = runCatching {
                                    api.detail("/api/admin/recordings/$id/download").str("url", url)
                                }.getOrDefault(url)
                                if (downloadUrl.isNotBlank()) openUrl(downloadUrl)
                            }
                        }) { Text("下载") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = if (obsOnly) null else ({
            TextButton(onClick = onDelete) { Text("删除录音", color = MaterialTheme.colorScheme.error) }
        }),
    )
}

private fun mergeRecordings(dbItems: List<JsonObject>, obsItems: List<JsonObject>): List<JsonObject> {
    val dbById = dbItems.associateBy { it.str("id", it.str("recordingId")) }
    val obsById = obsItems.associateBy { it.str("id") }
    val merged = obsItems.mapNotNull { obs ->
        val id = obs.str("id")
        if (id.isBlank()) return@mapNotNull null
        val db = dbById[id]
        if (db == null) {
            obs
        } else {
            val fields = db.toMutableMap()
            val dbUrl = (db["url"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            if (dbUrl == null) fields["url"] = obs["url"] ?: JsonPrimitive("")
            fields["obsKey"] = obs["obsKey"] ?: JsonPrimitive("")
            fields["lastModified"] = obs["lastModified"] ?: JsonPrimitive("")
            if (db.long("sizeBytes") <= 0L) fields["sizeBytes"] = obs["sizeBytes"] ?: JsonPrimitive(0L)
            fields["source"] = JsonPrimitive("数据库 + OBS")
            JsonObject(fields)
        }
    }
    val mergedIds = obsById.keys
    val dbOnly = dbItems.filter { !mergedIds.contains(it.str("id", it.str("recordingId"))) }
    return (merged + dbOnly).sortedByDescending { it.str("createdAt", it.str("lastModified", it.str("storedAt"))) }
}

private fun openUrl(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url))
    }
}
