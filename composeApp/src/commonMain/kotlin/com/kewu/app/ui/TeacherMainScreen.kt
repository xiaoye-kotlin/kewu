package com.kewu.app.ui

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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.HapticKind
import com.kewu.app.KewuBlue
import com.kewu.app.haptic
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
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
    var lastPreviewHaptic by remember { mutableStateOf(-1) }
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
                centerAnim.animateTo(
                    targetValue = targetPx,
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 260f),
                    initialVelocity = centerAnim.velocity.coerceIn(-1800f, 1800f),
                )
            }
        }
    }
    val cancelBubble: () -> Unit = { bubbleJob?.cancel() }
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

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TeacherTopBar(
                Modifier.offset { IntOffset(0, ((1f - enterT) * -14f).dp.roundToPx()) }.alpha(enterT),
            )
            // 内容区：只渲染当前选中页（coui 采样安全），横滑切页（横向主导手势）
            val n = TEACHER_NAV_ITEMS.size
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .layerBackdrop(backdrop)
                    .pointerInput(selected) {
                        var acc = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { acc = 0f },
                            onHorizontalDrag = { _, dragAmount -> acc += dragAmount },
                            onDragEnd = {
                                val threshold = 96f
                                if (acc <= -threshold) goToTab((selected + 1).coerceAtMost(n - 1))
                                else if (acc >= threshold) goToTab((selected - 1).coerceAtLeast(0))
                            },
                        )
                    },
            ) {
                Box(Modifier.fillMaxSize()) {
                    when (selected) {
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
                .height(24.dp)
                .align(Alignment.BottomCenter)
                .background(MaterialTheme.colorScheme.background),
        )
        AppleLiquidGlassNavBar(
            items = TEACHER_NAV_ITEMS,
            selected = selected,
            onSelect = {
                haptic(HapticKind.Light)
                selected = it
                preview = it
                navHidden = false
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
            tabW = tabW,
            centerAnim = centerAnim,
            onSlideTo = slideBubbleTo,
            onGestureStart = cancelBubble,
            modifier = Modifier
                .align(Alignment.BottomCenter)
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
                .padding(bottom = (24 + 66 + 16).dp),
        )
    }
}

/** 教师端顶栏：课字标 + 「课悟 · 教师端」（深蓝徽章区分角色，与学生端视觉区分）。 */
@Composable
private fun TeacherTopBar(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(cs.surface),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(36.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(KewuBlue),
                contentAlignment = Alignment.Center,
            ) { Text("课", color = cs.onPrimary, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(12.dp))
            Text("课悟 · Kewu", color = cs.onSurface, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(KewuBlue.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text("教师端", color = KewuBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
