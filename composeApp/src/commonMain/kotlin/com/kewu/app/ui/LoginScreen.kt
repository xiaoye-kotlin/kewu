package com.kewu.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.kewu.app.KewuBlue
import com.kewu.app.KewuGreen
import com.kewu.app.KewuSky
import io.github.suqi8.coui.kmp.blur.blur
import io.github.suqi8.coui.kmp.blur.colorControls
import io.github.suqi8.coui.kmp.blur.drawBackdrop
import io.github.suqi8.coui.kmp.blur.highlight.BloomStroke
import io.github.suqi8.coui.kmp.blur.highlight.Highlight
import io.github.suqi8.coui.kmp.blur.highlight.LightPosition
import io.github.suqi8.coui.kmp.blur.highlight.LightSource
import io.github.suqi8.coui.kmp.blur.isRuntimeShaderSupported
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.blur.rememberLayerBackdrop
import io.github.suqi8.coui.kmp.blur.runtimeShaderEffect
import io.github.suqi8.coui.kmp.blur.BackdropEffectScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin

// ───────────────────────── 登录 / 注册（启动首屏 · 官方 coui 液态玻璃范式） ─────────────────────────

/**
 * 启动首屏：对齐 **官方 coui example**（`LiquidGlassNavigationBar` / `Vibrancy` / `Lens` / `BlurSection`）
 * 的液态玻璃范式重写。官方玻璃的"灵魂"是 `effects { vibrancy(); blur(); lens() }` + `Highlight` 镜面高光 +
 * `onDrawSurface` 铺一层 tint —— 而不是我们之前自封装的"静态 blur + 单色 tint + 自造 magnify"。
 *
 * 两种形态：登录（学号 + 密码）/ 注册（姓名 + 学号 + 学校 + 密码 + 确认）。顶部分段控件切换。
 *
 * ★ 玻璃卡采用官方 `drawBackdrop` 同款组合：
 *   - `vibrancy()`：色彩活力增强（saturation 1.5）—— 玻璃里的背景"鲜亮通透"而非灰蒙；
 *   - `blur()`：毛玻璃；
 *   - `lens(refractionHeight, refractionAmount)`：圆角折射透镜，玻璃边缘把身后内容"掰弯"，这是 iOS/ColorOS
 *     液态玻璃最标志性的折射感（之前完全没有）；
 *   - `Highlight`：顶部 0.5/-0.3 主光 + 底部 0.8/-0.5 次光的双峰镜面高光（BloomStroke）—— 玻璃边缘的"水光"。
 *   - `onDrawSurface`：铺一层浅 tint（亮色白 α、暗色黑 α），让玻璃有"实体感"。
 *
 * ★ 动画（用户要求「大量丝滑动画」）+ 安全约束：
 *   - 玻璃卡**参数凝结出场**：blur 半径 / lens 折射量 / 圆角 / 上浮位移 从 0 长到目标（纯 draw 参数 + placement 位移，
 *     不碰 alpha/graphicsLayer 包玻璃——coui 采样者被这些包裹有首帧 SIGSEGV 风险）；
 *   - 背景两枚品牌光斑**无限漂移 + 半径呼吸**（玻璃里有"活水"可折射）；
 *   - 卡内元素**错峰 spring 进场**；分段滑块 spring 滑动；字段组模式切换滑入滑出 + 高度弹簧；
 *   - 输入框 focus 三路联动；按钮 按压→loading→描线对勾 morph→脉冲→进主界面。
 */
@Composable
fun LoginScreen(onLogin: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val isDark = !cs.background.isLight() // 近似深浅判定（M3 背景浅=亮色）
    var mode by remember { mutableStateOf(0) } // 0=登录 1=注册
    var account by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var school by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf("") }
    var pwd2 by remember { mutableStateOf("") }

    val pageBackdrop = rememberLayerBackdrop()
    val breath = rememberBreathPhase()

    // 首次组合 → 触发整页进场
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }

    // 玻璃卡凝结进度（0→1，纯 draw 参数驱动，安全）
    val condense by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = 850, easing = FastOutSlowInEasing),
        label = "glassCondense",
    )
    val blurPx = lerp(0f, 24f, condense)
    val refract = lerp(0f, 26f, condense)
    val cornerDp = lerp(0f, 30f, condense).dp

    // 玻璃镜面高光（深浅自适应）：主光偏上、次光偏下，双峰 Bloom 边缘水光
    val glassHighlight = remember(isDark) {
        val tint = if (isDark) Color.White else Color.White
        Highlight(
            width = 1.dp,
            alpha = if (isDark) 0.9f else 0.75f,
            style = BloomStroke(
                color = tint,
                innerBlurRadius = 2.dp,
                primaryLight = LightSource(
                    position = LightPosition(0.5f, -0.3f, -0.05f),
                    color = Color.White,
                    intensity = if (isDark) 0.7f else 1f,
                ),
                secondaryLight = LightSource(
                    position = LightPosition(0.5f, 0.8f, -0.5f),
                    color = Color.White,
                    intensity = 0.35f,
                ),
                dualPeak = true,
            ),
        )
    }
    val tintAlpha = 0.22f * condense

    Box(Modifier.fillMaxSize()) {
        // 背景层：M3 语义背景 + 两枚柔和品牌光斑（供玻璃折射），并记录到 backdrop
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(pageBackdrop)
                .background(cs.background)
                .drawBehind {
                    val t = breath
                    val w = size.width
                    val h = size.height
                    val minD = if (w < h) w else h
                    drawCircle(
                        color = KewuBlue.copy(alpha = 0.18f),
                        radius = minD * 0.55f * (1f + 0.07f * sin(t * 6.2831853f)),
                        center = Offset(
                            w * 0.18f + sin(t * 6.2831853f) * w * 0.07f,
                            h * 0.16f + sin(t * 6.2831853f + 1.3f) * h * 0.035f,
                        ),
                    )
                    drawCircle(
                        color = KewuSky.copy(alpha = 0.16f),
                        radius = minD * 0.50f * (1f + 0.06f * sin(t * 6.2831853f + 2.1f)),
                        center = Offset(
                            w * 0.86f + sin(t * 6.2831853f + 3.1415927f) * w * 0.06f,
                            h * 0.82f + sin(t * 6.2831853f + 2.6f) * h * 0.03f,
                        ),
                    )
                },
        )

        // 居中玻璃卡（官方 drawBackdrop 同款组合 + 凝结出场）
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    // 纯视觉平移（lambda 版 offset = placement，不产生离屏层，不包裹采样者）
                    .offset { IntOffset(0, ((1f - condense) * 30f).dp.roundToPx()) }
                    .drawBackdrop(
                        backdrop = pageBackdrop,
                        shape = { RoundedCornerShape(cornerDp) },
                        effects = {
                            // ① 活力增强：玻璃里的背景鲜亮通透
                            colorControls(brightness = 0f, contrast = 1f, saturation = 1.5f)
                            // ② 毛玻璃
                            if (blurPx > 0.5f) {
                                blur(blurPx.dp.toPx(), blurPx.dp.toPx())
                            }
                            // ③ 圆角折射透镜：玻璃边缘把身后内容"掰弯"，液态玻璃标志感
                            //   （官方 coui 的 lens() 在 coui-ui 模块；本项目仅引入 coui-blur，
                            //    故用官方同款 SDF 折射 shader 本地实现，效果等价。）
                            if (refract > 0.5f && isRuntimeShaderSupported()) {
                                refractionLens(
                                    refractionHeight = refract.dp.toPx(),
                                    refractionAmount = refract.dp.toPx(),
                                )
                            }
                        },
                        highlight = { glassHighlight },
                        // ④ 铺一层浅 tint，让玻璃有实体感（亮色白、暗色黑）
                        onDrawSurface = {
                            drawRect(
                                color = if (isDark) Color.Black.copy(alpha = 0.28f) else Color.White.copy(alpha = tintAlpha),
                            )
                        },
                    )
                    .clip(RoundedCornerShape(cornerDp))
            ) {
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    // 品牌头：圆角方块「课」徽 + 名称 + 副标题
                    val headP = enterProgress(appeared, delayMs = 140)
                    Row(
                        Modifier.offset { IntOffset(0, ((1f - headP) * 18f).dp.roundToPx()) }.alpha(headP),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(KewuBlue),
                            contentAlignment = Alignment.Center,
                        ) { Text("课", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("课悟", color = cs.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("AI 课堂增效系统", color = cs.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(20.dp))

                    // 分段控件：登录 / 注册（选中滑块 spring 滑动）
                    val segP = enterProgress(appeared, delayMs = 220)
                    Box(Modifier.alpha(segP)) {
                        Segmented(mode, listOf("登录", "注册")) { mode = it }
                    }
                    Spacer(Modifier.height(18.dp))

                    // 字段组：按模式切换（滑入滑出 + 高度弹簧），整组错峰进场
                    val fieldsP = enterProgress(appeared, delayMs = 300)
                    Box(
                        Modifier
                            .alpha(fieldsP)
                            .offset { IntOffset(0, ((1f - fieldsP) * 18f).dp.roundToPx()) },
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .animateContentSize(spring(dampingRatio = 0.7f, stiffness = 380f)),
                        ) {
                            AnimatedContent(
                                targetState = mode,
                                transitionSpec = {
                                    if (targetState > initialState) {
                                        (slideInVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) { h -> h } + fadeIn(tween(220))) togetherWith
                                            (slideOutVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) { h -> -h } + fadeOut(tween(180)))
                                    } else {
                                        (slideInVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) { h -> -h } + fadeIn(tween(220))) togetherWith
                                            (slideOutVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) { h -> h } + fadeOut(tween(180)))
                                    }
                                },
                                label = "loginFields",
                            ) { m ->
                                Column(Modifier.fillMaxWidth()) {
                                    if (m == 1) {
                                        LoginField("姓名", name, KeyboardType.Text) { name = it }
                                        Spacer(Modifier.height(12.dp))
                                    }
                                    LoginField(if (m == 0) "学号 / 账号" else "学号", account, KeyboardType.Number) { account = it }
                                    Spacer(Modifier.height(12.dp))
                                    if (m == 1) {
                                        LoginField("学校", school, KeyboardType.Text) { school = it }
                                        Spacer(Modifier.height(12.dp))
                                    }
                                    LoginField("密码", pwd, KeyboardType.Password, true) { pwd = it }
                                    if (m == 1) {
                                        Spacer(Modifier.height(12.dp))
                                        LoginField("确认密码", pwd2, KeyboardType.Password, true) { pwd2 = it }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(22.dp))

                    // 主按钮：按压回弹 → loading → 描线对勾 → 进主界面
                    val btnP = enterProgress(appeared, delayMs = 400)
                    Box(Modifier.alpha(btnP)) {
                        val enabled = if (mode == 0) account.isNotBlank() && pwd.isNotBlank()
                        else name.isNotBlank() && account.isNotBlank() && pwd.isNotBlank() && pwd == pwd2
                        LoginButton(
                            enabled = enabled,
                            label = if (mode == 0) "登录" else "注册并登录",
                            onDone = onLogin,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    val footP = enterProgress(appeared, delayMs = 480)
                    Text(
                        "基于华为云 · 多端协同采集 · 云端择优融合",
                        color = cs.onSurfaceVariant, fontSize = 11.5.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().alpha(footP),
                    )
                }
            }
        }
    }
}

/** 背景光斑的呼吸相位（0..1 循环，~9s 周期）——drawBehind 里每帧读取。 */
@Composable
private fun rememberBreathPhase(): Float {
    val transition = rememberInfiniteTransition(label = "breath")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "breathPhase",
    )
    return phase
}

/**
 * 错峰进场进度：每个元素按 [delayMs] 级联弹出（位移 + alpha 由调用方消费）。
 * 统一 650ms FastOutSlowIn，级联差 ~80ms，观感是「内容逐层落位」。
 */
@Composable
internal fun enterProgress(appeared: Boolean, delayMs: Int): Float {
    val p by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = 650, delayMillis = delayMs, easing = FastOutSlowInEasing),
        label = "enter$delayMs",
    )
    return p
}

/** 分段控件：两个等宽胶囊，选中项用 primaryContainer 高亮；选中滑块 spring 滑动。 */
@Composable
private fun Segmented(selected: Int, items: List<String>, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    var totalW by remember { mutableStateOf(0.dp) }
    val sliderX by animateDpAsState(
        targetValue = if (selected == 0) 4.dp else 4.dp + totalW / 2,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 500f),
        label = "segSlider",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cs.surfaceVariant)
            .padding(4.dp)
            .onSizeChanged { totalW = with(density) { it.width.toDp() } },
    ) {
        if (totalW > 0.dp) {
            Box(
                Modifier
                    .offset(x = sliderX)
                    .width(totalW / 2 - 8.dp)
                    .height(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(cs.primaryContainer),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            items.forEachIndexed { i, label ->
                val textColor by animateColorAsState(
                    targetValue = if (i == selected) cs.onPrimaryContainer else cs.onSurfaceVariant,
                    animationSpec = tween(220),
                    label = "segText$i",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .clickable { onSelect(i) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label, color = textColor, fontSize = 14.sp,
                        fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/**
 * 登录主按钮：三态状态机（idle → loading → success）。
 * - idle：品牌蓝实心 + 文案；
 * - pressed：整体 scale 弹簧回缩（0.96），松手弹回；
 * - loading：文案淡出、M3 转圈淡入；
 * - success：转圈淡出、描线对勾逐段长出（Canvas 手绘），按钮变品牌绿 + 一次脉冲，随后回调 [onDone]。
 */
@Composable
private fun LoginButton(enabled: Boolean, label: String, onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    var state by remember { mutableStateOf(0) } // 0=idle 1=loading 2=success
    val checkProgress = remember { Animatable(0f) }

    val bg by animateColorAsState(
        targetValue = when {
            state == 2 -> KewuGreen
            enabled -> KewuBlue
            else -> cs.surfaceVariant
        },
        animationSpec = tween(280),
        label = "btnBg",
    )
    val scale by animateFloatAsState(
        targetValue = when {
            state == 2 && checkProgress.value < 0.6f -> 1.04f
            state == 2 -> 1f
            pressed && enabled -> 0.96f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 600f),
        label = "btnScale",
    )
    val textAlpha by animateFloatAsState(
        targetValue = if (state == 0) 1f else 0f,
        animationSpec = tween(200),
        label = "btnText",
    )
    val spinAlpha by animateFloatAsState(
        targetValue = if (state == 1) 1f else 0f,
        animationSpec = tween(200),
        label = "btnSpin",
    )

    Box(
        Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(
                interactionSource = interactionSource,
                enabled = enabled && state == 0,
            ) {
                state = 1
                scope.launch {
                    delay(900) // mock 网络请求
                    state = 2
                    checkProgress.animateTo(1f, tween(durationMillis = 450, easing = LinearOutSlowInEasing))
                    delay(320)
                    onDone()
                }
            }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled || state == 2) Color.White else cs.onSurfaceVariant,
            fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.alpha(textAlpha),
        )
        if (spinAlpha > 0.01f) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp).alpha(spinAlpha),
            )
        }
        if (state == 2 && checkProgress.value > 0.01f) {
            Canvas(
                Modifier
                    .size(22.dp)
                    .alpha(checkProgress.value.coerceIn(0f, 1f)),
            ) {
                val p = checkProgress.value
                val sw = size.width * 0.13f
                val c = Color.White
                val t1 = (p / 0.55f).coerceIn(0f, 1f)
                val t2 = ((p - 0.45f) / 0.55f).coerceIn(0f, 1f)
                if (t1 > 0f) {
                    drawLine(
                        c,
                        start = Offset(size.width * 0.28f, size.height * 0.52f),
                        end = Offset(
                            size.width * lerp(0.28f, 0.44f, t1),
                            size.height * lerp(0.52f, 0.67f, t1),
                        ),
                        strokeWidth = sw,
                        cap = StrokeCap.Round,
                    )
                }
                if (t2 > 0f) {
                    drawLine(
                        c,
                        start = Offset(size.width * 0.44f, size.height * 0.67f),
                        end = Offset(
                            size.width * lerp(0.44f, 0.72f, t2),
                            size.height * lerp(0.67f, 0.33f, t2),
                        ),
                        strokeWidth = sw,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

/** 表单输入行：标签 + 圆角输入框（M3 surfaceVariant 底，置于玻璃卡内）。focus 时三路联动动画。 */
@Composable
private fun LoginField(
    label: String,
    value: String,
    type: KeyboardType,
    password: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val lifted = focused || value.isNotEmpty()
    val borderColor by animateColorAsState(
        targetValue = if (focused) cs.primary else cs.surfaceVariant,
        animationSpec = tween(240),
        label = "fieldBorder",
    )
    val bgColor by animateColorAsState(
        targetValue = if (focused) cs.primary.copy(alpha = 0.10f) else cs.surfaceVariant.copy(alpha = 0.9f),
        animationSpec = tween(240),
        label = "fieldBg",
    )
    val labelColor by animateColorAsState(
        targetValue = if (focused) cs.primary else cs.onSurfaceVariant,
        animationSpec = tween(240),
        label = "fieldLabel",
    )
    val lift by animateFloatAsState(
        targetValue = if (lifted) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "fieldLift",
    )
    Column(Modifier.fillMaxWidth()) {
        Text(
            label, color = labelColor,
            fontSize = lerp(12.5f, 11f, lift).sp,
            fontWeight = if (lifted) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier
                .padding(bottom = 6.dp)
                .offset { IntOffset(0, (-2f).dp.roundToPx()) },
        )
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(bgColor)
                .border(if (lift > 0.01f) (1f + 0.5f * lift).dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = type),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                textStyle = TextStyle(color = cs.onSurface, fontSize = 15.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (value.isEmpty()) {
                            Text(label, color = cs.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 15.sp)
                        }
                        inner()
                    }
                },
            )
        }
    }
}

/** 扩展：判断一个 [Color] 是否更偏亮（用于深浅主题近似判定）。 */
private fun Color.isLight(): Boolean {
    val lum = 0.2126f * red + 0.7152f * green + 0.0722f * blue
    return lum > 0.5f
}

/**
 * 圆角折射透镜（改编自官方 coui `component.liquid.Lens` 的 SDF 折射 shader，Apache-2.0）。
 *
 * 官方 `lens()` 位于 `coui-ui` 模块，本项目仅依赖 `coui-blur`，故在此本地复刻等价实现：
 * 在玻璃圆角区域的近边缘带，按"距边缘深度"把采样坐标沿法线方向外推，形成 iOS/ColorOS 液态玻璃
 * 那种边缘把身后内容"掰弯"的折射感。纯 `runtimeShaderEffect`（draw 期着色器），可安全随动。
 */
private fun BackdropEffectScope.refractionLens(
    refractionHeight: Float,
    refractionAmount: Float,
) {
    if (refractionHeight <= 0f || refractionAmount <= 0f) return
    if (padding < refractionAmount) padding = refractionAmount

    val radii = roundedRectCornerRadii() ?: return
    val sf = downscaleFactor.coerceAtLeast(1).toFloat()
    val scaledSizeW = size.width / sf
    val scaledSizeH = size.height / sf
    val scaledPadding = padding / sf
    val scaledRefractionHeight = refractionHeight / sf
    val scaledRefractionAmount = refractionAmount / sf
    val scaledRadii = FloatArray(radii.size) { radii[it] / sf }

    runtimeShaderEffect(
        key = "KewuGlassLens",
        shaderString = ROUNDED_RECT_REFRACTION_SHADER,
        uniformShaderName = "content",
    ) {
        setFloatUniform("size", scaledSizeW, scaledSizeH)
        setFloatUniform("offset", -scaledPadding, -scaledPadding)
        setFloatUniform("cornerRadii", scaledRadii)
        setFloatUniform("refractionHeight", scaledRefractionHeight)
        setFloatUniform("refractionAmount", -scaledRefractionAmount)
        setFloatUniform("depthEffect", 0f)
    }
}

private fun BackdropEffectScope.roundedRectCornerRadii(): FloatArray? {
    val cornerShape = shape as? androidx.compose.foundation.shape.CornerBasedShape ?: return null
    val sizePx = size
    val maxRadius = sizePx.minDimension / 2f
    val isLtr = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Ltr
    val topLeft = if (isLtr) cornerShape.topStart.toPx(sizePx, this) else cornerShape.topEnd.toPx(sizePx, this)
    val topRight = if (isLtr) cornerShape.topEnd.toPx(sizePx, this) else cornerShape.topStart.toPx(sizePx, this)
    val bottomRight = if (isLtr) cornerShape.bottomEnd.toPx(sizePx, this) else cornerShape.bottomStart.toPx(sizePx, this)
    val bottomLeft = if (isLtr) cornerShape.bottomStart.toPx(sizePx, this) else cornerShape.bottomEnd.toPx(sizePx, this)
    return floatArrayOf(
        topLeft.coerceAtMost(maxRadius),
        topRight.coerceAtMost(maxRadius),
        bottomRight.coerceAtMost(maxRadius),
        bottomLeft.coerceAtMost(maxRadius),
    )
}

private const val ROUNDED_RECT_SDF = """
float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}
float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}
float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}
"""

private const val ROUNDED_RECT_REFRACTION_SHADER = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
$ROUNDED_RECT_SDF
float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}
half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii);
    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);
    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));
    float2 refractedCoord = coord + d * grad;
    return content.eval(refractedCoord);
}
"""
