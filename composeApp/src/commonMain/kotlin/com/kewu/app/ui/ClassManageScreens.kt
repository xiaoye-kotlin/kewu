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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.verticalScroll
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
import com.kewu.app.ui.liquidglass.KewuGlassCard
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.Surface
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.preference.RadioButtonPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
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
//
// 2026-10-23 全局 coui 化：顶栏 KewuTopBar；输入框复用 TeacherTools 的 [TeacherToolField]（coui TextField）；
// 按钮 KewuPrimaryButton / coui TextButton；选课 coui RadioButtonPreference；行卡 coui Card；
// 名单 coui BasicComponent 分组卡；加载 / 空态 KewuLoading / KewuEmpty；
// 结果文字行改为 coui Snackbar 轻提示 + 失败常驻 TopTips（[TeacherToolFailTips]）。

/** 玻璃说明卡（液态玻璃本体保留，采样自传入 [backdrop]；描边改 coui dividerLine，文字 coui Text）。 */
@Composable
private fun IntroGlass(backdrop: LayerBackdrop, title: String, subtitle: String) {
    val cs = COUITheme.colorScheme
    KewuGlassCard(
        backdrop = backdrop,
        shape = RoundedCornerShape(20.dp),
        blurRadius = 22.dp,
        tintAlpha = 0.28f,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .border(1.dp, cs.dividerLine, RoundedCornerShape(20.dp)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = cs.onSurfaceSecondary, fontSize = 13.sp)
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
    val snackbar = LocalKewuSnackbar.current
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
        KewuTopBar("新建课程", onBack)
        IntroGlass(backdrop, "创建一门新课程", "课程创建后立即可发起签到、开设班级并发布作业。")
        TeacherToolField("课程名称", name, { name = it }, placeholder = "如：云计算基础")
        TeacherToolField("课程简介", desc, { desc = it }, placeholder = "一句话说明课程内容（可选）")
        Spacer(Modifier.height(4.dp))
        KewuPrimaryButton(
            text = if (loading) "正在创建…" else "创建课程",
            onClick = {
                scope.launch {
                    loading = true
                    val r = KewuApi.createTeacherCourse(name.trim(), desc.trim())
                    if (r.data != null) {
                        // 新课程立刻出现在发布作业 / 课程筛选里（旧版不刷新用户课程缓存）
                        KewuApi.me(force = true)
                        com.kewu.app.CourseRepo.refresh(force = true)
                    }
                    loading = false
                    result = if (r.data != null) {
                        true to "已创建《${r.data.name}》，课程号 ${r.data.id}"
                    } else {
                        false to (r.error ?: "创建失败，请稍后重试")
                    }
                    if (r.data != null) { name = ""; desc = "" }
                    result?.let { snackbar.toast(it.second) }
                }
            },
            enabled = name.isNotBlank() && !loading,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        TeacherToolFailTips(result) { result = null }
        Spacer(Modifier.height(24.dp))
    }
}

// ───────────────────────── 我的班级（教师：概览 + 建班 + 邀请码 + 名单） ─────────────────────────

private sealed class ClassDrill {
    data object LIST : ClassDrill()
    data class STUDENTS(val cls: ClassRoomInfo) : ClassDrill()
    /** 导入课表（建班成功后自动进入，也可从学生名单页进入更新）。 */
    data class SCHEDULE(val cls: ClassRoomInfo) : ClassDrill()
    /** 新建班级：先导入课表再建班（2026-10-27，不再要求先选课程）。 */
    data class NEW_CLASS(val name: String, val term: String) : ClassDrill()
}

/**
 * 班级管理：顶部三数概览（班数/学生数/课程数，GET /api/teacher/classes），
 * 班级列表带 6 位邀请码（可复制给学生），点班级看学生名单。
 * 「新建班级」内联表单：选课程 + 班名 + 学期 → POST /api/teacher/classes。
 */
@Composable
internal fun TeacherClassesScreen(backdrop: LayerBackdrop, onBack: () -> Unit) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    val snackbar = LocalKewuSnackbar.current

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
            courses = courses ?: KewuApi.myCourses()
            loaded = true
        }
    }
    LaunchedEffect(Unit) { reload() }

    Box(Modifier.fillMaxSize()) {
        // ★ 2026-10-26 Telegram 式推入 / 返回（旧版返回也是从右推入，方向反了）；学生名单可右滑回列表。
        //   列表页不接管手势 → 交给外层「我的」的右滑返回。
        KewuSlideHost(
            targetState = drill,
            modifier = Modifier.fillMaxSize(),
            isForward = { _, to -> to != ClassDrill.LIST },
            swipeEnabled = drill is ClassDrill.STUDENTS,
            swipeNeighbor = { from, dir -> if (dir < 0 && from != ClassDrill.LIST) ClassDrill.LIST else null },
            onSwipeCommit = { drill = it },
        ) { d ->
            when (d) {
                ClassDrill.LIST -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp),
                ) {
                    KewuTopBar("班级管理", onBack)
                    IntroGlass(backdrop, "我的班级", "开设班级后自动生成 6 位邀请码，学生凭码加入并自动绑定课程。")

                    // 顶部三数概览（三张 coui 白卡）
                    val ov = overview
                    if (ov != null) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            listOf("班级" to ov.classCount, "学生" to ov.studentCount, "课程" to ov.courseCount)
                                .forEach { (label, v) ->
                                    Card(
                                        modifier = Modifier.weight(1f),
                                        cornerRadius = 16.dp,
                                        insideMargin = PaddingValues(14.dp),
                                        colors = kewuCardColors(),
                                    ) {
                                        Text("$v", color = cs.onSurface, fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.height(2.dp))
                                        Text(label, color = cs.onSurfaceSecondary, fontSize = 12.sp)
                                    }
                                }
                        }
                    } else if (!loaded) {
                        KewuLoading()
                    }

                    // 新建班级入口（coui TextButton，沿用品牌绿强调「新增」动作）
                    TextButton(
                        text = if (showCreate) "收起建班表单" else "＋ 新建班级",
                        onClick = { haptic(HapticKind.Tick); showCreate = !showCreate; createResult = null },
                        colors = ButtonDefaults.textButtonColorsPrimary(
                            color = KewuGreen, textColor = Color(0xFF0B3B26)),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp),
                    )

                    // 新建班级内联表单（2026-10-27）：只填班名 + 学期，不再先选课程；
                    // 「下一步」进导入课表页，导入时自动建班并关联课表里的课程（也可跳过课表直接建班）
                    if (showCreate) {
                        TeacherToolField("班级名称", className, { className = it }, placeholder = "如：云计算 1 班")
                        Text("学期", color = cs.onSurfaceSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 16.dp))
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TERM_OPTIONS.forEach { t ->
                                Chip(selected = term == t,
                                    onClick = { haptic(HapticKind.Tick); term = t }, label = t)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        KewuPrimaryButton(
                            text = "下一步：导入课表",
                            onClick = {
                                haptic(HapticKind.Light)
                                drill = ClassDrill.NEW_CLASS(className.trim(), term)
                            },
                            enabled = className.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        )
                        TeacherToolFailTips(createResult) { createResult = null }
                        Spacer(Modifier.height(12.dp))
                    }

                    // 班级列表
                    KewuSectionTitle("我的班级")
                    val classList = classes
                    when {
                        classList == null -> KewuLoading()
                        classList.isEmpty() -> KewuEmpty("还没有班级，点上方「新建班级」开始")
                        else -> classList.forEach { cls ->
                            ClassRowCard(cls) { drill = ClassDrill.STUDENTS(cls) }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
                is ClassDrill.STUDENTS -> ClassStudentsScreen(
                    d.cls, onBack = { drill = ClassDrill.LIST },
                    onImportSchedule = { drill = ClassDrill.SCHEDULE(d.cls) },
                )
                is ClassDrill.NEW_CLASS -> ScheduleImportScreen(
                    classId = null, className = d.name, defaultCourse = "",
                    onBack = { drill = ClassDrill.LIST },
                    onDone = {
                        className = ""
                        showCreate = false
                        reload()
                        drill = ClassDrill.LIST
                    },
                    createClass = {
                        // 先建班（不带课程），课表导入时后端回填本班关联课程
                        val r = KewuApi.createTeacherClass("", d.name, d.term)
                        if (r.data != null) {
                            snackbar.toast("已创建《${r.data.name}》，邀请码 ${r.data.inviteCode}")
                            r.data.id to null
                        } else null to (r.error ?: "建班失败，请稍后重试")
                    },
                )
                is ClassDrill.SCHEDULE -> ScheduleImportScreen(
                    classId = d.cls.id, className = d.cls.name, defaultCourse = d.cls.courseName,
                    onBack = { drill = ClassDrill.LIST }, onDone = { drill = ClassDrill.LIST },
                )
            }
        }
    }
}

/** 班级行卡（coui Card 可点）：班名 + 课程/学期 + 邀请码（可复制）+ 学生数。 */
@Composable
private fun ClassRowCard(cls: ClassRoomInfo, onOpen: () -> Unit) {
    val cs = COUITheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalKewuSnackbar.current
    var copied by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
        cornerRadius = 18.dp,
        insideMargin = PaddingValues(14.dp),
        colors = kewuCardColors(),
        onClick = { haptic(HapticKind.Tick); onOpen() },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(cls.name, color = cs.onSurface, fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text("${cls.courseName} · ${cls.term}", color = cs.onSurfaceSecondary,
                    fontSize = 12.sp, maxLines = 1)
            }
            Text("${cls.studentCount} 人 ›", color = cs.onSurfaceSecondary, fontSize = 12.5.sp)
        }
        Spacer(Modifier.height(10.dp))
        // 邀请码条：浅主色底（coui tertiaryContainer）+ 主色大字 + coui 小号主按钮「复制」
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(cs.tertiaryContainer).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("邀请码", color = cs.onTertiaryContainer, fontSize = 12.sp)
            Spacer(Modifier.width(10.dp))
            Text(cls.inviteCode, color = cs.primary, fontSize = 18.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.weight(1f))
            TextButton(
                text = if (copied) "已复制" else "复制",
                onClick = {
                    haptic(HapticKind.Tick)
                    clipboard.setText(AnnotatedString(cls.inviteCode))
                    copied = true
                    scope.launch { snackbar.toast("邀请码已复制") }
                },
                colors = ButtonDefaults.textButtonColorsPrimary(),
                minHeight = ButtonDefaults.MinHeightSmall,
                minWidth = ButtonDefaults.MinWidthSmall,
                insideMargin = ButtonDefaults.InsideMarginSmall,
                cornerRadius = ButtonDefaults.MinHeightSmall / 2,
            )
        }
    }
}

/** 班级学生名单（GET /api/teacher/classes/{id}/students）：coui BasicComponent 分组卡。 */
@Composable
private fun ClassStudentsScreen(cls: ClassRoomInfo, onBack: () -> Unit, onImportSchedule: () -> Unit) {
    val cs = COUITheme.colorScheme
    var students by remember { mutableStateOf<List<ClassStudent>?>(null) }
    LaunchedEffect(cls.id) { students = KewuApi.getClassStudents(cls.id) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
        KewuTopBar("${cls.name} · 学生名单", onBack)
        KewuSecondaryButton("导入 / 更新本班课表", onImportSchedule, Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp))
        val list = students
        when {
            list == null -> KewuLoading()
            list.isEmpty() -> KewuEmpty("班级还没有学生，把邀请码 ${cls.inviteCode} 发给学生即可加入")
            else -> {
                KewuSectionTitle("共 ${list.size} 人")
                KewuCardGroup {
                    list.forEach { st ->
                        BasicComponent(
                            title = st.name,
                            summary = "学号 ${st.studentNo.ifBlank { st.studentId }}",
                            startAction = {
                                // 姓氏头像：coui Surface 浅主色圆角块
                                Surface(
                                    modifier = Modifier.padding(end = 12.dp).size(30.dp),
                                    shape = RoundedCornerShape(9.dp),
                                    color = cs.tertiaryContainer,
                                    contentColor = cs.onTertiaryContainer,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(st.name.firstOrNull()?.toString() ?: "学",
                                            color = cs.onTertiaryContainer, fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold)
                                    }
                                }
                            },
                        )
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
    val scope = rememberCoroutineScope()
    val snackbar = LocalKewuSnackbar.current
    var code by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
        KewuTopBar("加入班级", onBack)
        IntroGlass(backdrop, "输入邀请码加入班级", "向任课老师索取 6 位邀请码；加入后课程、作业与签到自动同步到课堂页。")
        TeacherToolField("邀请码", code, { code = it.filter(Char::isLetterOrDigit).take(6).uppercase() },
            placeholder = "如 K7Q2XM")
        Spacer(Modifier.height(4.dp))
        KewuPrimaryButton(
            text = if (loading) "正在加入…" else "加入班级",
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
                        haptic(HapticKind.Light)
                        snackbar.toast(r.data)
                        // 2026-10-27：加入成功立刻退回「我的」（旧版停在原页，看起来像没反应），
                        // 并刷新本人资料 / 课程 / 作业：班级、课表里的课马上可见
                        onBack()
                        KewuApi.me(force = true)
                        com.kewu.app.CourseRepo.refresh(force = true)
                        com.kewu.app.AssignmentRepo.refresh()
                        return@launch
                    }
                    result?.let { snackbar.toast(it.second) }
                }
            },
            enabled = code.length == 6 && !loading,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        TeacherToolFailTips(result) { result = null }
        Spacer(Modifier.height(24.dp))
    }
}
