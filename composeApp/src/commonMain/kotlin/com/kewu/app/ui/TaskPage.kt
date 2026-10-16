package com.kewu.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp as floatLerp
import com.kewu.app.KewuGreen
import com.kewu.app.ui.liquidglass.LiquidGlassSurface
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
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
 * 交互：点一条待完成 → 打勾移入「已完成」（再点取消），演示用。
 */
@Composable
fun TaskPage(onNavHide: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tasks = remember { mockTasks() }
    var doneIds by remember { mutableStateOf(setOf("t5")) }
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

            // coui 玻璃汇总卡：本周待交数 + 完成进度环（M3 进度环，spring 跟随）
            // 「N 项」数字在数量变化时来一次小脉冲（1 → 1.1 → 1），把"又少一项"演出来
            val progress by animateFloatAsState(
                targetValue = if (tasks.isEmpty()) 0f else done.size.toFloat() / tasks.size,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
                label = "taskProgress",
            )
            val pendingPulse = remember { Animatable(1f) }
            LaunchedEffect(pending.size) {
                if (pendingPulse.value != 1f) pendingPulse.snapTo(1f)
                launch {
                    pendingPulse.animateTo(1.1f, tween(durationMillis = 90, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                    pendingPulse.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 420f))
                }
            }
            LiquidGlassSurface(
                backdrop = pageBackdrop,
                shape = RoundedCornerShape(16.dp),
                blurRadius = 20.dp,
                tintAlpha = 0.3f,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("本周待交", color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${pending.size} 项", color = cs.onSurface, fontSize = 20.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1,
                            modifier = Modifier.scale(pendingPulse.value),
                        )
                    }
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(46.dp),
                        color = KewuGreen,
                        strokeWidth = 4.5.dp,
                        strokeCap = StrokeCap.Round,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            SectionLabel("待完成 · ${pending.size}")
            pending.forEachIndexed { i, t ->
                TaskCard(
                    task = t,
                    done = false,
                    enterIndex = i,
                    onToggle = { doneIds = doneIds + t.id },
                )
            }
            if (done.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                SectionLabel("已完成 · ${done.size}")
                done.forEachIndexed { i, t ->
                    TaskCard(
                        task = t,
                        done = true,
                        enterIndex = i,
                        onToggle = { doneIds = doneIds - t.id },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), maxLines = 1,
    )
}

/**
 * 任务徽章：**空心圈 ⇄ 大半圆+绿勾的形变动画**（M3 经典 morph）。
 * [done] 变化时一个 Animatable 驱动 progress 0→1：
 * - 0→45%：空心圈 sweep 从 270° 收回 0° 并淡出（圈"化"开）；
 * - 35%→100%：勾的两段折线依次长出（段 1 占 35→75%，段 2 占 65→100%，接缝重叠）；
 * - 颜色全程从 onSurfaceVariant 渐变到 KewuGreen；
 * - 另有一组缩放脉冲（0.82 → 弹簧回弹），点下去那一下有"咬合"手感。
 */
@Composable
private fun TaskBadge(done: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val progress = remember { Animatable(if (done) 1f else 0f) }
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(done) {
        launch {
            progress.animateTo(
                targetValue = if (done) 1f else 0f,
                animationSpec = if (done) spring(dampingRatio = 0.55f, stiffness = 320f)
                else spring(dampingRatio = 0.7f, stiffness = 420f),
            )
        }
        if (done) {
            launch {
                pulse.animateTo(0.82f, tween(durationMillis = 70))
                pulse.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 500f))
            }
        }
    }
    val p = progress.value
    // 颜色渐变（勾定型时纯品牌绿）
    val color = androidx.compose.ui.graphics.lerp(
        cs.onSurfaceVariant, KewuGreen, ((p - 0.25f) / 0.7f).coerceIn(0f, 1f),
    )
    Canvas(
        modifier
            .size(26.dp)
            .scale(pulse.value),
    ) {
        val sw = size.width * 0.10f
        // 空心圈：sweep 270°→0° 收回，淡出
        val circleT = (p / 0.45f).coerceIn(0f, 1f)
        val sweep = 270f * (1f - circleT)
        if (sweep > 1f) {
            drawArc(
                color = color.copy(alpha = 0.45f * (1f - circleT * 0.85f)),
                startAngle = 135f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(sw / 2f, sw / 2f),
                size = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw),
                style = Stroke(width = size.width * 0.09f, cap = StrokeCap.Round),
            )
        }
        // 勾：两段折线（相对坐标），段 1 = 35%→75%，段 2 = 65%→100%
        val t1 = ((p - 0.35f) / 0.4f).coerceIn(0f, 1f)
        val t2 = ((p - 0.65f) / 0.35f).coerceIn(0f, 1f)
        if (t1 > 0f) {
            drawLine(
                color = color,
                start = Offset(size.width * 0.30f, size.height * 0.50f),
                end = Offset(
                    size.width * floatLerp(0.30f, 0.44f, t1),
                    size.height * floatLerp(0.50f, 0.65f, t1),
                ),
                strokeWidth = sw,
                cap = StrokeCap.Round,
            )
        }
        if (t2 > 0f) {
            drawLine(
                color = color,
                start = Offset(size.width * 0.44f, size.height * 0.65f),
                end = Offset(
                    size.width * floatLerp(0.44f, 0.70f, t2),
                    size.height * floatLerp(0.65f, 0.32f, t2),
                ),
                strokeWidth = sw,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 任务卡（Mishka squircle）：左徽章（圈⇄绿勾形变）+ 标题 + 课程截止 + 紧急标签。 */
@Composable
private fun TaskCard(task: Task, done: Boolean, enterIndex: Int = 0, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    // 标题颜色 / 删除线随完成态平滑过渡（形变的主角是徽章，这里做柔和的辅助）
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
            .cardEnter(enterIndex)
            .squircleCard(16.dp, cs.surfaceVariant)
            .clickable { onToggle() }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 徽章：空心圈 ⇄ 大半圆+绿勾（形变动画）
            TaskBadge(done = done)
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
