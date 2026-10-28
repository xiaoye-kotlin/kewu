package com.kewu.app.ui

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.KewuGreen
import com.kewu.app.KewuBlue
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardColors
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme

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
 * 版式 = Mishka（12dp 间距、卡内 16dp、状态用标志性图形）；2026-10-23 coui 化：
 * 顶栏 = [KewuTopBar]（coui SmallTopAppBar），课块 / 空态 = coui [Card]，文字 / 标签 = coui 原生件，
 * 配色读 [COUITheme]（页面灰底 surface + 白卡 surfaceContainer）。
 * 顶部「周一到周日」日条用 **coui 原生 Card**（未选中 = 白底 + dividerLine 描边，
 * 选中 = KewuBlue 蓝底白字），芯片仍浮在列表上方，保留浮动感。
 */
/**
 * 本周真实课表（2026-10-26 起由老师导入，替代旧示例数据）：按东八区算本周一到周日的日期，
 * 每天的课按开始时间排；今天的课按当前时间判定「已上 / 上课中 / 未开始」，之前的日子算已上。
 */
private fun buildWeek(entries: List<com.kewu.app.ScheduleEntry>): Pair<List<DaySchedule>, Int> {
    val nowMs = kotlin.time.Clock.System.now().toEpochMilliseconds() + 8 * 3_600_000L
    val dayIndex = ((nowMs / 86_400_000L + 3) % 7).toInt() // 0 = 周一
    val minuteNow = ((nowMs / 60_000L) % (24 * 60)).toInt()
    val mondayMs = nowMs - dayIndex * 86_400_000L
    fun dateLabel(i: Int): String {
        val parts = com.kewu.app.todayCn(mondayMs - 8 * 3_600_000L + i * 86_400_000L).split("-")
        return "${parts[1].toInt()}/${parts[2].toInt()}"
    }
    fun minutes(t: String) = t.split(":").let { (it.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (it.getOrNull(1)?.toIntOrNull() ?: 0) }
    val names = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    val week = (0..6).map { i ->
        val slots = entries.filter { it.weekday == i + 1 }.sortedBy { it.startTime }.map { e ->
            val state = when {
                i < dayIndex -> SlotState.ATTENDED
                i > dayIndex -> SlotState.UPCOMING
                minuteNow >= minutes(e.endTime) -> SlotState.ATTENDED
                minuteNow >= minutes(e.startTime) -> SlotState.LIVE
                else -> SlotState.UPCOMING
            }
            ClassSlot(e.courseName, e.location.ifBlank { "地点待定" }, e.teacherName.ifBlank { "老师待定" } +
                (if (e.teacherName.isNotBlank() && !e.teacherName.endsWith("老师")) "老师" else ""), e.startTime, e.endTime, state)
        }
        DaySchedule(names[i], dateLabel(i), isToday = i == dayIndex, slots = slots)
    }
    return week to dayIndex
}

@Composable
fun TimetablePage(onBack: () -> Unit, onNavHide: (Boolean) -> Unit) {
    // 2026-10-26：真实课表（老师导入），拉不到时显示空周 + 提示，不再显示示例数据
    var schedule by remember { mutableStateOf<List<com.kewu.app.ScheduleEntry>?>(null) }
    LaunchedEffect(Unit) { schedule = com.kewu.app.KewuApi.getMySchedule() ?: emptyList() }
    val built = remember(schedule) { buildWeek(schedule.orEmpty()) }
    val week = built.first
    var selected by remember { mutableIntStateOf(built.second) } // 默认选中今天
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
        // 1) 平色底（coui 页面灰底）。不挂 backdrop —— 本页已无玻璃采样者。
        Box(Modifier.matchParentSize().background(COUITheme.colorScheme.surface))

        Column(Modifier.fillMaxSize()) {
            TimetableHeader(onBack)
            // 2) 列表区
            Box(Modifier.fillMaxWidth().weight(1f)) {
                // 列表容器（日条已改为 coui Card，不再需要玻璃采集层）
                Box(Modifier.matchParentSize()) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(horizontal = 12.dp)
                            // 顶部让位给浮动日条（62dp + 间距）
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
                                    EmptyDayCard(d.label, week.all { it.slots.isEmpty() })
                                } else {
                                    d.slots.forEachIndexed { i, slot -> ClassBlock(slot, i) }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
                // 3) 浮动日条（coui Card）：与列表平级浮在其上方
                DayStrip(
                    week = week,
                    selected = selected,
                    onSelect = { selected = it },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                )
            }
        }
    }
}

/** 顶栏：coui [KewuTopBar]（标题 + 周次副标题 + coui 返回钮），右侧保留「今天有课在录」呼吸绿点。 */
@Composable
private fun TimetableHeader(onBack: () -> Unit) {
    // 右侧小绿点呼吸（今天有课在录）：alpha + 缩放双通道，1.1s 一个来回
    // 2026-10-26：动画值只在 graphicsLayer 的 lambda 里读（旧版组合阶段读，顶栏每帧重组）；这个小点不是玻璃采样者
    val dotPulse = rememberInfiniteTransition().animateFloat(
        0.65f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "dotPulse",
    )
    KewuTopBar(
        title = "课表",
        // 2026-10-26：后端还没有排课数据，本页为示例课表；旧版写死「第 7 周 · 10月12日-10月18日」像真实数据
        subtitle = "本周课表 · 由任课老师导入",
        onBack = onBack,
        actions = {
            Box(
                Modifier
                    .padding(end = 20.dp)
                    .size(8.dp)
                    .graphicsLayer {
                        val p = dotPulse.value
                        scaleX = 0.75f + 0.25f * p
                        scaleY = 0.75f + 0.25f * p
                        alpha = 0.55f + 0.45f * p
                    }
                    .background(KewuGreen, CircleShape),
            )
        },
    )
}

/**
 * 浮动日条（2026-10-19 改）：每天一枚 coui 原生 `Card` 芯片浮在列表上方，
 * 固定浅色配色 —— 未选中 = `surfaceContainer` 白底 + `dividerLine` 描边 + onSurface/onSurfaceSecondary
 * 文字；选中 = `KewuBlue` 蓝底 + 白字（选中进度弹簧过渡，不瞬变）。「今天」额外点一枚品牌绿小点。
 */
@Composable
private fun DayStrip(
    week: List<DaySchedule>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = COUITheme.colorScheme
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        week.forEachIndexed { i, d ->
            val sel = i == selected
            val p = rememberEnterProgress(i, baseDelayMs = 120L, stepMs = 45L)
            // 选中进度：蓝底 / 文字色随之弹簧过渡，不瞬变（不生硬的关键）
            val cardColor by animateColorAsState(
                targetValue = if (sel) KewuBlue else cs.surfaceContainer,
                // ★ 不能传 Float 的 visibilityThreshold（会把 spring 推导成 SpringSpec<Float>，
                //   与 AnimationSpec<Color> 不符）；用默认阈值即可，弹簧照样过冲。
                animationSpec = spring(dampingRatio = 0.62f, stiffness = 420f),
                label = "dayCardColor$i",
            )
            val topColor by animateColorAsState(
                targetValue = if (sel) Color.White else cs.onSurfaceSecondary,
                animationSpec = tween(durationMillis = 200),
                label = "dayTop$i",
            )
            val dateColor by animateColorAsState(
                targetValue = if (sel) Color.White else cs.onSurface,
                animationSpec = tween(durationMillis = 200),
                label = "dayDate$i",
            )
            val pressedColor = if (sel) {
                Color.White.copy(alpha = 0.18f)
            } else {
                cs.tertiaryContainer
            }
            // 芯片浮在列表上方：入场只做位移 + 透明度（非玻璃件可安全叠 alpha/scale）
            Box(
                Modifier
                    .weight(1f)
                    .height(62.dp)
                    .then(
                        if (!sel) {
                            Modifier.border(1.dp, cs.dividerLine, RoundedCornerShape(18.dp))
                        } else {
                            Modifier
                        },
                    )
                    .riseEnter(p, 40f),
            ) {
                Card(
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 18.dp,
                    colors = CardColors(
                        color = cardColor,
                        contentColor = dateColor,
                        pressedColor = pressedColor,
                    ),
                    onClick = { onSelect(i) },
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                if (d.isToday) "今天" else d.label,
                                color = topColor, fontSize = 11.sp, maxLines = 1,
                            )
                            Text(
                                d.date, color = dateColor,
                                fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            )
                        }
                        // 「今天」绿点：未选中时点在日期下方提示今天在哪
                        if (d.isToday && !sel) {
                            Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 5.dp)
                                    .size(4.dp)
                                    .background(KewuGreen, CircleShape),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 单节课卡片（coui Card）：徽章 + 时间 + 课程信息 + 状态标签。 */
@Composable
private fun ClassBlock(slot: ClassSlot, index: Int) {
    val cs = COUITheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .riseEnter(rememberEnterProgress(index, baseDelayMs = 200L, stepMs = 55L), 60f),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(16.dp),
        colors = kewuCardColors(),
    ) {
        Row(
            Modifier.fillMaxWidth(),
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
                        "${slot.start} - ${slot.end}", color = cs.onSurfaceSecondary,
                        fontSize = 13.sp, maxLines = 1,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    "${slot.room} · ${slot.teacher}",
                    color = cs.onSurfaceSecondary, fontSize = 13.sp, maxLines = 1,
                )
                Spacer(Modifier.height(8.dp))
                when (slot.state) {
                    SlotState.ATTENDED -> StatusTag("已上过", cs.secondaryContainer, cs.onSecondaryVariant)
                    SlotState.LIVE -> StatusTag("正在上课", cs.tertiaryContainer, cs.onTertiaryContainer)
                    SlotState.UPCOMING -> StatusTag("未开始", cs.secondaryContainer, cs.onSurfaceSecondary)
                }
            }
        }
    }
}

/** 状态徽章：与课堂页课程卡同一套「标志性图形语言」。 */
@Composable
private fun SlotBadge(state: SlotState) {
    val cs = COUITheme.colorScheme
    when (state) {
        // 已上过：大半圆 + 绿勾
        SlotState.ATTENDED -> ThreeQuarterCheck(size = 28.dp, color = KewuGreen)
        SlotState.LIVE -> {
            // 呼吸圆点：coui 无 tertiary，用主色（与课堂页课程卡 LIVE 徽章一致）
            val pulse by rememberInfiniteTransition().animateFloat(
                0.65f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse),
            )
            Canvas(Modifier.size(28.dp)) {
                drawCircle(color = cs.primary.copy(alpha = 0.22f * pulse + 0.1f))
                drawCircle(color = cs.primary, radius = size.width * 0.17f * pulse)
            }
        }
        SlotState.UPCOMING -> Canvas(Modifier.size(28.dp)) {
            drawCircle(
                color = cs.onSurfaceSecondary.copy(alpha = 0.45f),
                style = Stroke(width = size.width * 0.09f),
            )
        }
    }
}

/** 周日空态：一张 coui Card + 灰色标志性图形。 */
@Composable
private fun EmptyDayCard(dayName: String, noSchedule: Boolean) {
    val cs = COUITheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .riseEnter(rememberEnterProgress(0, baseDelayMs = 200L), 40f),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(vertical = 32.dp),
        colors = kewuCardColors(),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            ThreeQuarterCheck(size = 44.dp, color = cs.onSurfaceSecondary, alpha = 0.35f)
            Spacer(Modifier.height(14.dp))
            Text(
                "${dayName}没有安排", color = cs.onSurface, fontSize = 14.sp,
                fontWeight = FontWeight.Medium, maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (noSchedule) "还没有课表：等任课老师在班级里导入后自动出现" else "好好休息，或提前预习下周课程", color = cs.onSurfaceSecondary, fontSize = 12.sp, maxLines = 1,
            )
        }
    }
}
