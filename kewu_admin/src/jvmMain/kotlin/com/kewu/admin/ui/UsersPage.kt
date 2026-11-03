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
import androidx.compose.material.icons.outlined.Add
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kewu.admin.AdminApi
import com.kewu.admin.PageResult
import com.kewu.admin.jsonBody
import com.kewu.admin.long
import com.kewu.admin.obj
import com.kewu.admin.roleLabel
import com.kewu.admin.statusLabel
import com.kewu.admin.str
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

@Composable
fun UsersPage(api: AdminApi, notify: (String) -> Unit) {
    var result by remember { mutableStateOf(PageResult(emptyList(), 0, 1, 20)) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(1) }
    var keyword by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<JsonObject?>(null) }
    var resetTarget by remember { mutableStateOf<JsonObject?>(null) }
    var deleteTarget by remember { mutableStateOf<JsonObject?>(null) }
    var toggleTarget by remember { mutableStateOf<JsonObject?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        loading = true
        error = null
        try {
            result = api.page(
                "/api/admin/users",
                mapOf("page" to page.toString(), "size" to "20", "keyword" to keyword, "role" to role, "status" to status),
            )
        } catch (e: Exception) {
            error = e.message ?: "用户列表加载失败"
        } finally {
            loading = false
        }
    }

    fun runAction(message: String, close: () -> Unit = {}, block: suspend () -> Unit) {
        scope.launch {
            working = true
            try {
                block()
                close()
                notify(message)
                reload()
            } catch (e: Exception) {
                notify(e.message ?: "操作失败")
            } finally {
                working = false
            }
        }
    }

    LaunchedEffect(page, role, status) { reload() }

    Column(Modifier.fillMaxSize()) {
        PageHeader("用户管理", "查询账号档案，分配角色，禁用账号或重置密码") {
            OutlinedButton(onClick = { scope.launch { reload() } }) { Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(6.dp)); Text("刷新") }
            Button(onClick = { creating = true }) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("新建用户") }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SearchField(keyword, { keyword = it }, "搜索账号或昵称", Modifier.weight(1f))
            OptionSelector(role, listOf("" to "全部角色", "STUDENT" to "学生", "TEACHER" to "教师", "ADMIN" to "管理员")) { role = it; page = 1 }
            OptionSelector(status, listOf("" to "全部状态", "active" to "正常", "disabled" to "已禁用")) { status = it; page = 1 }
            Button(onClick = { page = 1; scope.launch { reload() } }, modifier = Modifier.height(48.dp)) { Text("查询") }
        }
        when {
            loading && result.items.isEmpty() -> LoadingBlock()
            error != null && result.items.isEmpty() -> ErrorBlock(error ?: "加载失败") { scope.launch { reload() } }
            result.items.isEmpty() -> EmptyBlock("没有找到用户")
            else -> DataTable(
                items = result.items,
                columns = listOf(TableColumn("账号", 1.1f), TableColumn("昵称", 1.2f), TableColumn("角色", .8f), TableColumn("状态", .8f), TableColumn("用户 ID", 1.7f), TableColumn("操作", .7f)),
                modifier = Modifier.weight(1f),
                key = { it.str("id", "") },
                onRowClick = { editing = it },
            ) { user, column ->
                when (column.title) {
                    "账号" -> TableText(user.str("username"), bold = true)
                    "昵称" -> TableText(user.str("nickname"))
                    "角色" -> StatusPill(roleLabel(user.str("role", "")), if (user.str("role", "") == "ADMIN") StatusTone.INFO else StatusTone.NEUTRAL)
                    "状态" -> StatusPill(statusLabel(user.str("status", "active")), if (user.str("status", "active").equals("active", true)) StatusTone.SUCCESS else StatusTone.ERROR)
                    "用户 ID" -> TableText(user.str("id"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> TextButton(onClick = { editing = user }) { Text("管理") }
                }
            }
        }
        PaginationBar(result.page, result.size, result.total) { page = it }
    }

    if (creating) {
        UserEditorDialog(null, working, { creating = false }) { username, password, nickname, selectedRole, _ ->
            runAction("用户创建成功", { creating = false }) {
                api.create("/api/admin/users", jsonBody("username" to username, "password" to password, "nickname" to nickname, "role" to selectedRole))
            }
        }
    }
    editing?.let { user ->
        UserEditorDialog(user, working, { editing = null }, api = api, onToggle = { toggleTarget = user }, onReset = { resetTarget = user }, onDelete = { deleteTarget = user }) { _, _, nickname, selectedRole, selectedStatus ->
            runAction("用户资料已保存", { editing = null }) {
                api.update("/api/admin/users/${user.str("id", "")}", jsonBody("nickname" to nickname, "role" to selectedRole, "status" to selectedStatus))
            }
        }
    }
    resetTarget?.let { user ->
        ResetPasswordDialog(working, onDismiss = { resetTarget = null }) { password ->
            runAction("密码已重置", { resetTarget = null; editing = null }) {
                api.action("/api/admin/users/${user.str("id", "")}/reset-password", jsonBody("newPassword" to password))
            }
        }
    }
    toggleTarget?.let { user ->
        val disable = user.str("status", "active").equals("active", true)
        ConfirmDialog(
            if (disable) "禁用账号" else "启用账号",
            "确定要${if (disable) "禁用" else "启用"}「${user.str("nickname")}」吗？${if (disable) "禁用后该账号将无法继续登录。" else ""}",
            if (disable) "确认禁用" else "确认启用",
            disable,
            onConfirm = {
                runAction(if (disable) "账号已禁用" else "账号已启用", { toggleTarget = null; editing = null }) {
                    api.action("/api/admin/users/${user.str("id", "")}/${if (disable) "disable" else "enable"}")
                }
            },
            onDismiss = { toggleTarget = null },
        )
    }
    deleteTarget?.let { user ->
        ConfirmDialog("删除用户", "确定删除「${user.str("nickname")}」吗？后端按软删除处理，但建议优先禁用账号。", "确认删除", true,
            onConfirm = {
                runAction("用户已删除", { deleteTarget = null; editing = null }) { api.remove("/api/admin/users/${user.str("id", "")}") }
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun UserEditorDialog(
    user: JsonObject?,
    working: Boolean,
    onDismiss: () -> Unit,
    api: AdminApi? = null,
    onToggle: () -> Unit = {},
    onReset: () -> Unit = {},
    onDelete: () -> Unit = {},
    onSubmit: (String, String, String, String, String) -> Unit,
) {
    var username by remember { mutableStateOf(user.str("username", "")) }
    var password by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf(user.str("nickname", "")) }
    var role by remember { mutableStateOf(user.str("role", "STUDENT")) }
    var status by remember { mutableStateOf(user.str("status", "active")) }
    var detail by remember { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(user) {
        if (user != null && api != null) detail = runCatching { api.detail("/api/admin/users/${user.str("id", "")}") }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (user == null) "新建用户" else "管理用户") },
        text = {
            Column {
                if (user == null) {
                    OutlinedTextField(username, { username = it }, label = { Text("账号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(password, { password = it }, label = { Text("初始密码（至少 6 位）") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                } else {
                    Text("${user.str("username")} · ${user.str("id")}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    detail?.let { raw ->
                        val stats = raw.obj("stats") ?: raw
                        Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            Text("课程 ${stats.long("courseCount")}")
                            Text("提交 ${stats.long("submissionCount")}")
                            Text("录音 ${stats.long("recordingCount")}")
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(nickname, { nickname = it }, label = { Text("昵称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OptionSelector(role, listOf("STUDENT" to "学生", "TEACHER" to "教师", "ADMIN" to "管理员")) { role = it }
                    if (user != null) OptionSelector(status, listOf("active" to "正常", "disabled" to "已禁用")) { status = it }
                }
                if (user != null) {
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onReset) { Text("重置密码") }
                        TextButton(onClick = onToggle) { Text(if (status.equals("active", true)) "禁用账号" else "启用账号") }
                        TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !working && nickname.isNotBlank() && (user != null || (username.isNotBlank() && password.length >= 6)),
                onClick = { onSubmit(username, password, nickname, role, status) },
            ) { Text(if (working) "处理中…" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ResetPasswordDialog(working: Boolean, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重置密码") },
        text = { OutlinedTextField(password, { password = it }, label = { Text("新密码（至少 6 位）") }, singleLine = true, visualTransformation = PasswordVisualTransformation()) },
        confirmButton = { Button(enabled = password.length >= 6 && !working, onClick = { onSubmit(password) }) { Text("确认重置") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
