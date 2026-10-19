package com.kewu.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import com.kewu.app.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.kewu.app.KewuApi
import com.kewu.app.KewuGreen
import com.kewu.app.hapticClickable
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ───────────────────────── 数据模型（mock） ─────────────────────────

private data class Task(
    val id: String,
    val title: String,
    val course: String,
    val due: String,
    val urgent: Boolean = false,
)

private fun mockTasks(): List<Task> = listOf(
    Task("t1", "习题 3.2 · 特征值与特征向量应用", "线性代数", "今天 23:59", urgent = true),
    Task("t2", "背诵模仿 Unit 3", "大学英语", "明天 18:00"),
    Task("t3", "实验报告二 · 排序算法对比", "程序设计", "10月20日"),
    Task("t4", "读书笔记 · 认识的飞跃", "马克思主义原理", "10月22日"),
    Task("t5", "作业 5 · 多元函数微分", "高等数学", "10月14日 已交"),
)

// ───────────────────────── 任务页（tab 2） ─────────────────────────

/**
 * 任务页：课堂页「作业待交」小卡的跳转目标。
 * 版式 = Mishka（squircle 卡 / 12dp 间距 / 卡内 16dp）；顶部汇总卡用 coui 毛玻璃。
 * 交互：点一条待完成 → 打开作业详情 Hub；已交标记来自 /api/assignments/{id}/my。
 *
 * ★ 计数 bug 修复（2026-10-18）：旧版先把 mock 5 条当真实数据上屏（「还有 5 项」），
 * 接口返回后再整体换成真值（比如 0 条 →「0 项」）—— 数字瞬间跳变。现在初始为空 + loading，
 * 只显示真实接口数据；仅断网失败才降级 mock。数字脉冲也跳过首次填充。
 */
@Composable
fun TaskPage(onNavHide: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    // 初始为空列表（不是 mock！），loading 期间显示加载态
    var tasks by remember { mutableStateOf(emptyList<Task>()) }
    var doneIds by remember { mutableStateOf(setOf<String>()) }
    var loading by remember { mutableStateOf(true) }
    var assignmentId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val studentId = KewuApi.username ?: ""
        val list = KewuApi.getAssignments()
        if (list != null) {
            tasks = list.map { a ->
                Task(id = a.id, title = a.title, course = a.courseId, due = a.deadline.ifBlank { "未设置" })
            }
            val done = mutableSetOf<String>()
            list.forEach { a ->
                if (studentId.isNotEmpty() && KewuApi.getMySubmissions(a.id, studentId) != null) done.add(a.id)
            }
            doneIds = done
        } else {
            // 断网兜底：此时才降级 mock（不是一进来就显示假数据）
            tasks = mockTasks()
            doneIds = setOf("t5")
        }
        loading = false
    }
    val pending = tasks.filter { it.id !in doneIds }
    val done = tasks.filter { it.id in doneIds }
    val pageBackdrop = rememberGlassBackdrop()
    val scrollState = rememberScrollState()

    // 与课堂页同款：向下浏览收起底部导航栏，回顶展开
    LaunchedEffect(scrollState) {
        var last = 0
        snapshotFlow { scrollState.value }.collect { cur ->
            if (cur <= 0) {
                onNavHide(false)
            } else {
                val delta = cur - last
                if (delta > 6) onNavHide(true) else if (delta < -6) onNavHide(false)
            }
            last = cur
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 背景层：M3 语义色，是玻璃汇总卡的采样源（采集层与玻璃平级 ✓）
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(pageBackdrop)
                .background(cs.background),
        )
        // ★ 下钻滑页（与「我的」子路由同款，仅 slide 无 fade）：旧版 if/else 硬切，生硬。
        AnimatedContent(
            targetState = assignmentId,
            transitionSpec = {
                if (targetState != null) {
                    slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { w -> w } togetherWith
                        slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { w -> -w / 5 }
                } else {
                    slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { w -> -w / 5 } togetherWith
                        slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { w -> w }
                }
            },
            label = "taskDrill",
        ) { drillId ->
            if (drillId == null) {
                TaskListContent(
                    tasks = tasks,
                    doneIds = doneIds,
                    loading = loading,
                    scrollState = scrollState,
                    pageBackdrop = pageBackdrop,
                    onOpenTask = { assignmentId = it },
                )
            } else {
                BackHandler { assignmentId = null }
                AssignmentDetailScreen(
                    assignmentId = drillId,
                    onBack = { assignmentId = null },
                    onSubmitted = { id -> doneIds = doneIds + id },
                )
            }
        }
    }
}

@Composable
private fun TaskListContent(
    tasks: List<Task>,
    doneIds: Set<String>,
    loading: Boolean,
    scrollState: androidx.compose.foundation.ScrollState,
    pageBackdrop: io.github.suqi8.coui.kmp.blur.LayerBackdrop,
    onOpenTask: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val pending = tasks.filter { it.id !in doneIds }
    val done = tasks.filter { it.id in doneIds }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(bottom = 96.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "任务", color = cs.onSurface, fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
                Text(
                    "作业 · 待办 · 提醒", color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // coui 玻璃汇总卡：本周待交数 + 完成进度环（M3 进度环，spring 跟随）。
        // 「N 项」数字在**用户完成/取消**导致数量变化时来一次小脉冲（1 → 1.1 → 1），把"又少一项"演出来；
        // 首次填充与进入页面**不**脉冲（否则一打开数字就跳一下，观感像 bug —— 原「5 项→0 项」的另一半修复）。
        val progress by animateFloatAsState(
            targetValue = if (tasks.isEmpty()) 0f else done.size.toFloat() / tasks.size,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
            label = "taskProgress",
        )
        val pendingPulse = remember { Animatable(1f) }
        val prevPending = remember { mutableStateOf(-1) } // -1 = 尚未首次填充
        LaunchedEffect(pending.size) {
            if (prevPending.value == -1) {
                // 首次填充：只记录，不脉冲（避免打开页面数字就跳）
                prevPending.value = pending.size
            } else if (prevPending.value != pending.size) {
                prevPending.value = pending.size
                pendingPulse.snapTo(1f)
                launch {
                    pendingPulse.animateTo(1.1f, tween(durationMillis = 90, easing = FastOutSlowInEasing))
                    pendingPulse.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 420f))
                }
            }
        }
        // 玻璃凝结入场：先不可见，再从零上浮「长」出来
        val sumP = rememberEnterProgress(0, baseDelayMs = 200L)
        KewuGlassCard(
            backdrop = pageBackdrop,
            shape = RoundedCornerShape(16.dp),
            blurRadius = glassCondenseBlur(sumP, 20f),
            tintAlpha = 0.30f * sumP,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .glassEnter(sumP, 40f),
        ) {
            Row(
                Modifier.padding(16.dp).alpha(sumP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("本周待交", color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (loading) "…" else "${pending.size} 项", color = cs.onSurface, fontSize = 20.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1,
                        modifier = Modifier.scale(pendingPulse.value),
                    )
                }
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(46.dp),
                    color = KewuGreen,
                    strokeWidth = 4.5.dp,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        if (loading) {
            // 加载态：不再用 mock 占位（数字跳变 bug 的根因），显示一个轻量加载指示
            Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = cs.primary)
            }
        } else if (tasks.isEmpty()) {
            // 真实接口返回空：空态提示（不是 bug，是真的没有作业）
            val emptyP = rememberEnterProgress(1, baseDelayMs = 240L)
            KewuGlassCard(
                backdrop = pageBackdrop,
                shape = RoundedCornerShape(16.dp),
                blurRadius = glassCondenseBlur(emptyP, 18f),
                tintAlpha = 0.22f * emptyP,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).glassEnter(emptyP, 40f),
            ) {
                Box(Modifier.fillMaxWidth().alpha(emptyP).padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                    Text("暂无作业，休息一下～", color = cs.onSurfaceVariant, fontSize = 14.sp)
                }
            }
        } else {
            SectionLabel("待完成 · ${pending.size}", Modifier.riseEnter(rememberEnterProgress(0, baseDelayMs = 240L), 24f))
            pending.forEachIndexed { i, t ->
                TaskCard(
                    task = t,
                    done = false,
                    enterIndex = i,
                    onToggle = { onOpenTask(t.id) },
                )
            }
            if (done.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                SectionLabel("已完成 · ${done.size}", Modifier.riseEnter(rememberEnterProgress(0, baseDelayMs = 300L), 24f))
                done.forEachIndexed { i, t ->
                    TaskCard(
                        task = t,
                        done = true,
                        enterIndex = i,
                        onToggle = { },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp), maxLines = 1,
    )
}

/**
 * 任务卡（Mishka squircle）：左侧 **大半圆+勾/叉**（与首页状态卡同一套图形语言）+
 * 标题 + 课程截止 + 紧急标签。点击整卡打开作业详情；勾选态 = 后端已提交。
 * 入场：从零上浮淡入（riseEnter，非玻璃件可安全叠 alpha/scale）。
 */
@Composable
private fun TaskCard(task: Task, done: Boolean, enterIndex: Int = 0, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    // 标题颜色 / 删除线随完成态平滑过渡
    val titleColor by animateColorAsState(
        targetValue = if (done) cs.onSurfaceVariant else cs.onSurface,
        animationSpec = tween(durationMillis = 260),
        label = "taskTitle",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 10.dp)
            .riseEnter(rememberEnterProgress(enterIndex, baseDelayMs = 260L, stepMs = 55L), 60f)
            .squircleCard(16.dp, cs.surfaceVariant)
            .hapticClickable { onToggle() }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // ★ 与首页同一套「大半圆 + 勾/叉」图形语言（用户 2026-10-19 要求）：
            //   已完成 = 品牌绿大半圆 + 勾；未完成 = 灰色大半圆 + 叉。
            //   不再用 coui 的方形 Checkbox（用户明确不喜欢「一个正方形里面一个勾」）。
            ThreeQuarterCheck(
                size = 26.dp,
                color = if (done) KewuGreen else cs.onSurfaceVariant,
                cross = !done,
            )
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    task.title,
                    color = titleColor,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "${task.course} · 截止 ${task.due}",
                    color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1,
                )
            }
            if (task.urgent && !done) {
                Spacer(Modifier.width(8.dp))
                StatusTag("今天截止", cs.errorContainer, cs.onErrorContainer)
            }
        }
    }
}


