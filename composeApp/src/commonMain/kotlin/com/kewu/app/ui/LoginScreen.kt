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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.kewu.app.KewuBlue
import com.kewu.app.KewuGreen
import com.kewu.app.KewuSky
import com.kewu.app.ui.liquidglass.LiquidGlassSurface
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin

// ───────────────────────── 登录 / 注册（启动首屏 · coui 液态玻璃） ─────────────────────────

/**
 * 启动首屏：液态玻璃大卡表单（coui blur + 折射），身后放两枚柔和的品牌色光斑，让玻璃"透出"内容并带折射感。
 * 背景层按规定走 [MaterialTheme] 默认 colorScheme，光斑用品牌色（[KewuBlue]/[KewuSky]）极低透明度，
 * 不喧宾夺主、也不破坏导航栏玻璃的纯色采样规则（登录页没有底部导航栏玻璃采样本背景）。
 *
 * 两种形态：登录（学号 + 密码）/ 注册（姓名 + 学号 + 学校 + 密码 + 确认）。顶部分段控件切换。
 * mock：任意输入 → [onLogin] 进入主界面。
 *
 * ★ 动画总设计（用户要求「大量丝滑动画」）：
 *   1. 背景两枚光斑**无限漂移 + 半径呼吸**（[rememberInfiniteTransition]）—— 玻璃有"活水"可折射；
 *   2. 玻璃卡**参数凝结出场**：blurRadius 0→26dp、tintAlpha 0→0.22、圆角 0→28dp、magnify 1→1.18，
 *      配合纯视觉位移上浮。★ 不用 alpha/scale 包玻璃卡本身 —— coui 采样者被 alpha/graphicsLayer
 *      包裹有首帧 RenderThread SIGSEGV 风险，参数动画是纯 draw 参数，随随便便动；
 *   3. 卡内元素**错峰 spring 进场**（位移 + alpha，逐层级联 ~80ms）；
 *   4. 分段控件选中滑块 **spring 滑动**，字段组按模式切换 **滑入滑出 + 高度弹簧**；
 *   5. 输入框 focus：边框/底色/标签上浮三路联动 tween；
 *   6. 登录按钮：按压回弹（pressed scale 弹簧）→ loading spinner → **描线对勾 morph**
 *      （Canvas 逐段画线，M3 经典「进度→对勾」形变）→ 绿色脉冲 → 进主界面。
 */
@Composable
fun LoginScreen(onLogin: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var mode by remember { mutableStateOf(0) } // 0=登录 1=注册
    var account by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var school by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf("") }
    var pwd2 by remember { mutableStateOf("") }

    val pageBackdrop = rememberGlassBackdrop()
    val breath = rememberBreathPhase()

    // 首次组合 → 触发整页进场（光斑呼吸一直在跑，与它无关）
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }

    Box(Modifier.fillMaxSize()) {
        // 背景层：M3 语义背景 + 两枚柔和品牌光斑（供玻璃折射），并记录到 backdrop
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(pageBackdrop)
                .background(cs.background)
                .drawBehind {
                    // 两团柔光各自缓慢漂移（正弦）+ 半径呼吸 → 玻璃卡里看到的是"流动的"底色，
                    // 静态底色上的液态玻璃是死的，流动底色上的玻璃才是活的。
                    val t = breath
                    val w = size.width
                    val h = size.height
                    val minD = if (w < h) w else h
                    drawCircle(
                        color = KewuBlue.copy(alpha = 0.16f),
                        radius = minD * 0.55f * (1f + 0.07f * sin(t * 6.2831853f)),
                        center = Offset(
                            w * 0.18f + sin(t * 6.2831853f) * w * 0.07f,
                            h * 0.16f + sin(t * 6.2831853f + 1.3f) * h * 0.035f,
                        ),
                    )
                    drawCircle(
                        color = KewuSky.copy(alpha = 0.14f),
                        radius = minD * 0.50f * (1f + 0.06f * sin(t * 6.2831853f + 2.1f)),
                        center = Offset(
                            w * 0.86f + sin(t * 6.2831853f + 3.1415927f) * w * 0.06f,
                            h * 0.82f + sin(t * 6.2831853f + 2.6f) * h * 0.03f,
                        ),
                    )
                },
        )

        // 居中玻璃卡（参数凝结出场：blur/圆角/tint/magnify 从 0 长到目标值 + 上浮 28dp）
        val condense by animateFloatAsState(
            targetValue = if (appeared) 1f else 0f,
            animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            label = "glassCondense",
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LiquidGlassSurface(
                backdrop = pageBackdrop,
                shape = RoundedCornerShape(lerp(0f, 28f, condense).dp),
                blurRadius = lerp(0f, 26f, condense).dp,
                tintAlpha = 0.22f * condense,
                magnify = 1f + 0.18f * condense,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    // 纯视觉平移（lambda 版 offset = placement，不产生离屏层）
                    .offset { IntOffset(0, ((1f - condense) * 28f).dp.roundToPx()) },
            ) {
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    // 品牌头：圆角方块「课」徽 + 名称 + 副标题
                    val headP = enterProgress(appeared, delayMs = 120)
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
                    val segP = enterProgress(appeared, delayMs = 200)
                    Box(Modifier.alpha(segP)) {
                        Segmented(mode, listOf("登录", "注册")) { mode = it }
                    }
                    Spacer(Modifier.height(18.dp))

                    // 字段组：按模式切换（滑入滑出 + 高度弹簧），整组错峰进场
                    val fieldsP = enterProgress(appeared, delayMs = 280)
                    Box(
                        Modifier
                            .alpha(fieldsP)
                            .offset { IntOffset(0, ((1f - fieldsP) * 18f).dp.roundToPx()) },
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                // 模式切换时高度平滑过渡（广播式 layout 动画，无离屏层）
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
                                    // 登录用「学号 / 账号」，注册用「学号」
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
                    val btnP = enterProgress(appeared, delayMs = 380)
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
                    val footP = enterProgress(appeared, delayMs = 460)
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
 * （internal：课堂页顶栏/状态区复用同一套级联节奏。）
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
    // 滑块 x：4dp（选中第 0 项）↔ 4dp + 半宽（选中第 1 项）
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
        // 滑块（选中底）
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
 * - success：转圈淡出、**描线对勾**逐段长出（Canvas 手绘，两段折线各自 pathTrim），
 *   按钮变品牌绿 + 一次 1→1.04→1 的脉冲，随后回调 [onDone]。
 */
@Composable
private fun LoginButton(enabled: Boolean, label: String, onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    var state by remember { mutableStateOf(0) } // 0=idle 1=loading 2=success
    val checkProgress = remember { Animatable(0f) }

    // 按钮底色：idle 品牌蓝 / disabled 灰 / success 品牌绿（颜色动画过渡）
    val bg by animateColorAsState(
        targetValue = when {
            state == 2 -> KewuGreen
            enabled -> KewuBlue
            else -> cs.surfaceVariant
        },
        animationSpec = tween(280),
        label = "btnBg",
    )
    // 按压回弹 + success 脉冲
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
    // 文案 / spinner 交叉淡入
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
        // 文案
        Text(
            label,
            color = if (enabled || state == 2) Color.White else cs.onSurfaceVariant,
            fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.alpha(textAlpha),
        )
        // loading 转圈（不确定态：自己转）
        if (spinAlpha > 0.01f) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp).alpha(spinAlpha),
            )
        }
        // success 描线对勾
        if (state == 2 && checkProgress.value > 0.01f) {
            Canvas(
                Modifier
                    .size(22.dp)
                    .alpha(checkProgress.value.coerceIn(0f, 1f)),
            ) {
                val p = checkProgress.value
                val sw = size.width * 0.13f
                val c = Color.White
                // 两段折线：p1(0.28,0.52) → p2(0.44,0.67) → p3(0.72,0.33)
                // 段 1 占 0→55%，段 2 占 45→100%（有 10% 重叠，接缝不断）
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
    // focus：边框描品牌色、底色淡入 primary 8%、标签上浮变色
    val borderColor by animateColorAsState(
        targetValue = if (focused) cs.primary else cs.surfaceVariant,
        animationSpec = tween(240),
        label = "fieldBorder",
    )
    val bgColor by animateColorAsState(
        targetValue = if (focused) cs.primary.copy(alpha = 0.08f) else cs.surfaceVariant.copy(alpha = 0.85f),
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
                visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
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
