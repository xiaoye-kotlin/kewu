package com.kewu.app.ui

import com.kewu.app.kvSet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.CourseInfo
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.haptic
import com.kewu.app.ui.liquidglass.KewuGlassCard
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldDefaults
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.basic.TopTips
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.preference.RadioButtonPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
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
//
// 2026-10-23 全局 coui 化：顶栏 KewuTopBar；输入框 coui TextField；主按钮 KewuPrimaryButton；
// 选课改 coui RadioButtonPreference 分组卡；时长改 coui Chip；结果文字行改为
// 「coui Snackbar 轻提示 + 失败时常驻 TopTips」（失败文案可能较长，如 postNote 的 Redis 降级说明，
// toast 一闪而过读不完，故失败额外挂一条可关闭的顶部提示条）。
// 下列 internal 件同时被 ClassManageScreens.kt 复用。

/**
 * 表单输入：小标题（coui Text）+ coui [TextField]（Rectangle 白底，单行带清除键）。
 * [placeholder] 作为框内占位，空时回退到 [label]。
 */
@Composable
internal fun TeacherToolField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val cs = COUITheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
        Text(label, color = cs.onSurfaceSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            // 与 coui 分组标题（SmallTitle 内边距 32dp）左对齐：外层 12 + 20（旧版 16dp，比上方「选择课程」往左缩了一截）
            modifier = Modifier.padding(start = 20.dp, bottom = 6.dp))
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            backgroundMode = TextFieldMode.Rectangle,
            colors = TextFieldDefaults.textFieldColors(backgroundColor = cs.surfaceContainer),
            label = placeholder.ifEmpty { label },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 4,
            showClearButton = singleLine,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        )
    }
}

/**
 * 失败常驻提示（coui [TopTips]，可关闭）。成功只走 Snackbar 轻提示，不占版面。
 * 记住最后一条文案，保证收起动画期间文字不突然变空。
 */
@Composable
internal fun TeacherToolFailTips(result: Pair<Boolean, String>?, onClose: () -> Unit) {
    var lastMsg by remember { mutableStateOf("") }
    val failMsg = result?.takeIf { !it.first }?.second
    LaunchedEffect(failMsg) { if (failMsg != null) lastMsg = failMsg }
    val text = failMsg ?: lastMsg
    if (text.isNotEmpty()) {
        TopTips(
            text = text,
            visible = failMsg != null,
            onClose = onClose,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 12.dp),
        )
    }
}

/** 玻璃说明卡（液态玻璃本体保留，采样自传入 [backdrop]；卡内文字为 coui Text）。 */
@Composable
internal fun TeacherToolIntro(backdrop: LayerBackdrop, title: String, subtitle: String) {
    val cs = COUITheme.colorScheme
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
            Text(subtitle, color = cs.onSurfaceSecondary, fontSize = 13.sp)
        }
    }
}

/** 整宽主操作按钮（KewuPrimaryButton，左右与卡片对齐 12dp）。 */
@Composable
private fun ToolButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    KewuPrimaryButton(text, onClick, Modifier.fillMaxWidth().padding(horizontal = 12.dp), enabled = enabled)
}

/**
 * 课程单选组：coui [RadioButtonPreference] 放进同一张分组白卡。
 * 列表为空 / 加载中分别走 KewuEmpty / KewuLoading。
 */
@Composable
private fun CoursePickGroup(courses: List<CourseInfo>?, selectedId: String?, onSelect: (String) -> Unit) {
    when {
        courses == null -> KewuLoading(text = "课程加载中…")
        courses.isEmpty() -> KewuEmpty("暂无可选课程")
        else -> KewuCardGroup {
            courses.forEach { c ->
                RadioButtonPreference(
                    title = c.name,
                    summary = c.teacher.ifBlank { null },
                    selected = selectedId == c.id,
                    onClick = { haptic(HapticKind.Tick); onSelect(c.id) },
                )
            }
        }
    }
}

// ───────────────────────── 绑定课程 ─────────────────────────

/**
 * 绑定课程：输入课程 ID 调 [KewuApi.bindCourse]，并把当前账号已绑定的课程列出来。
 */
@Composable
internal fun BindCourseScreen(backdrop: LayerBackdrop, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = LocalKewuSnackbar.current
    var courseId by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var bound by remember { mutableStateOf<List<CourseInfo>>(emptyList()) }

    LaunchedEffect(Unit) {
        if (KewuApi.currentUser == null) KewuApi.me()
        bound = KewuApi.currentUser?.courses ?: emptyList()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
        KewuTopBar("绑定课程", onBack)
        TeacherToolIntro(backdrop, "绑定课程", "输入课程 ID 绑定到当前账号，绑定后可同步课程作业、签到与录音。")
        if (bound.isNotEmpty()) {
            KewuSectionTitle("已绑定课程")
            KewuCardGroup {
                bound.forEach { c -> BasicComponent(title = c.name, summary = "ID: ${c.id}") }
            }
        }
        TeacherToolField("课程 ID", courseId, { courseId = it }, placeholder = "如 c1001")
        Spacer(Modifier.height(4.dp))
        ToolButton(if (loading) "正在绑定…" else "绑定课程", enabled = courseId.isNotBlank() && !loading) {
            scope.launch {
                loading = true
                val err = KewuApi.bindCourse(courseId.trim())
                loading = false
                if (err == null) {
                    // 绑定后强制刷新（旧版只在 currentUser 为空时才刷，此时它必不为空 → 新课程在发布页 / 筛选里看不到）
                    KewuApi.me(force = true)
                    com.kewu.app.CourseRepo.refresh(force = true)
                    bound = KewuApi.currentUser?.courses ?: bound
                    result = true to "绑定成功"
                } else {
                    result = false to (err ?: "绑定失败")
                }
                result?.let { snackbar.toast(it.second) }
            }
        }
        TeacherToolFailTips(result) { result = null }
        Spacer(Modifier.height(24.dp))
    }
}

// ───────────────────────── 发起签到（教师） ─────────────────────────

/** 签到有效时长可选项（分钟），coui Chip 单选。 */
private val DURATION_OPTIONS = listOf(5, 10, 15, 30)

/**
 * 发起签到：选课程 + 标题 + 有效时长，调 [KewuApi.createAttendance]。
 */
@Composable
internal fun CreateAttendanceScreen(backdrop: LayerBackdrop, onBack: () -> Unit) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    val snackbar = LocalKewuSnackbar.current
    var courses by remember { mutableStateOf<List<CourseInfo>?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("课堂签到") }
    var duration by remember { mutableStateOf(10) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }

    // 2026-10-26：只列老师自己的课（旧版 getCourses() 是全站课程，可能给别人的课发起签到）
    LaunchedEffect(Unit) {
        KewuApi.me()
        courses = KewuApi.myCourses()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
        KewuTopBar("发起签到", onBack)
        TeacherToolIntro(backdrop, "发起课堂签到", "创建一次签到会话，学生通过动态码 / 定位完成打卡。")
        KewuSectionTitle("选择课程")
        CoursePickGroup(courses, selectedId) { selectedId = it }
        TeacherToolField("签到标题", title, { title = it }, placeholder = "课堂签到")
        // 有效时长：coui Chip 单选（原自由数字输入改为常用档位，避免填 0 / 超长）
        Text("有效时长（分钟）", color = cs.onSurfaceSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp))
        Row(
            Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DURATION_OPTIONS.forEach { m ->
                Chip(
                    selected = duration == m,
                    onClick = { haptic(HapticKind.Tick); duration = m },
                    label = "$m 分钟",
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        // 发起成功后按钮锁定（旧版可再点，会重复建会话）；会话写入 teacher_active_session，控制台据此显示「上课中」面板
        ToolButton(
            when {
                loading -> "正在发起…"
                started -> "签到进行中 · 可在控制台查看"
                else -> "发起签到"
            },
            enabled = selectedId != null && title.isNotBlank() && !loading && !started,
        ) {
            scope.launch {
                loading = true
                val publisher = KewuApi.nameForServer()
                val session = KewuApi.createAttendance(
                    selectedId ?: return@launch, title.trim(), durationMinutes = duration, publisher = publisher,
                )
                loading = false
                if (session != null) {
                    started = true
                    kvSet("teacher_active_session", session.id)
                }
                result = if (session != null) true to "签到已发起，学生现在可以扫码或输入暗号签到"
                else false to "签到发起失败，请稍后重试"
                result?.let { snackbar.toast(it.second) }
            }
        }
        TeacherToolFailTips(result) { result = null }
        Spacer(Modifier.height(24.dp))
    }
}

// ───────────────────────── 发布课堂笔记 ─────────────────────────

/**
 * 发布课堂笔记：选课程 + 编辑内容，调 [KewuApi.postNote]；选课后自动拉取已有笔记作草稿。
 * 注：Redis 降级时 postNote 返回「课堂缓存服务暂不可用…」文案，照常作为失败提示展示。
 */
@Composable
internal fun PostNoteScreen(backdrop: LayerBackdrop, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = LocalKewuSnackbar.current
    var courses by remember { mutableStateOf<List<CourseInfo>?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var content by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        KewuApi.me()
        courses = KewuApi.myCourses()
    }
    // 选课后拉取该课已有笔记作草稿。★ 2026-10-26：记住草稿来自哪门课——切到另一门课时，
    // 若内容还是上一门课自动带出的笔记（老师没改过）就整体替换，避免 A 课的笔记被发到 B 课。
    var draftFrom by remember { mutableStateOf<Pair<String, String>?>(null) } // 课程 id -> 自动带出的原文
    LaunchedEffect(selectedId) {
        val id = selectedId ?: return@LaunchedEffect
        val untouched = content.isEmpty() || draftFrom?.second == content
        val existing = KewuApi.getNoteCache(id).orEmpty()
        if (untouched) {
            content = existing
            draftFrom = id to existing
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
        KewuTopBar("课堂笔记", onBack)
        TeacherToolIntro(backdrop, "发布课堂笔记", "记录本节课的要点，发布后即缓存到本课程，方便回看。")
        KewuSectionTitle("选择课程")
        CoursePickGroup(courses, selectedId) { selectedId = it }
        TeacherToolField("笔记内容", content, { content = it }, singleLine = false, placeholder = "记录本节课的要点…")
        Spacer(Modifier.height(4.dp))
        ToolButton(if (loading) "正在发布…" else "发布笔记", enabled = selectedId != null && content.isNotBlank() && !loading) {
            scope.launch {
                loading = true
                val err = KewuApi.postNote(selectedId ?: return@launch, content.trim())
                loading = false
                result = if (err == null) true to "笔记已发布" else false to (err ?: "发布失败")
                result?.let { snackbar.toast(it.second) }
            }
        }
        TeacherToolFailTips(result) { result = null }
        Spacer(Modifier.height(24.dp))
    }
}
