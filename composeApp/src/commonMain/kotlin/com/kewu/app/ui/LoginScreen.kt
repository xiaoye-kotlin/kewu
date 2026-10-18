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
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.kewu.app.HapticKind
import com.kewu.app.KewuBlue
import com.kewu.app.KewuGreen
import com.kewu.app.KewuSky
import com.kewu.app.KewuApi
import com.kewu.app.haptic
import com.kewu.app.hapticClickable
import com.kewu.app.kvGet
import com.kewu.app.kvSet
import com.kewu.app.ui.liquidglass.defaultKewuHighlight
import com.kewu.app.ui.liquidglass.isLight
import com.kewu.app.ui.liquidglass.refractionLens
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
import kewu.composeapp.generated.resources.Res
import kewu.composeapp.generated.resources.student_logo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
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
    // ★ 自动记住：首次登录后把账号密码存进 kv，下次启动直接进主界面（App 层读 kewu_logged 决定）。
    //   这里进场即预填，用户无需重输；只有「我的→退出登录」才会清空。
    var account by remember { mutableStateOf(kvGet("kewu_account") ?: "") }
    var name by remember { mutableStateOf("") }
    var school by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf(kvGet("kewu_pwd") ?: "") }
    var pwd2 by remember { mutableStateOf("") }
    // 学校选择器：底部弹层开关（MIUI 风选项 + 模糊搜索）
    var schoolSheetOpen by remember { mutableStateOf(false) }
    // 登录/注册失败文案（网络异常 / 账号密码错误），成功后清空
    var loginError by remember { mutableStateOf("") }

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

    // 玻璃镜面高光（深浅自适应）：官方同款双峰 Bloom 边缘水光
    val glassHighlight = remember(isDark) { defaultKewuHighlight(isDark) }
    val tintAlpha = 0.22f * condense

    Box(Modifier.fillMaxSize()) {
        // 背景层：M3 语义背景 + 两枚柔和品牌光斑（供玻璃折射），并记录到 backdrop
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(pageBackdrop)
                .background(Color(0xFFF8F9FA))
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
                        ) {
                            // 2026-10-19：品牌头徽改用新版学生端 logo（裁出中央学士帽+书本+声波主图形）
                            Image(
                                painter = painterResource(Res.drawable.student_logo),
                                contentDescription = "课悟",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
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
                                    // 登录态：仅「学号 + 密码」两字段（用户要求精简）
                                    MiuixField(
                                        title = "学号",
                                        value = account,
                                        keyboardType = KeyboardType.Number,
                                        onValueChange = { account = it },
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    MiuixField(
                                        title = "密码",
                                        value = pwd,
                                        keyboardType = KeyboardType.Password,
                                        password = true,
                                        onValueChange = { pwd = it },
                                    )
                                    // 注册态：确认密码紧跟密码下方（用户明确要求），再姓名、学校
                                    if (m == 1) {
                                        Spacer(Modifier.height(12.dp))
                                        MiuixField("确认密码", pwd2, KeyboardType.Password, password = true) { pwd2 = it }
                                        Spacer(Modifier.height(12.dp))
                                        MiuixField("姓名", name, KeyboardType.Text) { name = it }
                                        Spacer(Modifier.height(12.dp))
                                        // ★ 学校改为选择器：点开底部弹层（coui 玻璃 + MIUI 选项 + 模糊搜索）
                                        SchoolSelectorField(school) { schoolSheetOpen = true }
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
                            mode = mode,
                            account = account,
                            pwd = pwd,
                            name = name,
                            enabled = enabled,
                            label = if (mode == 0) "登录" else "注册并登录",
                            onError = { loginError = it },
                            onDone = {
                                // ★ 自动记住：登录/注册成功后持久化账号密码 + 已登录标记，
                                //   下次启动直接进主界面（除非在「我的」点退出登录清空）。
                                kvSet("kewu_account", account)
                                kvSet("kewu_pwd", pwd)
                                kvSet("kewu_logged", "1")
                                onLogin()
                            },
                        )
                    }
                    // 登录/注册失败提示（错误色，不进主界面）
                    if (loginError.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            loginError, color = cs.error, fontSize = 12.5.sp,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        // ★ 一级界面返回键（2026-10-19 用户需求）：登录/注册首屏也按「双击退出」处理 ——
        //   第一次按系统返回键浮出「再次按下返回键退出应用」，2s 内再按一次退出。
        //   （深一层只有学校选择弹层，它是平台 Dialog，返回键优先被弹层消费，不会冲突。）
        DoubleBackExitHint(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 56.dp),
        )

        // ★ 学校选择底部弹层（coui 官方 WindowBottomSheet + 模糊搜索）。
        //   弹窗外壳（抓手/滑入滑出/遮罩/返回键）完全由 coui 提供，不自绘。
        if (schoolSheetOpen) {
            SchoolPickerSheet(
                selected = school,
                onDismiss = { schoolSheetOpen = false },
                onSelect = { school = it; schoolSheetOpen = false },
            )
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
                        .hapticClickable { onSelect(i) }
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
private fun LoginButton(
    mode: Int,
    account: String,
    pwd: String,
    name: String,
    enabled: Boolean,
    label: String,
    onError: (String) -> Unit,
    onDone: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    var state by remember { mutableStateOf(0) } // 0=idle 1=loading 2=success
    val checkProgress = remember { Animatable(0f) }
    // 登录成功瞬间的柔光闪（视觉印象）+ 配合线性马达震动
    val successFlash = remember { Animatable(0f) }

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
            .height(50.dp)
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(
                interactionSource = interactionSource,
                enabled = enabled && state == 0,
            ) {
                haptic(HapticKind.Tick)
                onError("") // 清除旧错误
                state = 1
                scope.launch {
                    // ★ 真实请求：登录 / 注册（注册成功后自动登录拿 token）
                    val msg = if (mode == 0) {
                        KewuApi.login(account, pwd)
                    } else {
                        val r = KewuApi.register(account, pwd, name)
                        if (r == null) KewuApi.login(account, pwd) else r
                    }
                    if (msg == null) {
                        // 成功：拉取个人资料（最佳努力，失败不影响进入主界面）
                        KewuApi.me()
                        state = 2
                        // ★ 登录成功：线性马达中档震动 + 一次柔光闪（视觉印象）
                        haptic(HapticKind.Medium)
                        successFlash.snapTo(0.18f)
                        successFlash.animateTo(0f, tween(durationMillis = 280))
                        checkProgress.animateTo(1f, tween(durationMillis = 450, easing = LinearOutSlowInEasing))
                        delay(320)
                        onDone()
                    } else {
                        onError(msg)
                        state = 0
                    }
                }
            },
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
        // 成功柔光闪（盖在按钮内容之上，快速淡出）—— 给"变对勾"加一点视觉印象
        Box(Modifier.fillMaxSize().alpha(successFlash.value).background(Color.White))
    }
}

/**
 * 仿 MIUI / ColorOS（coui 基于 ColorOS 16）的卡片式输入框 InputView：
 * 白底（亮色纯白 / 暗色 surface 浅色）、圆角 14dp、左侧竖排小标题 + 右侧输入、
 * 末尾「清除」按钮（有内容时）与密码「可见/隐藏」切换（Lock 图标，core 无 Visibility）。
 * focus 时左标题与边框转为品牌蓝，复刻 MIUI 输入聚焦反馈。
 */
@Composable
private fun MiuixField(
    title: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    var pwdVisible by remember { mutableStateOf(false) }

    val titleColor by animateColorAsState(
        targetValue = if (focused) KewuBlue else cs.onSurfaceVariant,
        animationSpec = tween(200),
        label = "miuixTitle",
    )
    val cardColor = if (cs.background.isLight()) Color.White else cs.surface
    // MIUI 输入框：蓝色线条（常驻淡蓝、聚焦实蓝），白底、极大圆角、框较长
    val borderColor by animateColorAsState(
        targetValue = if (focused) KewuBlue else KewuBlue.copy(alpha = 0.35f),
        animationSpec = tween(200),
        label = "miuixBorder",
    )

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(cardColor)
            .border(1.4.dp, borderColor, RoundedCornerShape(18.dp))
            // ★ 输入框加长：横向内边距收紧（18→14）让输入区更宽，
            //   纵向 15→17 让框更高，避免「确认密码」码字被遮住/看不清。
            .padding(horizontal = 14.dp, vertical = 17.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                color = titleColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                // ★ 标签列 56→72dp：「确认密码」四字能完整放下，不再挤压右侧输入区
                modifier = Modifier.width(72.dp),
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                maxLines = 1,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation = if (password && !pwdVisible) PasswordVisualTransformation() else VisualTransformation.None,
                textStyle = TextStyle(color = cs.onSurface, fontSize = 15.sp),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (value.isEmpty()) {
                            Text(
                                "请输入$title",
                                color = cs.onSurfaceVariant.copy(alpha = 0.5f),
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                            )
                        }
                        inner()
                    }
                },
            )
            // ★ 末尾图标区**常驻 28dp 槽位**：输入首个字符时图标出现不再撑高行高（修复跳动）。
            // 非密码且空内容时槽位留空（仅占位、不可点）；其余情况显示 Lock/Clear 并给轻震动。
            val showTrail = value.isNotEmpty() || password
            Box(
                Modifier.size(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (showTrail) {
                    FieldIcon(
                        image = if (password) AppIcons.Lock else AppIcons.Clear,
                        tint = cs.onSurfaceVariant,
                    ) {
                        haptic(HapticKind.Tick)
                        if (password) pwdVisible = !pwdVisible else onValueChange("")
                    }
                }
            }
        }
    }
}

/** 轻量图标按钮（无波纹，置于输入框末尾）。 */
@Composable
private fun FieldIcon(image: ImageVector, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(image, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

// ───────────────────────── 学校选择（底部弹层 · coui 玻璃 · MIUI 风 · 模糊搜索） ─────────────────────────

/**
 * 注册页「学校」选择字段：与 [MiuixField] 同卡片样式（白底/圆角/蓝色边框），
 * 但只读不可输入 —— 点击整卡弹出底部选择器（[SchoolPickerSheet]）。
 * 选中后显示学校名；未选时显示占位「请选择学校」。
 */
@Composable
private fun SchoolSelectorField(value: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val cardColor = if (cs.background.isLight()) Color.White else cs.surface
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(cardColor)
            .border(1.4.dp, KewuBlue.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .hapticClickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 17.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("学校", color = cs.onSurfaceVariant, fontSize = 15.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false,
                modifier = Modifier.width(72.dp))
            Text(
                if (value.isEmpty()) "请选择学校" else value,
                color = if (value.isEmpty()) cs.onSurfaceVariant.copy(alpha = 0.5f) else cs.onSurface,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 下拉箭头（▼）
            Canvas(Modifier.size(16.dp)) {
                val s = size.width
                val p = Path().apply {
                    moveTo(s * 0.2f, s * 0.38f); lineTo(s * 0.5f, s * 0.66f); lineTo(s * 0.8f, s * 0.38f)
                }
                drawPath(p, cs.onSurfaceVariant, style = Stroke(width = s * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/**
 * 学校选择底部弹层（2026-10-19 改）：**弹窗外壳改用自写 `KewuBottomSheet`**（标准 Compose
 * `Dialog` + coui 视觉：拖拽手柄 / 顶部圆角 / 遮罩 / 上滑入场 / 点遮罩关闭 / 返回键 dismiss）。
 * 原因：coui 1.1.0 的 `WindowBottomSheet` 在本项目 CMP 1.11.2 下一点击即 `NoSuchMethodError`
 * 闪退（`DialogProperties` 构造签名不匹配，详见交接文档 12.1）。内容区不变：搜索框（M3 语义色）
 * + 结果列表（名称/拼音/首字母模糊搜，全量 2900+ 所高校）+ 未命中时「使用『xxx』」自由输入兜底。
 *
 * 关闭动画：选中的值先暂存 [pendingSelect]，等弹层退场动画结束（KewuBottomSheet 的 onDismiss）
 * 再回调，退场动画能完整播放。
 */
@Composable
private fun SchoolPickerSheet(selected: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    var pendingSelect by remember { mutableStateOf<String?>(null) }

    KewuBottomSheet(
        show = true,
        onDismiss = {
            val picked = pendingSelect
            pendingSelect = null
            if (picked != null) onSelect(picked) else onDismiss()
        },
        title = "选择学校",
    ) { close ->
        // 搜索框（M3 语义色：surfaceVariant 底 + 品牌蓝描边）
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(cs.surfaceVariant)
                .border(1.2.dp, KewuBlue.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 放大镜图标
                Canvas(Modifier.size(16.dp)) {
                    val s = size.width
                    drawCircle(cs.onSurfaceVariant, radius = s * 0.32f, center = Offset(s * 0.38f, s * 0.38f),
                        style = Stroke(width = s * 0.10f))
                    drawLine(cs.onSurfaceVariant, Offset(s * 0.62f, s * 0.62f), Offset(s * 0.9f, s * 0.9f),
                        strokeWidth = s * 0.10f, cap = StrokeCap.Round)
                }
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = cs.onSurface, fontSize = 14.sp),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth()) {
                            if (query.isEmpty()) {
                                Text("搜索学校（名称 / 拼音 / 首字母）", color = cs.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 14.sp)
                            }
                            inner()
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // 结果列表（模糊搜索：名称 / 全拼 / 简写；全量 2900+ 所）
        val results = remember(query) { searchUniversities(query) }
        Box(Modifier.fillMaxWidth().height(340.dp)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                // 未命中时给「自由输入」兜底项（后端 register 接受任意 nickname/school 字符串）
                if (results.isEmpty() && query.isNotBlank()) {
                    item {
                        SchoolOptionRow(
                            name = "使用「${query.trim()}」",
                            sub = "未收录，手动指定",
                            isSelected = false,
                        ) {
                            pendingSelect = query.trim()
                            close()
                        }
                    }
                }
                itemsIndexed(results) { _, uni ->
                    SchoolOptionRow(
                        name = uni.name,
                        sub = uni.province,
                        isSelected = uni.name == selected,
                    ) {
                        pendingSelect = uni.name
                        close()
                    }
                }
            }
        }
    }
}

/** MIUI 风选项行：学校名 + 省份（副标题）+ 选中态绿勾；整行可点。 */
@Composable
private fun SchoolOptionRow(name: String, sub: String, isSelected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) cs.primaryContainer else Color.Transparent)
            .hapticClickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, color = cs.onSurface, fontSize = 15.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                if (sub.isNotEmpty()) {
                    Spacer(Modifier.height(1.dp))
                    Text(sub, color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
                }
            }
            if (isSelected) {
                // 选中绿勾（Canvas 两段折线）
                Canvas(Modifier.size(18.dp)) {
                    val s = size.width
                    val p = Path().apply {
                        moveTo(s * 0.22f, s * 0.52f); lineTo(s * 0.44f, s * 0.72f); lineTo(s * 0.80f, s * 0.30f)
                    }
                    drawPath(p, KewuGreen, style = Stroke(width = s * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }
}
