package com.kewu.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.BackHandler
import com.kewu.app.FocusStatusInfo
import com.kewu.app.FocusSuggestionInfo
import com.kewu.app.FocusVisualConfigInfo
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuGreen
import com.kewu.app.haptic
import com.kewu.app.ui.liquidglass.KewuGlassCard
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.ProgressIndicatorDefaults
import io.github.suqi8.coui.kmp.basic.Slider
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * 专注番茄钟（2026-11-16）：后端 7 个 focus 接口的前端协调器。
 *
 * 两个界面（phase）：
 *  - SETUP：设置页（普通 COUI 灰底），自由调节时长（1-120 分钟 Slider）+ AI 建议；
 *  - SESSION：点「开始专注」后进入的**沉浸式新界面**，全屏动态背景 FocusBackground（AI visual_config 三态）
 *    + 大倒计时 + 分心/完成/放弃/跳过休息。背景只在这一层，不再是整页蒙底。
 *
 * 开始转场：点「开始专注」时 [reveal] 从 0 动画到 1，SESSION 内容用 clipPath 圆形从中心扩散到覆盖全屏
 * （半径 = 屏幕对角线/2 × reveal），像一圈光把新界面「揭示」出来。
 *
 * 时间权威：页面不记 startTime，只用 /status 的 elapsedSeconds / restRemainingSeconds 叠加本地秒 tick 展示。
 */
@Composable
fun FocusTimerPage(
    pageBackdrop: LayerBackdrop,
    onBack: () -> Unit,
    onNavHide: (Boolean) -> Unit = {},
) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()

    // ── 后端状态 ──
    var status by remember { mutableStateOf<FocusStatusInfo?>(null) }
    var suggestion by remember { mutableStateOf<FocusSuggestionInfo?>(null) }
    var loading by remember { mutableStateOf(true) }
    var baseTick by remember { mutableStateOf(0) }
    var tick by remember { mutableStateOf(0) }

    // ── 界面状态 ──
    var phase by remember { mutableStateOf(FocusPhase.SETUP) }
    var planned by remember { mutableStateOf(25f) }       // 自由时长（分钟），Slider 用浮点，传给后端取整
    var notice by remember { mutableStateOf<String?>(null) }
    val reveal = remember { Animatable(0f) }

    // 每秒展示 tick（不参与业务时间）
    // ★ 修复（2026-11-19）：只在沉浸页才需要每秒刷新；原实现用 LaunchedEffect(Unit) 在 SETUP 页也每秒 tick，
    //   导致设置页无意义重组（选时动画被干扰 / 耗电）。以 phase 为 key，离开沉浸页即取消协程。
    LaunchedEffect(phase) {
        if (phase != FocusPhase.SESSION) return@LaunchedEffect
        while (true) { delay(1000); tick++ }
    }

    suspend fun refreshStatus(): FocusStatusInfo? {
        val s = KewuApi.focusStatus()
        status = s
        baseTick = tick
        if (s?.focusing == true && s.plannedMinutes > 0) planned = s.plannedMinutes.toFloat()
        loading = false
        return s
    }

    LaunchedEffect(Unit) {
        val s = refreshStatus()
        suggestion = KewuApi.focusSuggestion()
        // 若后端已有进行中的会话（例如上次没结束），直接进入沉浸界面
        if (s?.focusing == true || s?.resting == true) {
            phase = FocusPhase.SESSION
            reveal.snapTo(1f)
        }
    }

    // 沉浸界面内的返回：先退回设置页（若后端仍在进行，提示去完成/取消）
    // ★ 修复（2026-11-19）：退出沉浸页但后端会话仍在跑时，原来静默退回，
    //   用户再点「开始」会被后端拒绝且不知原因。现在明确提示会话仍在进行。
    fun leaveSession() {
        phase = FocusPhase.SETUP
        scope.launch { reveal.snapTo(0f) }
        if (status?.focusing == true || status?.resting == true) {
            notice = "本次专注仍在进行中，可继续专注或在会话中结束"
        }
    }

    BackHandler {
        if (phase == FocusPhase.SESSION) leaveSession() else onBack()
    }

    // 进入沉浸式专注界面时隐藏底部导航栏，回到设置页 / 离开时恢复
    LaunchedEffect(phase) { onNavHide(phase == FocusPhase.SESSION) }
    DisposableEffect(Unit) {
        onDispose { onNavHide(false) }
    }

    Box(Modifier.fillMaxSize()) {
        when (phase) {
            FocusPhase.SETUP -> {
                FocusSetupScreen(
                    pageBackdrop = pageBackdrop,
                    planned = planned,
                    onPlannedChange = { planned = it },
                    suggestion = suggestion,
                    notice = notice,
                    onNotice = { notice = it },
                    onBack = onBack,
                    onStart = {
                        scope.launch {
                            val err = KewuApi.focusStart(planned.toInt().coerceIn(1, 120))
                            if (err != null) {
                                notice = err
                            } else {
                                haptic(HapticKind.Light)
                                refreshStatus()
                                phase = FocusPhase.SESSION
                                // 中心扩散揭示动画
                                reveal.snapTo(0f)
                                reveal.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
                            }
                        }
                    },
                )
            }
            FocusPhase.SESSION -> {
                // 圆形从中心扩散：DrawScope.size 求对角线半径，clipPath 裁出圆，圆内才画沉浸界面
                val radiusPx = reveal.value
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            if (reveal.value >= 0.999f) {
                                drawContent() // 动画结束后走快路径，省掉每帧裁剪
                            } else {
                                val c = Offset(size.width / 2f, size.height / 2f)
                                val maxR = hypot(size.width, size.height) / 2f + 4f
                                val r = maxR * reveal.value
                                val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
                                clipPath(circle, clipOp = ClipOp.Intersect) { this@drawWithContent.drawContent() }
                            }
                        }
                ) {
                    FocusSessionScreen(
                        status = status,
                        suggestion = suggestion,
                        plannedMinutes = planned.toInt().coerceIn(1, 120),
                        tick = tick,
                        baseTick = baseTick,
                        loading = loading,
                        notice = notice,
                        onNotice = { notice = it },
                        onBack = { leaveSession() },
                        onRefresh = { scope.launch { refreshStatus() } },
                        onSuggestionReload = { scope.launch { suggestion = KewuApi.focusSuggestion() } },
                        onExited = { phase = FocusPhase.SETUP; scope.launch { reveal.snapTo(0f) } },
                    )
                }
            }
        }
    }
}

private enum class FocusPhase { SETUP, SESSION }

/**
 * 设置页：普通 COUI 灰底（不铺动态背景），自由调节时长 + AI 建议。
 */
@Composable
private fun FocusSetupScreen(
    pageBackdrop: LayerBackdrop,
    planned: Float,
    onPlannedChange: (Float) -> Unit,
    suggestion: FocusSuggestionInfo?,
    notice: String?,
    onNotice: (String?) -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = KewuNavClearance),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            KewuSecondaryButton("‹ 返回", onBack, Modifier.height(36.dp))
            Spacer(Modifier.weight(1f))
            Text("专注番茄钟", color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(36.dp))
        }
        Spacer(Modifier.height(14.dp))

        // ── 自由时长卡（液态玻璃，沿用原有风格）──
        KewuGlassCard(
            backdrop = pageBackdrop,
            shape = RoundedCornerShape(18.dp),
            blurRadius = 20.dp,
            tintAlpha = 0.28f,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("本次专注时长", color = cs.onSurfaceSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(2.dp))
                // 分钟数字：数值变化时弹簧「弹一下」+ 轻微上滑淡入，选时更有质感（用户 2026-11-19 需求）
                val minute = planned.toInt()
                val bump = remember { Animatable(1f) }
                val minuteFade = remember { Animatable(1f) }
                LaunchedEffect(minute) {
                    // 先放大再回弹（弹簧），同时从下方 8px 淡入
                    bump.snapTo(1.18f)
                    minuteFade.snapTo(0f)
                    launch {
                        bump.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 380f))
                    }
                    minuteFade.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "$minute", color = KewuGreen,
                        fontSize = 40.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.graphicsLayer {
                            scaleX = bump.value
                            scaleY = bump.value
                            translationY = (1f - minuteFade.value) * 8f
                            alpha = 0.4f + minuteFade.value * 0.6f
                        },
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("分钟", color = KewuGreen, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 6.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "自由调节 1–120 分钟，找到属于你的节奏",
                    color = cs.onSurfaceSecondary, fontSize = 12.sp,
                )
                Spacer(Modifier.height(10.dp))
                // COUI Slider：步长 1 分钟，value 0f..1f 映射到 1..120
                Slider(
                    value = ((planned - 1f) / 119f).coerceIn(0f, 1f),
                    onValueChange = { frac -> onPlannedChange((1f + frac * 119f).coerceIn(1f, 120f)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("1 分钟", color = cs.onSurfaceSecondary, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    Text("120 分钟", color = cs.onSurfaceSecondary, fontSize = 11.sp)
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("快捷：", color = cs.onSurfaceSecondary, fontSize = 12.sp)
                    Spacer(Modifier.size(6.dp))
                    listOf(15, 25, 45, 60).forEach { m ->
                        FocusQuickChip(
                            label = "$m",
                            selected = minute == m,
                            onClick = { haptic(HapticKind.Tick); onPlannedChange(m.toFloat()) },
                        )
                        Spacer(Modifier.size(6.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        KewuPrimaryButton(
            "开始专注",
            onClick = {
                haptic(HapticKind.Light)
                onNotice(null)
                onStart()
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )

        if (notice != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                notice!!, color = cs.error, fontSize = 13.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }

        Spacer(Modifier.height(20.dp))
        KewuSectionTitle("AI 专注建议")
        KewuGlassCard(
            backdrop = pageBackdrop,
            shape = RoundedCornerShape(16.dp),
            blurRadius = 18.dp,
            tintAlpha = 0.24f,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                if (suggestion == null) {
                    Text("正在生成建议…", color = cs.onSurfaceSecondary, fontSize = 13.sp)
                } else {
                    val s = suggestion!!
                    Text(
                        "推荐 ${s.recommendedMinutes} 分钟 · 休息 ${s.restMinutes} 分钟",
                        color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "疲劳指数 ${s.fatigueIndex} / 100 · 来源：" + when (s.source) {
                            "ai" -> "AI 分析"; "cold_start" -> "冷启动默认"; "fallback" -> "兜底默认"; else -> s.source
                        },
                        color = cs.onSurfaceSecondary, fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(s.advice, color = cs.onSurface, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    KewuSecondaryButton(
                        "采用推荐时长",
                        onClick = { haptic(HapticKind.Tick); onPlannedChange(s.recommendedMinutes.toFloat()); onNotice("已采用 ${s.recommendedMinutes} 分钟") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 番茄钟快捷时长胶囊（2026-11-19）：按下弹簧缩放 + 选中态玻璃高亮，比朴素按钮更有质感。
 */
@Composable
private fun FocusQuickChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 700f),
        label = "quickChipScale",
    )
    val bg by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) KewuGreen else Color(0x1A000000),
        animationSpec = tween(200),
        label = "quickChipBg",
    )
    Box(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(
                0.8.dp,
                if (selected) KewuGreen else Color.White.copy(alpha = 0.16f),
                RoundedCornerShape(16.dp),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Color.White else cs.onSurface,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
        )
    }
}
