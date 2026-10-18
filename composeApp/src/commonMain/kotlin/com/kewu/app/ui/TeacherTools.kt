package com.kewu.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.CourseInfo
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuGreen
import com.kewu.app.haptic
import com.kewu.app.hapticClickable
import com.kewu.app.ui.liquidglass.KewuGlassCard
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import kotlinx.coroutines.launch

// ───────────────────────── 教师 / 课程工具（补全缺界面的接口） ─────────────────────────
//
// 这一组界面补全后端文档里「有 API、但此前没有 UI」的接口：
//   - bindCourse       → BindCourseScreen（绑定课程）
//   - createAttendance → CreateAttendanceScreen（发起签到，教师入口）
//   - postNote         → PostNoteScreen（发布课堂笔记）
//
// coui 安全约束：本组界面**复用 MyPage 顶层的单一采样层**（不新建自己的 layerBackdrop 背景），
// 玻璃卡（KewuGlassCard）只作为采样者引用传入的 [backdrop]。否则 transition 期间会出现两个
// 采样层同时写同一 backdrop 的冲突。离线 / 未登录时全部优雅降级（提示而不崩）。

/** 通用输入框：标签 + 圆角底（M3 surfaceVariant）+ BasicTextField。 */
@Composable
private fun ToolField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
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
                singleLine = singleLine,
                textStyle = TextStyle(color = cs.onSurface, fontSize = 15.sp),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (value.isEmpty()) {
                            Text(placeholder.ifEmpty { label }, color = cs.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 15.sp)
                        }
                        inner()
                    }
                },
            )
        }
    }
}

/** 通用主操作按钮（主色 / 禁用态转灰）。 */
@Composable
private fun ToolButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) cs.primary else cs.surfaceVariant)
            .hapticClickable { if (enabled) onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) Color.White else cs.onSurfaceVariant,
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 结果提示行（成功=品牌绿，失败=error 语义色）。 */
@Composable
private fun ResultLine(msg: String, ok: Boolean) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp)) {
        Text(msg, color = if (ok) KewuGreen else cs.error, fontSize = 13.sp)
    }
}

/** 玻璃说明卡（液态玻璃，采样自传入 [backdrop]）。 */
@Composable
private fun GlassIntro(backdrop: LayerBackdrop, title: String, subtitle: String) {
    val cs = MaterialTheme.colorScheme
    KewuGlassCard(
        backdrop = backdrop,
        shape = RoundedCornerShape(20.dp),
        blurRadius = 22.dp,
        tintAlpha = 0.28f,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = cs.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

/** 可点选的课程行（squircle；选中态用 primaryContainer 高亮）。 */
@Composable
private fun CoursePickRow(
    course: CourseInfo,
    selected: Boolean,
    subtitle: String = "",
    onSelect: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp)
            .squircleCard(14.dp, if (selected) cs.primaryContainer else cs.surfaceVariant)
            .hapticClickable { haptic(HapticKind.Tick); onSelect() }
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(course.name, color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                if (subtitle.isNotBlank() || course.teacher.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(if (subtitle.isNotBlank()) subtitle else course.teacher,
                        color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
                }
            }
            if (selected) Text("已选", color = cs.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
        fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
}

// ───────────────────────── 绑定课程 ─────────────────────────

/**
 * 绑定课程：输入课程 ID 调 [KewuApi.bindCourse]，并把当前账号已绑定的课程列出来。
 */
@Composable
internal fun BindCourseScreen(backdrop: LayerBackdrop, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var courseId by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var bound by remember { mutableStateOf<List<CourseInfo>>(emptyList()) }

    LaunchedEffect(Unit) {
        if (KewuApi.currentUser == null) KewuApi.me()
        bound = KewuApi.currentUser?.courses ?: emptyList()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
        HubTopBar("绑定课程", onBack)
        GlassIntro(backdrop, "绑定课程", "输入课程 ID 绑定到当前账号，绑定后可同步课程作业、签到与录音。")
        if (bound.isNotEmpty()) {
            SectionLabel("已绑定课程")
            bound.forEach { c ->
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)
                        .squircleCard(14.dp, MaterialTheme.colorScheme.surfaceVariant).padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(2.dp))
                            Text("ID: ${c.id}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        ToolField("课程 ID", courseId, { courseId = it }, placeholder = "如 c1001")
        Spacer(Modifier.height(4.dp))
        ToolButton("绑定课程", enabled = courseId.isNotBlank() && !loading) {
            scope.launch {
                loading = true
                val err = KewuApi.bindCourse(courseId.trim())
                loading = false
                if (err == null) {
                    if (KewuApi.currentUser == null) KewuApi.me()
                    bound = KewuApi.currentUser?.courses ?: bound
                    result = true to "绑定成功"
                } else {
                    result = false to (err ?: "绑定失败")
                }
            }
        }
        result?.let { ResultLine(it.second, it.first) }
        Spacer(Modifier.height(24.dp))
    }
}

// ───────────────────────── 发起签到（教师） ─────────────────────────

/**
 * 发起签到：选课程 + 标题 + 有效时长，调 [KewuApi.createAttendance]。
 */
@Composable
internal fun CreateAttendanceScreen(backdrop: LayerBackdrop, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var courses by remember { mutableStateOf<List<CourseInfo>?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("课堂签到") }
    var duration by remember { mutableStateOf("10") }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { courses = KewuApi.getCourses() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
        HubTopBar("发起签到", onBack)
        GlassIntro(backdrop, "发起课堂签到", "创建一次签到会话，学生通过动态码 / 定位完成打卡。")
        SectionLabel("选择课程")
        courses?.let { list ->
            if (list.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(16.dp)) { Text("暂无可选课程", color = cs.onSurfaceVariant, fontSize = 13.sp) }
            }
            list.forEach { c ->
                CoursePickRow(c, selected = selectedId == c.id) { selectedId = c.id }
            }
        } ?: Box(Modifier.fillMaxWidth().padding(16.dp)) { Text("课程加载中…", color = cs.onSurfaceVariant, fontSize = 13.sp) }
        Spacer(Modifier.height(8.dp))
        ToolField("签到标题", title, { title = it }, placeholder = "课堂签到")
        ToolField("有效时长（分钟）", duration, { duration = it.filter { ch -> ch.isDigit() } }, placeholder = "10")
        Spacer(Modifier.height(4.dp))
        ToolButton("发起签到", enabled = selectedId != null && title.isNotBlank() && !loading) {
            scope.launch {
                loading = true
                val dur = duration.toIntOrNull() ?: 10
                val session = KewuApi.createAttendance(selectedId!!, title.trim(), durationMinutes = dur)
                loading = false
                result = if (session != null) true to "签到已发起 · 会话 ${session.id}"
                else false to "签到发起失败，请稍后重试"
            }
        }
        result?.let { ResultLine(it.second, it.first) }
        Spacer(Modifier.height(24.dp))
    }
}

// ───────────────────────── 发布课堂笔记 ─────────────────────────

/**
 * 发布课堂笔记：选课程 + 编辑内容，调 [KewuApi.postNote]；选课后自动拉取已有笔记作草稿。
 */
@Composable
internal fun PostNoteScreen(backdrop: LayerBackdrop, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var courses by remember { mutableStateOf<List<CourseInfo>?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var content by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { courses = KewuApi.getCourses() }
    // 选课后拉取已有笔记作草稿（仅当尚未手动编辑时填充）
    LaunchedEffect(selectedId) {
        selectedId?.let { id ->
            KewuApi.getNoteCache(id)?.let { existing -> if (content.isEmpty()) content = existing }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
        HubTopBar("课堂笔记", onBack)
        GlassIntro(backdrop, "发布课堂笔记", "记录本节课的要点，发布后即缓存到本课程，方便回看。")
        SectionLabel("选择课程")
        courses?.let { list ->
            list.forEach { c ->
                CoursePickRow(c, selected = selectedId == c.id) { selectedId = c.id }
            }
        } ?: Box(Modifier.fillMaxWidth().padding(16.dp)) { Text("课程加载中…", color = cs.onSurfaceVariant, fontSize = 13.sp) }
        Spacer(Modifier.height(8.dp))
        ToolField("笔记内容", content, { content = it }, singleLine = false, placeholder = "记录本节课的要点…")
        Spacer(Modifier.height(4.dp))
        ToolButton("发布笔记", enabled = selectedId != null && content.isNotBlank() && !loading) {
            scope.launch {
                loading = true
                val err = KewuApi.postNote(selectedId!!, content.trim())
                loading = false
                result = if (err == null) true to "笔记已发布" else false to (err ?: "发布失败")
            }
        }
        result?.let { ResultLine(it.second, it.first) }
        Spacer(Modifier.height(24.dp))
    }
}
