package com.kewu.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.ClassRoomInfo
import com.kewu.app.ClassStudent
import com.kewu.app.CourseInfo
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuGreen
import com.kewu.app.TeacherOverview
import com.kewu.app.haptic
import com.kewu.app.hapticClickable
import com.kewu.app.ui.liquidglass.KewuGlassCard
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import kotlinx.coroutines.launch

// ───────────────────────── 课程 / 班级管理（2026-10-21 接入后端第 5 节新接口） ─────────────────────────
//
// 三个界面，消费后端「教师端专属接口」里此前没有 UI 的部分：
//   CreateCourseScreen    → POST /api/teacher/courses        老师新建课程
//   TeacherClassesScreen  → GET  /api/teacher/classes        班级概览 + 邀请码 + 学生名单
//                           POST /api/teacher/classes        新建班级（自动生成 6 位邀请码）
//                           GET  /api/teacher/classes/{id}/students  班级名单
//   JoinClassScreen       → POST /api/classes/join           学生用邀请码加入班级
//
// coui 安全约束：复用外层传入的单一采样层 [backdrop]（玻璃卡只作采样者），不新建自己的
// layerBackdrop 背景；邀请码复制用 LocalClipboardManager（与学生端 TaPage 同款）。

/** 通用输入框（标签 + surfaceVariant 圆角底 + BasicTextField，与 TeacherTools.ToolField 同款语言）。 */
@Composable
private fun FieldBox(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
        Text(label, color = cs.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(cs.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = cs.onSurface, fontSize = 15.sp),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (value.isEmpty()) {
                            Text(placeholder.ifEmpty { label },
                                color = cs.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 15.sp)
                        }
                        inner()
                    }
                },
            )
        }
    }
}

/** 结果提示行（成功=品牌绿，失败=error 语义色）。 */
@Composable
private fun OutcomeLine(msg: String, ok: Boolean) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp)) {
        Text(msg, color = if (ok) KewuGreen else cs.error, fontSize = 13.sp)
    }
}

/** 玻璃说明卡（采样自传入 [backdrop]）。 */
@Composable
private fun IntroGlass(backdrop: LayerBackdrop, title: String, subtitle: String) {
    val cs = MaterialTheme.colorScheme
    KewuGlassCard(
        backdrop = backdrop,
        shape = RoundedCornerShape(20.dp),
        blurRadius = 22.dp,
        tintAlpha = 0.28f,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(20.dp)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = cs.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

private val TERM_OPTIONS = listOf("2026 春", "2026 秋", "2027 春")

// ───────────────────────── 新建课程（教师） ─────────────────────────

/**
 * 新建课程：调 [KewuApi.createTeacherCourse]（POST /api/teacher/courses）。
 * 创建成功后课程立刻出现在 /api/courses，可用于开课、建班、发作业。
 */
@Composable
internal fun CreateCourseScreen(backdrop: LayerBackdrop, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
        HubTopBar("新建课程", onBack)
        IntroGlass(backdrop, "创建一门新课程", "课程创建后立即可发起签到、开设班级并发布作业。")
        FieldBox("课程名称", name, { name = it }, placeholder = "如：云计算基础")
        FieldBox("课程简介", desc, { desc = it }, placeholder = "一句话说明课程内容（可选）")
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                scope.launch {
                    loading = true
                    val r = KewuApi.createTeacherCourse(name.trim(), desc.trim())
                    loading = false
                    result = if (r.data != null) {
                        true to "已创建《${r.data.name}》（ID: ${r.data.id}）"
                    } else {
                        false to (r.error ?: "创建失败，请稍后重试")
                    }
                    if (r.data != null) { name = ""; desc = "" }
                }
            },
            enabled = name.isNotBlank() && !loading,
            colors = ButtonDefaults.buttonColors(
                color = MaterialTheme.colorScheme.primary, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) { Text(if (loading) "正在创建…" else "创建课程", fontWeight = FontWeight.Bold) }
        result?.let { OutcomeLine(it.second, it.first) }
        Spacer(Modifier.height(24.dp))
    }
}

// ───────────────────────── 我的班级（教师：概览 + 建班 + 邀请码 + 名单） ─────────────────────────

private sealed class ClassDrill {
    data object LIST : ClassDrill()
    data class STUDENTS(val cls: ClassRoomInfo) : ClassDrill()
}

/**
 * 班级管理：顶部三数概览（班数/学生数/课程数，GET /api/teacher/classes），
 * 班级列表带 6 位邀请码（可复制给学生），点班级看学生名单。
 * 「新建班级」内联表单：选课程 + 班名 + 学期 → POST /api/teacher/classes。
 */
@Composable
internal fun TeacherClassesScreen(backdrop: LayerBackdrop, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var drill by remember { mutableStateOf<ClassDrill>(ClassDrill.LIST) }
    com.kewu.app.BackHandler(enabled = drill != ClassDrill.LIST) { drill = ClassDrill.LIST }

    var overview by remember { mutableStateOf<TeacherOverview?>(null) }
    var classes by remember { mutableStateOf<List<ClassRoomInfo>?>(null) }
    var courses by remember { mutableStateOf<List<CourseInfo>?>(null) }
    var loaded by remember { mutableStateOf(false) }

    // 新建班级表单
    var showCreate by remember { mutableStateOf(false) }
    var courseId by remember { mutableStateOf<String?>(null) }
    var className by remember { mutableStateOf("") }
    var term by remember { mutableStateOf(TERM_OPTIONS.first()) }
    var creating by remember { mutableStateOf(false) }
    var createResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    fun reload() {
        scope.launch {
            val ov = KewuApi.getTeacherClasses()
            classes = ov?.classes
            overview = ov
            courses = courses ?: KewuApi.getCourses()
            loaded = true
        }
    }
    LaunchedEffect(Unit) { reload() }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = drill,
            transitionSpec = {
                slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { w -> w } togetherWith
                    slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { w -> -w / 5 }
            },
            modifier = Modifier.fillMaxSize(),
            label = "classNav",
        ) { d ->
            when (d) {
                ClassDrill.LIST -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp),
                ) {
                    HubTopBar("班级管理", onBack)
                    IntroGlass(backdrop, "我的班级", "开设班级后自动生成 6 位邀请码，学生凭码加入并自动绑定课程。")

                    // 顶部三数概览
                    val ov = overview
                    if (ov != null) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            listOf("班级" to ov.classCount, "学生" to ov.studentCount, "课程" to ov.courseCount)
                                .forEach { (label, v) ->
                                    Box(Modifier.weight(1f).squircleCard(16.dp, cs.surface).padding(14.dp)) {
                                        Column {
                                            Text("$v", color = cs.onSurface, fontSize = 20.sp,
                                                fontWeight = FontWeight.Bold)
                                            Spacer(Modifier.height(2.dp))
                                            Text(label, color = cs.onSurfaceVariant, fontSize = 12.sp)
                                        }
                                    }
                                }
                        }
                    } else if (!loaded) {
                        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = cs.primary, strokeWidth = 3.dp,
                                modifier = Modifier.size(24.dp))
                        }
                    }

                    // 新建班级入口
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
                        Button(
                            onClick = { showCreate = !showCreate; createResult = null },
                            colors = ButtonDefaults.buttonColors(
                                color = KewuGreen, contentColor = Color(0xFF0B3B26)),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (showCreate) "收起建班表单" else "＋ 新建班级", fontWeight = FontWeight.Bold) }
                    }

                    // 新建班级内联表单
                    if (showCreate) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
                            Box(Modifier.fillMaxWidth().squircleCard(18.dp, cs.surface).padding(16.dp)) {
                                Column {
                                    Text("选择课程", color = cs.onSurfaceVariant, fontSize = 12.5.sp)
                                    Spacer(Modifier.height(6.dp))
                                    courses?.forEach { c ->
                                        val sel = courseId == c.id
                                        Box(
                                            Modifier.fillMaxWidth().padding(bottom = 6.dp)
                                                .squircleCard(14.dp,
                                                    if (sel) cs.primaryContainer else cs.surfaceVariant)
                                                .hapticClickable { haptic(HapticKind.Tick); courseId = c.id }
                                                .padding(12.dp),
                                        ) {
                                            Text(c.name, fontSize = 13.5.sp,
                                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                                maxLines = 1)
                                        }
                                    } ?: Box(Modifier.fillMaxWidth().padding(8.dp),
                                        contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = cs.primary,
                                            strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                                    }
                                    FieldBox("班级名称", className, { className = it }, placeholder = "如：云计算 1 班")
                                    Text("学期", color = cs.onSurfaceVariant, fontSize = 12.5.sp,
                                        modifier = Modifier.padding(horizontal = 16.dp))
                                    Spacer(Modifier.height(6.dp))
                                    Row(Modifier.padding(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TERM_OPTIONS.forEach { t ->
                                            Chip(selected = term == t,
                                                onClick = { haptic(HapticKind.Tick); term = t }, label = t)
                                        }
                                    }
                                    Spacer(Modifier.height(14.dp))
                                    Button(
                                        onClick = {
                                            val cid = courseId ?: return@Button
                                            scope.launch {
                                                creating = true
                                                val r = KewuApi.createTeacherClass(cid, className.trim(), term)
                                                creating = false
                                                if (r.data != null) {
                                                    haptic(HapticKind.Light)
                                                    createResult = true to "已创建《${r.data.name}》，邀请码 ${r.data.inviteCode}"
                                                    className = ""
                                                    reload()
                                                } else {
                                                    createResult = false to (r.error ?: "创建失败，请稍后重试")
                                                }
                                            }
                                        },
                                        enabled = courseId != null && className.isNotBlank() && !creating,
                                        colors = ButtonDefaults.buttonColors(
                                            color = cs.primary, contentColor = Color.White),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text(if (creating) "正在创建…" else "创建班级", fontWeight = FontWeight.Bold) }
                                    createResult?.let { OutcomeLine(it.second, it.first) }
                                }
                            }
                        }
                    }

                    // 班级列表
                    GroupLabel("我的班级")
                    val classList = classes
                    when {
                        classList == null -> Box(Modifier.fillMaxWidth().padding(30.dp),
                            contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = cs.primary, strokeWidth = 3.dp,
                                modifier = Modifier.size(26.dp))
                        }
                        classList.isEmpty() -> Text(
                            "还没有班级，点上方「新建班级」开始",
                            color = cs.onSurfaceVariant, fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        else -> classList.forEach { cls ->
                            ClassRowCard(cls) { drill = ClassDrill.STUDENTS(cls) }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
                is ClassDrill.STUDENTS -> ClassStudentsScreen(d.cls, onBack = { drill = ClassDrill.LIST })
            }
        }
    }
}

/** 班级行卡：班名 + 课程/学期 + 邀请码（可复制）+ 学生数。 */
@Composable
private fun ClassRowCard(cls: ClassRoomInfo, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
        Box(Modifier.fillMaxWidth().squircleCard(18.dp, cs.surface)
            .hapticClickable { haptic(HapticKind.Tick); onOpen() }
            .padding(14.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(cls.name, color = cs.onSurface, fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Spacer(Modifier.height(2.dp))
                        Text("${cls.courseName} · ${cls.term}", color = cs.onSurfaceVariant,
                            fontSize = 12.sp, maxLines = 1)
                    }
                    Text("${cls.studentCount} 人 ›", color = cs.onSurfaceVariant, fontSize = 12.5.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(cs.primaryContainer.copy(alpha = 0.55f)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("邀请码", color = cs.onPrimaryContainer, fontSize = 12.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(cls.inviteCode, color = cs.primary, fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(cs.primary)
                            .hapticClickable {
                                haptic(HapticKind.Tick)
                                clipboard.setText(AnnotatedString(cls.inviteCode))
                                copied = true
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(if (copied) "已复制" else "复制", color = Color.White,
                            fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/** 班级学生名单（GET /api/teacher/classes/{id}/students）。 */
@Composable
private fun ClassStudentsScreen(cls: ClassRoomInfo, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var students by remember { mutableStateOf<List<ClassStudent>?>(null) }
    LaunchedEffect(cls.id) { students = KewuApi.getClassStudents(cls.id) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
        HubTopBar("${cls.name} · 学生名单", onBack)
        val list = students
        when {
            list == null -> Box(Modifier.fillMaxWidth().padding(40.dp),
                contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = cs.primary, strokeWidth = 3.dp,
                    modifier = Modifier.size(26.dp))
            }
            list.isEmpty() -> Text(
                "班级还没有学生，把邀请码 ${cls.inviteCode} 发给学生即可加入",
                color = cs.onSurfaceVariant, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            else -> {
                GroupLabel("共 ${list.size} 人")
                list.forEach { st ->
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 6.dp)) {
                        Box(Modifier.fillMaxWidth().squircleCard(14.dp, cs.surfaceVariant).padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                                        .background(cs.primaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(st.name.firstOrNull()?.toString() ?: "学",
                                        color = cs.onPrimaryContainer, fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(st.name, color = cs.onSurface, fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Medium, maxLines = 1)
                                    Spacer(Modifier.height(1.dp))
                                    Text("学号 ${st.studentId}", color = cs.onSurfaceVariant,
                                        fontSize = 11.5.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ───────────────────────── 加入班级（学生：邀请码） ─────────────────────────

/**
 * 学生加入班级：输入老师给的 6 位邀请码 → POST /api/classes/join。
 * 成功后自动把课程绑到当前账号（服务端行为），并刷新我的课程列表。
 */
@Composable
internal fun JoinClassScreen(backdrop: LayerBackdrop, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
        HubTopBar("加入班级", onBack)
        IntroGlass(backdrop, "输入邀请码加入班级", "向任课老师索取 6 位邀请码；加入后课程、作业与签到自动同步到课堂页。")
        FieldBox("邀请码", code, { code = it.filter(Char::isLetterOrDigit).take(6).uppercase() },
            placeholder = "如 K7Q2XM")
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                scope.launch {
                    loading = true
                    val r = KewuApi.joinClassByInvite(code)
                    loading = false
                    result = if (r.data != null) {
                        true to r.data
                    } else {
                        false to (r.error ?: "加入失败，请确认邀请码后重试")
                    }
                    if (r.data != null) {
                        code = ""
                        KewuApi.currentUser = null
                        KewuApi.me() // 刷新我的课程（最佳努力）
                    }
                }
            },
            enabled = code.length == 6 && !loading,
            colors = ButtonDefaults.buttonColors(
                color = cs.primary, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) { Text(if (loading) "正在加入…" else "加入班级", fontWeight = FontWeight.Bold) }
        result?.let { OutcomeLine(it.second, it.first) }
        Spacer(Modifier.height(24.dp))
    }
}
