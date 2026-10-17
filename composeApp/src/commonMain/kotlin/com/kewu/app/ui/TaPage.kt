package com.kewu.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.KewuGreen
import com.kewu.app.hapticClickable
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop

// ───────────────────────── 助教 / 课程中心页（tab 1） ─────────────────────────

/**
 * 「助教」页（实为**课程中心**）：顶部分组标题 + 玻璃概览卡 + 课程列表 + 作业列表。
 * 版式沿用 Mishka / 课堂页语言（squircle 卡、12dp 间距、卡内 16dp）；标题与概述用 coui 毛玻璃。
 *
 * 交互：
 * - 点课程 → 回课堂 tab（看课表 / 正在上课）；
 * - 点作业 → 跳任务 tab（任务页是作业待交的归集处）。
 * - 向下浏览收起底部导航栏，回顶展开（与课堂/任务/我的同款）。
 *
 * ★ 用户明确要求「作业」二字**居中**（此前某版被左对齐）。这里分组标题统一用
 *   [TextAlign.Center] 的 [SectionLabelCentered]，确保「课程 / 作业」都水平居中。
 */
@Composable
fun TaPage(onNavHide: (Boolean) -> Unit, onGoToTab: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val pageBackdrop = rememberGlassBackdrop()
    val scrollState = rememberScrollState()
    val courses = remember { mockTaCourses() }
    val assignments = remember { mockTaAssignments() }

    // 与课堂/任务/我的同款：向下浏览收起底部导航栏，回顶展开
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
        // 背景层：M3 语义色，是玻璃卡（概览头）的采样源（采集层与玻璃平级 ✓）
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
                        "课程中心", color = cs.onSurface, fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1,
                    )
                    Text(
                        "我的课程 · 作业 · 待办", color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // coui 玻璃概览卡：本学期课程数 + 待交作业数
            KewuGlassCard(
                backdrop = pageBackdrop,
                shape = RoundedCornerShape(16.dp),
                blurRadius = 20.dp,
                tintAlpha = 0.30f,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("本学期课程", color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${courses.size} 门", color = cs.onSurface, fontSize = 20.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("待交作业", color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${assignments.count { !it.done }} 项",
                            color = if (assignments.any { !it.done }) KewuGreen else cs.onSurfaceVariant,
                            fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            SectionLabelCentered("课程")
            courses.forEachIndexed { i, c ->
                CourseRow(c, i) { onGoToTab(0) }
            }
            Spacer(Modifier.height(8.dp))

            // ★ 用户明确要求「作业」二字居中：用 TextAlign.Center 的居中标签
            SectionLabelCentered("作业")
            assignments.forEachIndexed { i, a ->
                AssignmentRow(a, i) { onGoToTab(2) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 居中分组标题（课程 / 作业 都水平居中）。 */
@Composable
private fun SectionLabelCentered(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    )
}

/** 课程行：名称 + 教师/下次教室 + 下次时间。点击回课堂 tab。 */
@Composable
private fun CourseRow(c: TaCourse, index: Int = 0, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 10.dp)
            .taskEnter(false, index)
            .squircleCard(16.dp, cs.surfaceVariant)
            .hapticClickable { onClick() }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.name, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Spacer(Modifier.height(3.dp))
                Text("${c.teacher} · 下次 ${c.nextRoom}", color = cs.onSurfaceVariant, fontSize = 12.5.sp, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(c.nextTime, color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text("下节课", color = cs.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

/** 作业行：标题 + 课程/截止 + 待交/已交标签。点击跳任务 tab。 */
@Composable
private fun AssignmentRow(a: TaAssignment, index: Int = 0, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 10.dp)
            .taskEnter(false, index)
            .squircleCard(16.dp, cs.surfaceVariant)
            .hapticClickable { onClick() }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    a.title,
                    color = if (a.done) cs.onSurfaceVariant else cs.onSurface,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    textDecoration = if (a.done) TextDecoration.LineThrough else null,
                )
                Spacer(Modifier.height(3.dp))
                Text("${a.course} · 截止 ${a.due}", color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            if (a.done) {
                StatusTag("已交", cs.primaryContainer, cs.onPrimaryContainer)
            } else {
                StatusTag("待交", cs.secondaryContainer, cs.onSecondaryContainer)
            }
        }
    }
}

// ───────────────────────── mock 数据 ─────────────────────────

private data class TaCourse(
    val name: String,
    val teacher: String,
    val nextRoom: String,
    val nextTime: String,
)

private data class TaAssignment(
    val title: String,
    val course: String,
    val due: String,
    val done: Boolean,
)

private fun mockTaCourses() = listOf(
    TaCourse("高等数学", "张教授", "理科楼 A201", "明天 08:00"),
    TaCourse("线性代数", "李教授", "理科楼 B305", "明天 10:00"),
    TaCourse("大学英语", "王老师", "外语楼 C102", "周三 14:00"),
    TaCourse("程序设计", "赵老师", "实验楼 D401", "周四 16:00"),
)

private fun mockTaAssignments() = listOf(
    TaAssignment("习题 3.2 · 特征值与特征向量", "线性代数", "今天 23:59", false),
    TaAssignment("背诵模仿 Unit 3", "大学英语", "明天 18:00", false),
    TaAssignment("实验报告二 · 排序算法对比", "程序设计", "10月20日", false),
    TaAssignment("作业 5 · 多元函数微分", "高等数学", "10月14日", true),
)
