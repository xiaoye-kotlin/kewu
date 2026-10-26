package com.kewu.app.ui

import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import com.kewu.app.TabSwipeLock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.imePadding
import kotlinx.coroutines.delay
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.AiNav
import com.kewu.app.ChatHistory
import com.kewu.app.AssistantHub
import com.kewu.app.HapticKind
import com.kewu.app.haptic
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ───────────────────────── 教师端主框架（3 tab，与学生端同款液态玻璃导航） ─────────────────────────
//
// 设计原则（用户定调）：教师端「极简、重控制、看数据」，3 个 tab：控制台 / 作业 / 我的。
// 底部导航完全复用学生端的 AppleLiquidGlassNavBar（托盘 + 泡泡滑行 + 彩虹镶边 + 逐栏震动），
// tab 图标用自绘 glyph：控制台=房子(0)、作业=清单(2)、我的=人形(3)（NavItem.index 决定图形）。
//
// coui 硬约束与学生端一致：
// - 内容区只渲染当前选中单页（绝不用 Row 并排多页 + offset），切页只换 index；
// - 内容区是导航栏玻璃托盘的采样层（layerBackdrop），导航栏与之平级；
// - 入场/收起动画只动 placement 位移（lambda offset）+ 导航栏自身的 alpha/scale（生产已验证）。
// 2026-10-23 全局 coui 化：底色 / 顶栏改读 COUITheme，导航栏（液态玻璃）原样不动。
// 2026-10-24 按用户要求移除顶部「课悟 · Kewu」品牌横幅，内容从状态栏下直接开始，不挡视野。

private val TEACHER_NAV_ITEMS = listOf(
    NavItem(0, "控制台"),
    NavItem(1, "助教"),
    NavItem(2, "作业"),
    NavItem(3, "我的"),
)

@Composable
fun TeacherMainScreen(
    onLogout: () -> Unit = {},
) {
    var selected by remember { mutableStateOf(0) }
    var preview by remember { mutableStateOf(0) }
    var previewFloat by remember { mutableStateOf(0f) }
    var navHidden by remember { mutableStateOf(false) }
    // 下钻子页上锁时暂停内容区横滑切 tab（见 TabSwipeLock）
    val swipeLock by TabSwipeLock.count.collectAsState()
    var lastPreviewHaptic by remember { mutableStateOf(-1) }
    // 助教（index=1）tab 的 AI 未读红点：仅统计 ChatHistory 里晚于「已读游标」的 assistant 消息，
    // 与首页铃铛通知完全无关（小悟消息在助教页内读，不进铃铛）。进入助教页即清空。
    // 2026-10-26：未读数改由 AssistantHub 统一维护（在助教页里看着回答的不算未读；切走后才到的、
    // 以及小悟主动发来的消息才算）。每分钟重算一次，跨天时教师端「今日简报未读」能自动出现。
    val assistantUnread by AssistantHub.unread(ChatHistory.KEY_TEACHER).collectAsState()
    LaunchedEffect(Unit) {
        ChatHistory.seedWelcomeIfEmpty(ChatHistory.KEY_TEACHER)
        AssistantHub.refreshUnread(ChatHistory.KEY_TEACHER)
        launch {
            while (true) {
                delay(60_000)
                AssistantHub.refreshUnread(ChatHistory.KEY_TEACHER)
            }
        }
    }
    DisposableEffect(Unit) { onDispose { AssistantHub.setVisible(ChatHistory.KEY_TEACHER, false) } }
    val navScope = rememberCoroutineScope()
    val tabW = remember { mutableStateOf(0f) }
    val centerAnim = remember { Animatable(0f) }
    var tabSwitchJob by remember { mutableStateOf<Job?>(null) }
    // 泡泡滑行的唯一驾驶协程（与学生端同款：新目标取消旧任务、携带当前速度改奔新目标，连点不瞬移）
    var bubbleJob by remember { mutableStateOf<Job?>(null) }
    val slideBubbleTo: (Float) -> Unit = { targetPx ->
        if (tabW.value > 0f) {
            bubbleJob?.cancel()
            bubbleJob = navScope.launch {
                // 与学生端 slideBubbleTo 完全一致：逐栏轻震 + 被打断停在半路时补滑到位
                centerAnim.slidePassHaptic(
                    targetValue = targetPx,
                    tabW = tabW.value,
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 260f),
                    initialVelocity = centerAnim.velocity.coerceIn(-1800f, 1800f),
                ) { haptic(HapticKind.Tick) }
                if (kotlin.math.abs(centerAnim.value - targetPx) > 1f) {
                    centerAnim.animateTo(
                        targetValue = targetPx,
                        animationSpec = spring(dampingRatio = 0.85f, stiffness = 260f),
                    )
                }
            }
        }
    }
    val cancelBubble: () -> Unit = { bubbleJob?.cancel() }
    // 内容区拖动切页时泡泡跟手（同学生端 MainScreen.swipeBubble）
    val swipeBubble: (Int, Int?, Float) -> Unit = { from, to, p ->
        if (tabW.value > 0f) {
            val pos = if (to == null) from.toFloat() else from + (to - from) * p
            bubbleJob?.cancel()
            bubbleJob = navScope.launch { centerAnim.snapTo(pos * tabW.value + tabW.value / 2f) }
        }
    }
    val navVisible by animateFloatAsState(
        targetValue = if (navHidden) 0f else 1f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = 300f),
        label = "teacherNavVisible",
    )
    val backdrop = rememberGlassBackdrop()
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val enterT by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "teacherEnter",
    )
    val goToTab: (Int) -> Unit = { idx ->
        if (idx in TEACHER_NAV_ITEMS.indices) {
            selected = idx
            preview = idx
            navHidden = false
            // ★ 助教（index=1）进入即清空 AI 未读红点；切走则重新统计
            AssistantHub.setVisible(ChatHistory.KEY_TEACHER, idx == 1)
            slideBubbleTo(idx * tabW.value + tabW.value / 2f)
            tabSwitchJob?.cancel()
            tabSwitchJob = navScope.launch {
                val anim = Animatable(previewFloat)
                anim.animateTo(
                    targetValue = idx.toFloat(),
                    animationSpec = spring(dampingRatio = 0.68f, stiffness = 420f),
                ) { previewFloat = value }
            }
        }
    }

    // 2026-10-24：AI 助教代操作 —— 小悟的导航指令经 AiNav 总线到这里执行切 tab
    LaunchedEffect(Unit) {
        AiNav.teacherTab.collect { idx ->
            if (idx in TEACHER_NAV_ITEMS.indices) {
                goToTab(idx)
                AiNav.teacherTab.value = -1
            }
        }
    }

    val navExtra = rememberNavExtraInset()
    Box(
        Modifier
            .fillMaxSize()
            .background(COUITheme.colorScheme.surface),
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding()
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
        ) {
            // 内容区：只渲染当前选中页（coui 采样安全），横滑切页（横向主导手势）
            // 注：顶部「课悟 · Kewu」品牌横幅已按用户要求移除，内容从状态栏下直接开始，不挡视野。
            val n = TEACHER_NAV_ITEMS.size
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // ★ 2026-10-26 全局输入法避让（同学生端 MainScreen）：内容区随键盘收缩，聚焦输入框自动进入可视区
                    .imePadding()
                    // ★ 2026-10-26 edge-to-edge：三键导航等较高的系统导航栏，整体把内容区与底栏一起抬高（手势条机型为 0）
                    .padding(bottom = navExtra)
                    .layerBackdrop(backdrop),
            ) {
                // ★ 2026-10-26 Telegram 式切页（同学生端 MainScreen）：tab 间整页跟手联动 + 两端橡皮筋回弹，
                //   泡泡跟着手指走。下钻子页（发布 / 批阅 / 详情…）上锁时暂停，避免一次斜滑丢掉草稿与批阅进度。
                KewuSlideHost(
                    targetState = selected,
                    modifier = Modifier.fillMaxSize(),
                    style = SlideStyle.Pager,
                    isForward = { a, b -> b > a },
                    swipeEnabled = swipeLock == 0,
                    swipeNeighbor = { from, d -> (from + d).takeIf { it in 0 until n } },
                    onSwipeCommit = { goToTab(it) },
                    onSwipeProgress = { from, to, p ->
                        swipeBubble(from, to, p)
                        val pv = (if (to == null) from.toFloat() else from + (to - from) * p).roundToInt()
                        if (pv != preview) {
                            haptic(HapticKind.Tick)
                            preview = pv
                        }
                    },
                    onSwipeEnd = { to ->
                        if (to == null) {
                            preview = selected
                            slideBubbleTo(selected * tabW.value + tabW.value / 2f)
                        }
                    },
                ) { idx ->
                    when (idx) {
                        0 -> TeacherConsolePage(onGoToTab = { goToTab(it) }, onNavHide = { navHidden = it })
                        1 -> TeacherAssistantPage(onNavHide = { navHidden = it })
                        2 -> TeacherTaskPage(onNavHide = { navHidden = it })
                        else -> TeacherMyPage(onLogout = onLogout, onNavHide = { navHidden = it })
                    }
                }
            }
        }
        // 底部 24dp 幕布（与学生端同款：遮住伸向系统手势条空隙的内容，小白条悬在纯色上）
        Box(
            Modifier
                .fillMaxWidth()
                .height(24.dp + navExtra)
                .align(Alignment.BottomCenter)
                .background(COUITheme.colorScheme.surface),
        )
        AppleLiquidGlassNavBar(
            items = TEACHER_NAV_ITEMS,
            selected = selected,
            onSelect = {
                haptic(HapticKind.Light)
                selected = it
                preview = it
                navHidden = false
                // ★ 助教（index=1）进入即清空 AI 未读红点；切走则重新统计
                AssistantHub.setVisible(ChatHistory.KEY_TEACHER, it == 1)
                tabSwitchJob?.cancel()
                tabSwitchJob = navScope.launch {
                    val anim = Animatable(previewFloat)
                    anim.animateTo(
                        targetValue = it.toFloat(),
                        animationSpec = spring(dampingRatio = 0.68f, stiffness = 420f),
                    ) { previewFloat = value }
                }
            },
            preview = preview,
            onPreview = {
                if (it != lastPreviewHaptic) {
                    lastPreviewHaptic = it
                    haptic(HapticKind.Tick)
                }
                preview = it
            },
            previewFloat = previewFloat,
            onPreviewFloat = {
                tabSwitchJob?.cancel()
                previewFloat = it
            },
            backdrop = backdrop,
            hidden = navHidden,
            badges = mapOf(1 to assistantUnread),
            tabW = tabW,
            centerAnim = centerAnim,
            onSlideTo = slideBubbleTo,
            onGestureStart = cancelBubble,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = navExtra)
                .scale((0.35f + 0.65f * navVisible).coerceAtLeast(0f))
                .alpha(navVisible.coerceIn(0f, 1f))
                .offset {
                    IntOffset(
                        0,
                        (((1f - enterT) * 40f).dp.roundToPx()) + (((1f - navVisible) * 14f).dp.roundToPx()),
                    )
                },
        )
        // 一级界面双击返回退出（必须是根 Box 最后一个子节点，绘制在最上层）
        DoubleBackExitHint(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = (24 + 66 + 16).dp + navExtra),
        )
    }
}


