package com.kewu.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.KewuGreen
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop

// ───────────────────────── 数据模型（mock） ─────────────────────────

/** 一节课的状态：已上过 / 正在上课 / 未开始 */
private enum class SlotState { ATTENDED, LIVE, UPCOMING }

private data class ClassSlot(
    val name: String,
    val room: String,
    val teacher: String,
    val start: String,
    val end: String,
    val state: SlotState,
)

private data class DaySchedule(
    val label: String,          // 周一 .. 周日
    val date: String,           // 10/12
    val isToday: Boolean = false,
    val slots: List<ClassSlot>,
)

/** mock：2026 年第 7 周（10/12 - 10/18），今天 = 周五 10/16。 */
private fun mockWeek(): List<DaySchedule> = listOf(
    DaySchedule("周一", "10/12", slots = listOf(
        ClassSlot("高等数学", "主楼 305", "王老师", "08:00", "09:40", SlotState.ATTENDED),
        ClassSlot("大学英语", "外语楼 202", "李老师", "10:00", "11:40", SlotState.ATTENDED),
        ClassSlot("线性代数", "主楼 401", "张老师", "14:00", "15:40", SlotState.ATTENDED),
    )),
    DaySchedule("周二", "10/13", slots = listOf(
        ClassSlot("大学物理", "实验楼 110", "陈老师", "08:00", "09:40", SlotState.ATTENDED),
        ClassSlot("马克思主义原理", "主楼 210", "赵老师", "10:00", "11:40", SlotState.ATTENDED),
        ClassSlot("体育", "体育馆", "周老师", "16:00", "17:40", SlotState.ATTENDED),
    )),
    DaySchedule("周三", "10/14", slots = listOf(
        ClassSlot("高等数学", "主楼 305", "王老师", "08:00", "09:40", SlotState.ATTENDED),
        ClassSlot("大学英语", "外语楼 202", "李老师", "10:00", "11:40", SlotState.ATTENDED),
        ClassSlot("程序设计", "信息楼 507", "刘老师", "14:00", "15:40", SlotState.UPCOMING),
    )),
    DaySchedule("周四", "10/15", slots = listOf(
        ClassSlot("线性代数", "主楼 401", "张老师", "08:00", "09:40", SlotState.ATTENDED),
        ClassSlot("大学物理", "实验楼 110", "陈老师", "10:00", "11:40", SlotState.ATTENDED),
        ClassSlot("马克思主义原理", "主楼 210", "赵老师", "14:00", "15:40", SlotState.UPCOMING),
    )),
    DaySchedule("周五", "10/16", isToday = true, slots = listOf(
        ClassSlot("高等数学", "主楼 305", "王老师", "08:00", "09:40", SlotState.LIVE),
        ClassSlot("大学英语", "外语楼 202", "李老师", "10:00", "11:40", SlotState.UPCOMING),
        ClassSlot("线性代数", "主楼 401", "张老师", "14:00", "15:40", SlotState.UPCOMING),
    )),
    DaySchedule("周六", "10/17", slots = listOf(
        ClassSlot("程序设计实验", "信息楼 507", "刘老师", "09:00", "11:30", SlotState.UPCOMING),
    )),
    DaySchedule("周日", "10/18", slots = emptyList()),
)

// ───────────────────────── 课表页 ─────────────────────────

/**
 * 课表页：由课堂页「今日课程」小卡进入，查看一周排课。
 *
 * 版式 = Mishka（squircle 卡片、12dp 间距、卡内 16dp、状态用标志性图形）；
 * coui 用在**浮动的玻璃日条**上：日条浮在列表上方，列表滚动时从它下面穿过，
 * 被 blur + tint 成磨砂质感 —— 这是本 app 的招牌质感。
 *
 * ★ coui 硬约束：玻璃日条（采样者）必须放在被采集层**之外**。所以结构是
 * 「平色底 → 采集层（layerBackdrop 包住滚动列表）→ 玻璃日条（与采集层平级）」
 * 三层平铺，日条不是列表的子节点。
 */
@Composable
fun TimetablePage(onBack: () -> Unit, onNavHide: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val week = remember { mockWeek() }
    var selected by remember { mutableIntStateOf(4) } // 默认选中今天（周五）
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
        // 1) 平色底（M3 语义色）。不挂 backdrop —— 玻璃日条要采到的是滚动列表。
        Box(Modifier.matchParentSize().background(cs.background))

        Column(Modifier.fillMaxSize()) {
            TimetableHeader(onBack)
            // 2) 列表区
            Box(Modifier.fillMaxWidth().weight(1f)) {
                // 采集层：把滚动列表录进 backdrop，供上层玻璃日条采样
                Box(Modifier.matchParentSize().layerBackdrop(pageBackdrop)) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(horizontal = 12.dp)
                            // 顶部让位给浮动玻璃日条（62dp + 间距）
                            .padding(top = 74.dp, bottom = 96.dp),
                    ) {
                        // 按天切换：横推 + 淡入（iOS 式推页）；切天顺手滚回顶，
                        // 不然新的一天从旧滚动位置开始，课程卡会"闪没"。
                        LaunchedEffect(selected) {
                            if (scrollState.value > 0) scrollState.animateScrollTo(0)
                        }
                        AnimatedContent(
                            targetState = selected,
                            transitionSpec = {
                                if (targetState > initialState) {
                                    (slideInHorizontally(spring(dampingRatio = 0.8f, stiffness = 400f)) { w -> w } + fadeIn(tween(220))) togetherWith
                                        (slideOutHorizontally(spring(dampingRatio = 0.8f, stiffness = 400f)) { w -> -w / 4 } + fadeOut(tween(180)))
                                } else {
                                    (slideInHorizontally(spring(dampingRatio = 0.8f, stiffness = 400f)) { w -> -w / 4 } + fadeIn(tween(220))) togetherWith
                                        (slideOutHorizontally(spring(dampingRatio = 0.8f, stiffness = 400f)) { w -> w } + fadeOut(tween(180)))
                                }
                            },
                            label = "daySlots",
                        ) { sel ->
                            val d = week[sel]
                            Column {
                                if (d.slots.isEmpty()) {
                                    EmptyDayCard()
                                } else {
                                    d.slots.forEachIndexed { i, slot -> ClassBlock(slot, i) }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
                // 3) 浮动玻璃日条：与采集层平级（在采集层之外）✓
                DayStrip(
                    week = week,
                    selected = selected,
                    onSelect = { selected = it },
                    backdrop = pageBackdrop,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun TimetableHeader(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    // 右侧小绿点呼吸（今天有课在录）：alpha + 缩放双通道，1.1s 一个来回
    val dotPulse by rememberInfiniteTransition().animateFloat(
        0.65f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "dotPulse",
    )
    // 返回键按压反馈
    val backInteraction = remember { MutableInteractionSource() }
    val backPressed by backInteraction.collectIsPressedAsState()
    val backScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (backPressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 600f),
        label = "backScale",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 返回键
        Box(
            Modifier
                .size(36.dp)
                .scale(backScale)
                .clip(CircleShape)
                .background(cs.surfaceVariant)
                .clickable(interactionSource = backInteraction) { onBack() },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(15.dp)) {
                val sw = size.width * 0.14f
                val p = Path().apply {
                    moveTo(size.width * 0.62f, size.height * 0.18f)
                    lineTo(size.width * 0.34f, size.height * 0.5f)
                    lineTo(size.width * 0.62f, size.height * 0.82f)
                }
                drawPath(
                    p, cs.onSurfaceVariant,
                    style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "课表", color = cs.onSurface, fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
            Text(
                "第 7 周 · 10月12日 - 10月18日", color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1,
            )
        }
        // 右侧小绿点：今天有课在录
        Box(
            Modifier
                .size(8.dp)
                .scale(0.75f + 0.25f * dotPulse)
                .alpha(0.55f + 0.45f * dotPulse)
                .background(KewuGreen, CircleShape),
        )
    }
}

/** 浮动玻璃日条：7 天胶囊，选中日用 primary 实底，今天显示「今天」。
 *  选中态由一个**滑块**承担：spring 滑到目标 chip（不是选中 chip 瞬移变色），
 *  文字颜色再 tween 跟上 —— 观感是「一坨高亮滑过去」，而不是「哪块突然亮了」。 */
@Composable
private fun DayStrip(
    week: List<DaySchedule>,
    selected: Int,
    onSelect: (Int) -> Unit,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    KewuGlassCard(
        backdrop = backdrop,
        shape = RoundedCornerShape(50),
        blurRadius = 16.dp,
        tintAlpha = 0.16f,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(62.dp),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 5.dp, vertical = 5.dp),
        ) {
            val gap = 3.dp
            val chipW = (maxWidth - gap * (week.size - 1)) / week.size
            // 滑块 x：spring 滑到选中 chip（低阻尼 → 有过冲的"滑"感）
            val sliderX by animateDpAsState(
                targetValue = (chipW + gap) * selected,
                animationSpec = spring(dampingRatio = 0.62f, stiffness = 420f),
                label = "daySlider",
            )
            Box(
                Modifier
                    .offset(x = sliderX)
                    .width(chipW)
                    .fillMaxHeight()
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(50))
                    .background(cs.primary),
            )
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                week.forEachIndexed { i, d ->
                    val sel = i == selected
                    val topColor by animateColorAsState(
                        targetValue = if (sel) cs.onPrimary else cs.onSurfaceVariant,
                        animationSpec = tween(durationMillis = 220),
                        label = "dayTop$i",
                    )
                    val dateColor by animateColorAsState(
                        targetValue = if (sel) cs.onPrimary else cs.onSurface,
                        animationSpec = tween(durationMillis = 220),
                        label = "dayDate$i",
                    )
                    Box(
                        Modifier
                            .width(chipW)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(50))
                            .clickable { onSelect(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (d.isToday) "今天" else d.label,
                                color = topColor,
                                fontSize = 11.sp, maxLines = 1,
                            )
                            Text(
                                d.date,
                                color = dateColor,
                                fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 单节课卡片（Mishka squircle）：徽章 + 时间 + 课程信息 + 状态标签。 */
@Composable
private fun ClassBlock(slot: ClassSlot, index: Int) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .squircleCard(16.dp, cs.surfaceVariant)
            .cardEnter(index),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SlotBadge(slot.state)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        slot.name, color = cs.onSurface, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1,
                    )
                    Text(
                        "${slot.start} - ${slot.end}", color = cs.onSurfaceVariant,
                        fontSize = 13.sp, maxLines = 1,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    "${slot.room} · ${slot.teacher}",
                    color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1,
                )
                Spacer(Modifier.height(8.dp))
                when (slot.state) {
                    SlotState.ATTENDED -> StatusTag("已上过", cs.secondaryContainer, cs.onSecondaryContainer)
                    SlotState.LIVE -> StatusTag("正在上课", cs.tertiaryContainer, cs.onTertiaryContainer)
                    SlotState.UPCOMING -> StatusTag("未开始", cs.surfaceVariant, cs.onSurfaceVariant)
                }
            }
        }
    }
}

/** 状态徽章：与课堂页课程卡同一套「标志性图形语言」。 */
@Composable
private fun SlotBadge(state: SlotState) {
    val cs = MaterialTheme.colorScheme
    when (state) {
        // 已上过：大半圆 + 绿勾
        SlotState.ATTENDED -> ThreeQuarterCheck(size = 28.dp, color = KewuGreen)
        SlotState.LIVE -> {
            val pulse by rememberInfiniteTransition().animateFloat(
                0.65f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse),
            )
            Canvas(Modifier.size(28.dp)) {
                drawCircle(color = cs.tertiary.copy(alpha = 0.22f * pulse + 0.1f))
                drawCircle(color = cs.tertiary, radius = size.width * 0.17f * pulse)
            }
        }
        SlotState.UPCOMING -> Canvas(Modifier.size(28.dp)) {
            drawCircle(
                color = cs.onSurfaceVariant.copy(alpha = 0.45f),
                style = Stroke(width = size.width * 0.09f),
            )
        }
    }
}

/** 周日空态：一张 squircle 卡 + 灰色标志性图形。 */
@Composable
private fun EmptyDayCard() {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .squircleCard(16.dp, cs.surfaceVariant)
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ThreeQuarterCheck(size = 44.dp, color = cs.onSurfaceVariant, alpha = 0.35f)
            Spacer(Modifier.height(14.dp))
            Text(
                "周日没有安排", color = cs.onSurface, fontSize = 14.sp,
                fontWeight = FontWeight.Medium, maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "好好休息，或提前预习下周课程", color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1,
            )
        }
    }
}
