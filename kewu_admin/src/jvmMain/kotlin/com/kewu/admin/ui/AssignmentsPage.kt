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
import androidx.compose.material3.OutlinedTextField
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
import com.kewu.admin.long
import com.kewu.admin.statusLabel
import com.kewu.admin.str
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun AssignmentsPage(api: AdminApi, notify: (String) -> Unit) {
    var assignments by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var submissions by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var selected by remember { mutableStateOf<JsonObject?>(null) }
    var detail by remember { mutableStateOf<JsonObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var submissionLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var courseId by remember { mutableStateOf("") }
    var keyword by remember { mutableStateOf("") }
    var reviewTarget by remember { mutableStateOf<JsonObject?>(null) }
    var deleteTarget by remember { mutableStateOf<JsonObject?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        loading = true
        error = null
        try {
            assignments = api.list("/api/admin/assignments", mapOf("courseId" to courseId, "keyword" to keyword))
            selected = assignments.firstOrNull { it.str("id") == selected?.str("id") } ?: assignments.firstOrNull()
        } catch (e: Exception) {
            error = e.message ?: "作业加载失败"
        } finally {
            loading = false
        }
    }
    suspend fun loadSubmissions() {
        val assignment = selected ?: return
        submissionLoading = true
        try {
            detail = api.detail("/api/admin/assignments/${assignment.str("id", "")}")
            submissions = api.list("/api/admin/assignments/${assignment.str("id", "")}/submissions")
        } catch (e: Exception) {
            notify(e.message ?: "提交记录加载失败")
        } finally {
            submissionLoading = false
        }
    }
    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(selected?.str("id", "")) { if (selected != null) loadSubmissions() }

    Column(Modifier.fillMaxSize()) {
        PageHeader("作业与批阅", "跨课程查看作业与提交，必要时人工覆盖批阅结果") {
            OutlinedButton(onClick = { scope.launch { reload(); loadSubmissions() } }) { Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(6.dp)); Text("刷新") }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SearchField(courseId, { courseId = it }, "课程 ID（可选）", Modifier.weight(1f))
            SearchField(keyword, { keyword = it }, "搜索作业标题", Modifier.weight(1.5f))
            Button(onClick = { scope.launch { reload() } }, modifier = Modifier.height(48.dp)) { Text("查询") }
        }
        when {
            loading && assignments.isEmpty() -> LoadingBlock()
            error != null && assignments.isEmpty() -> ErrorBlock(error ?: "加载失败") { scope.launch { reload() } }
            assignments.isEmpty() -> Panel(Modifier.weight(1.1f)) { EmptyBlock("暂无作业") }
            else -> DataTable(
                assignments,
                listOf(TableColumn("作业标题", 1.7f), TableColumn("课程", 1f), TableColumn("题目数", .6f), TableColumn("提交数", .6f), TableColumn("发布时间", 1.1f), TableColumn("操作", .7f)),
                Modifier.weight(1.05f),
                key = { it.str("id", "") },
                onRowClick = { selected = it },
            ) { assignment, column ->
                when (column.title) {
                    "作业标题" -> TableText(if (selected?.str("id") == assignment.str("id")) "● ${assignment.str("title")}" else assignment.str("title"), true, if (selected?.str("id") == assignment.str("id")) KewuBlue else MaterialTheme.colorScheme.onSurface)
                    "课程" -> TableText(assignment.str("courseName", assignment.str("courseId")))
                    "题目数" -> TableText(assignment.long("questionCount").toString())
                    "提交数" -> TableText(assignment.long("submissionCount").toString())
                    "发布时间" -> TableText(assignment.str("createdAt"))
                    else -> TextButton(onClick = { deleteTarget = assignment }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("提交与批阅", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    detail?.let { "${it.str("title")} · 已批 ${it.long("reviewedCount")} / 待批 ${it.long("pendingCount")} / 均分 ${it.str("avgScore")}" }
                        ?: selected?.let { "当前作业：${it.str("title")}" } ?: "请选择作业",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        when {
            selected == null -> Panel(Modifier.weight(1f)) { EmptyBlock("请选择作业") }
            submissionLoading && submissions.isEmpty() -> LoadingBlock("正在加载提交记录…")
            submissions.isEmpty() -> Panel(Modifier.weight(1f)) { EmptyBlock("该作业暂无提交") }
            else -> DataTable(
                submissions,
                listOf(TableColumn("学生", 1.2f), TableColumn("版本", .6f), TableColumn("状态", .9f), TableColumn("得分", .7f), TableColumn("提交时间", 1.2f), TableColumn("提交 ID", 1.2f), TableColumn("操作", .8f)),
                Modifier.weight(1f),
                key = { it.str("id", "") },
            ) { submission, column ->
                when (column.title) {
                    "学生" -> TableText("${submission.str("studentName")}（${submission.str("studentId")}）", bold = true)
                    "版本" -> TableText("v${submission.long("version", 1)}")
                    "状态" -> StatusPill(statusLabel(submission.str("status", "")), if (submission.str("status", "") == "REVIEWED") StatusTone.SUCCESS else StatusTone.WARNING)
                    "得分" -> TableText(submission.str("score"))
                    "提交时间" -> TableText(submission.str("submittedAt"))
                    "提交 ID" -> TableText(submission.str("id"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> TextButton(onClick = { reviewTarget = submission }) { Text("批阅") }
                }
            }
        }
    }

    reviewTarget?.let { submission ->
        ReviewDialog(submission, { reviewTarget = null }) { score, comment, reviewer, action ->
            scope.launch {
                try {
                    val body = buildJsonObject {
                        if (score.isNotBlank()) put("score", JsonPrimitive(score.toInt()))
                        put("comment", comment)
                        put("reviewer", reviewer)
                        put("action", action)
                    }
                    api.update("/api/admin/submissions/${submission.str("id", "")}/review", body)
                    reviewTarget = null
                    notify("批阅结果已保存")
                    loadSubmissions()
                } catch (e: Exception) {
                    notify(e.message ?: "批阅失败")
                }
            }
        }
    }
    deleteTarget?.let { assignment ->
        ConfirmDialog("删除作业", "确定删除「${assignment.str("title")}」吗？提交记录会保留，但与作业的关联将断开。", "确认删除", true,
            onConfirm = {
                scope.launch {
                    try {
                        api.remove("/api/admin/assignments/${assignment.str("id", "")}")
                        deleteTarget = null
                        notify("作业已删除")
                        reload()
                    } catch (e: Exception) { notify(e.message ?: "删除失败") }
                }
            },
            onDismiss = { deleteTarget = null })
    }
}

@Composable
private fun ReviewDialog(submission: JsonObject, onDismiss: () -> Unit, onSubmit: (String, String, String, String) -> Unit) {
    var score by remember { mutableStateOf(submission.str("score", "")) }
    var comment by remember { mutableStateOf(submission.str("comment", "")) }
    var reviewer by remember { mutableStateOf(submission.str("reviewer", "admin")) }
    var action by remember { mutableStateOf("ACCEPT") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("人工批阅 · ${submission.str("studentName")}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("提交 ${submission.str("id")} · v${submission.long("version", 1)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(score, { score = it.filter(Char::isDigit) }, label = { Text("分数") }, singleLine = true)
                OutlinedTextField(comment, { comment = it }, label = { Text("评语") }, minLines = 3)
                OutlinedTextField(reviewer, { reviewer = it }, label = { Text("批阅人") }, singleLine = true)
                OptionSelector(action, listOf("ACCEPT" to "定分并完成批阅", "RETURN" to "打回重做")) { action = it }
            }
        },
        confirmButton = { Button(enabled = score.isNotBlank() && reviewer.isNotBlank(), onClick = { onSubmit(score, comment, reviewer, action) }) { Text("保存批阅") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
