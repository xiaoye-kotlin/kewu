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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import com.kewu.admin.jsonBody
import com.kewu.admin.long
import com.kewu.admin.str
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

@Composable
fun CoursesPage(api: AdminApi, notify: (String) -> Unit) {
    var courses by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var classes by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var selected by remember { mutableStateOf<JsonObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var classLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editingCourse by remember { mutableStateOf<JsonObject?>(null) }
    var creatingCourse by remember { mutableStateOf(false) }
    var editingClass by remember { mutableStateOf<JsonObject?>(null) }
    var creatingClass by remember { mutableStateOf(false) }
    var studentsClass by remember { mutableStateOf<JsonObject?>(null) }
    var deleteCourse by remember { mutableStateOf<JsonObject?>(null) }
    var deleteClass by remember { mutableStateOf<JsonObject?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reloadCourses() {
        loading = true
        error = null
        try {
            courses = api.list("/api/admin/courses")
            selected = courses.firstOrNull { it.str("id") == selected?.str("id") } ?: courses.firstOrNull()
        } catch (e: Exception) {
            error = e.message ?: "课程加载失败"
        } finally {
            loading = false
        }
    }
    suspend fun reloadClasses() {
        val course = selected ?: return
        classLoading = true
        try {
            classes = api.list("/api/admin/courses/${course.str("id", "")}/classes")
        } catch (e: Exception) {
            notify(e.message ?: "班级加载失败")
        } finally {
            classLoading = false
        }
    }
    fun action(message: String, close: () -> Unit, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                close()
                notify(message)
                reloadCourses()
                reloadClasses()
            } catch (e: Exception) {
                notify(e.message ?: "操作失败")
            }
        }
    }
    LaunchedEffect(Unit) { reloadCourses() }
    LaunchedEffect(selected?.str("id", "")) { if (selected != null) reloadClasses() }

    Column(Modifier.fillMaxSize()) {
        PageHeader("课程与班级", "管理课程资料、班级邀请码和班级学生名单") {
            OutlinedButton(onClick = { scope.launch { reloadCourses(); reloadClasses() } }) { Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(6.dp)); Text("刷新") }
            Button(onClick = { creatingCourse = true }) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("新建课程") }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("全部课程", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${courses.size} 门课程", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            loading && courses.isEmpty() -> LoadingBlock()
            error != null && courses.isEmpty() -> ErrorBlock(error ?: "加载失败") { scope.launch { reloadCourses() } }
            courses.isEmpty() -> Panel(Modifier.weight(1f)) { EmptyBlock("暂无课程") }
            else -> DataTable(
                courses,
                listOf(TableColumn("课程名称", 1.5f), TableColumn("任课教师", 1f), TableColumn("班级数", .7f), TableColumn("学生数", .7f), TableColumn("课程 ID", 1.2f), TableColumn("操作", .7f)),
                Modifier.weight(1.05f),
                key = { it.str("id", "") },
                onRowClick = { selected = it },
            ) { course, column ->
                when (column.title) {
                    "课程名称" -> TableText(if (selected?.str("id") == course.str("id")) "● ${course.str("name")}" else course.str("name"), bold = true, if (selected?.str("id") == course.str("id")) KewuBlue else MaterialTheme.colorScheme.onSurface)
                    "任课教师" -> TableText(course.str("teacher"))
                    "班级数" -> TableText(course.long("classCount").toString())
                    "学生数" -> TableText(course.long("studentCount").toString())
                    "课程 ID" -> TableText(course.str("id"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Row {
                        TextButton(onClick = { editingCourse = course }) { Text("编辑") }
                        TextButton(onClick = { deleteCourse = course }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("班级列表", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(selected?.let { "当前课程：${it.str("name")}" } ?: "请选择课程", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { creatingClass = true }, enabled = selected != null) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("新建班级") }
        }
        when {
            selected == null -> Panel(Modifier.weight(1f)) { EmptyBlock("请选择课程") }
            classLoading && classes.isEmpty() -> LoadingBlock("正在加载班级…")
            classes.isEmpty() -> Panel(Modifier.weight(1f)) { EmptyBlock("该课程暂无班级") }
            else -> DataTable(
                classes,
                listOf(TableColumn("班级名称", 1.4f), TableColumn("学期", 1f), TableColumn("邀请码", .9f), TableColumn("学生数", .7f), TableColumn("班级 ID", 1.2f), TableColumn("操作", 1.2f)),
                Modifier.weight(1f),
                key = { it.str("id", "") },
                onRowClick = { studentsClass = it },
            ) { item, column ->
                when (column.title) {
                    "班级名称" -> TableText(item.str("name"), bold = true)
                    "学期" -> TableText(item.str("term"))
                    "邀请码" -> StatusPill(item.str("inviteCode"), StatusTone.INFO)
                    "学生数" -> TableText(item.long("studentCount").toString())
                    "班级 ID" -> TableText(item.str("id"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Row {
                        TextButton(onClick = { studentsClass = item }) { Text("学生") }
                        TextButton(onClick = { editingClass = item }) { Text("编辑") }
                        TextButton(onClick = { deleteClass = item }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }

    if (creatingCourse) CourseDialog(null, { creatingCourse = false }) { name, description, teacher, teacherId ->
        action("课程创建成功", { creatingCourse = false }) { api.create("/api/admin/courses", jsonBody("name" to name, "description" to description, "teacher" to teacher, "teacherId" to teacherId)) }
    }
    editingCourse?.let { course ->
        CourseDialog(course, { editingCourse = null }) { name, description, teacher, _ ->
            action("课程已保存", { editingCourse = null }) { api.update("/api/admin/courses/${course.str("id", "")}", jsonBody("name" to name, "description" to description, "teacher" to teacher)) }
        }
    }
    if (creatingClass) ClassDialog(null, { creatingClass = false }) { name, term, teacherId ->
        action("班级创建成功", { creatingClass = false }) { api.create("/api/admin/classes", jsonBody("courseId" to selected?.str("id", ""), "name" to name, "term" to term, "teacherId" to teacherId)) }
    }
    editingClass?.let { item ->
        ClassDialog(item, { editingClass = null }) { name, term, teacherId ->
            action("班级已保存", { editingClass = null }) { api.update("/api/admin/classes/${item.str("id", "")}", jsonBody("name" to name, "term" to term, "teacherId" to teacherId)) }
        }
    }
    deleteCourse?.let { course ->
        ConfirmDialog("删除课程", "确定删除「${course.str("name")}」吗？其班级和学生关联会移除，作业、提交与录音保留。", "确认删除", true,
            onConfirm = { action("课程已删除", { deleteCourse = null }) { api.remove("/api/admin/courses/${course.str("id", "")}") } },
            onDismiss = { deleteCourse = null })
    }
    deleteClass?.let { item ->
        ConfirmDialog("删除班级", "确定删除「${item.str("name")}」吗？班级学生关联会同时清除。", "确认删除", true,
            onConfirm = { action("班级已删除", { deleteClass = null }) { api.remove("/api/admin/classes/${item.str("id", "")}") } },
            onDismiss = { deleteClass = null })
    }
    studentsClass?.let { item ->
        ClassStudentsDialog(api, item, { studentsClass = null }, notify) { scope.launch { reloadClasses(); reloadCourses() } }
    }
}

@Composable
private fun CourseDialog(course: JsonObject?, onDismiss: () -> Unit, onSubmit: (String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf(course.str("name", "")) }
    var description by remember { mutableStateOf(course.str("description", "")) }
    var teacher by remember { mutableStateOf(course.str("teacher", "")) }
    var teacherId by remember { mutableStateOf(course.str("teacherId", "")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (course == null) "新建课程" else "编辑课程") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("课程名称 *") }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text("课程简介") }, minLines = 2)
                OutlinedTextField(teacher, { teacher = it }, label = { Text("任课教师") }, singleLine = true)
                if (course == null) OutlinedTextField(teacherId, { teacherId = it }, label = { Text("教师 ID / 账号（可选）") }, singleLine = true)
            }
        },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { onSubmit(name, description, teacher, teacherId) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ClassDialog(item: JsonObject?, onDismiss: () -> Unit, onSubmit: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf(item.str("name", "")) }
    var term by remember { mutableStateOf(item.str("term", "2026 秋")) }
    var teacherId by remember { mutableStateOf(item.str("teacherId", "")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item == null) "新建班级" else "编辑班级") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("班级名称 *") }, singleLine = true)
                OutlinedTextField(term, { term = it }, label = { Text("学期") }, singleLine = true)
                OutlinedTextField(teacherId, { teacherId = it }, label = { Text("教师 ID / 账号（可选）") }, singleLine = true)
            }
        },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { onSubmit(name, term, teacherId) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ClassStudentsDialog(api: AdminApi, item: JsonObject, onDismiss: () -> Unit, notify: (String) -> Unit, onChanged: () -> Unit) {
    var students by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var studentId by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    suspend fun reload() {
        loading = true
        try {
            students = api.list("/api/admin/classes/${item.str("id", "")}/students")
        } catch (e: Exception) {
            notify(e.message ?: "名单加载失败")
        } finally {
            loading = false
        }
    }
    LaunchedEffect(item) { reload() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${item.str("name")} · 学生名单") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(studentId, { studentId = it }, label = { Text("学生 ID") }, singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = studentId.isNotBlank(), onClick = {
                        scope.launch {
                            try {
                                api.action("/api/admin/classes/${item.str("id", "")}/students", jsonBody("studentId" to studentId))
                                studentId = ""
                                notify("学生已加入班级")
                                reload()
                                onChanged()
                            } catch (e: Exception) { notify(e.message ?: "添加失败") }
                        }
                    }) { Text("添加") }
                }
                Spacer(Modifier.height(12.dp))
                when {
                    loading -> LoadingBlock()
                    students.isEmpty() -> Text("班级暂无学生", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 330.dp)) {
                        items(students, key = { it.str("studentId", it.str("id", "")) }) { student ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(student.str("name", student.str("nickname", student.str("studentName"))), fontWeight = FontWeight.Medium)
                                    Text("${student.str("studentId", student.str("id"))} · 入班 ${student.str("joinedAt")}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        try {
                                            api.remove("/api/admin/classes/${item.str("id", "")}/students/${student.str("studentId", student.str("id"))}")
                                            notify("学生已移出班级")
                                            reload()
                                            onChanged()
                                        } catch (e: Exception) { notify(e.message ?: "移除失败") }
                                    }
                                }) { Text("移出", color = MaterialTheme.colorScheme.error) }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}
