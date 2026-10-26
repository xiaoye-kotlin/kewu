package com.kewu.app.ui

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.State
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.BoxWithConstraints
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
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.BasicComponentDefaults
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.basic.InputField
import io.github.suqi8.coui.kmp.basic.TabRowWithContour
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldDefaults
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Ok
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import kewu.composeapp.generated.resources.Res
import kewu.composeapp.generated.resources.student_logo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState
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
 *
 * ★ 2026-10-23 全局 coui 化：玻璃卡（drawBackdrop 链）与凝结出场、品牌 logo、光斑、描线对勾保留；
 *   卡内分段 → coui TabRowWithContour、输入框 → coui TextField、主按钮容器 → coui Button、
 *   转圈 → coui InfiniteProgressIndicator、学校选择 → coui ArrowPreference + OverlayBottomSheet
 *   （InputField 搜索 + BasicComponent 结果行），文字 / 配色一律 coui Text + COUITheme。
 */
@Composable
fun LoginScreen(onLogin: () -> Unit) {
    val cs = COUITheme.colorScheme
    val isDark = !cs.surface.isLight() // 近似深浅判定（coui 页面底 surface 浅=亮色）
    var mode by remember { mutableStateOf(0) } // 0=登录 1=注册
    // ★ 角色选择（单应用多角色，2026-10-19 教师端）：0=学生 1=教师。
    //   后端暂无角色字段，登录成功后按 kv 里的角色分流到学生端 / 教师端两套 UI。
    //   默认记住上次角色（退出登录不清 kewu_role，同账号重登更顺手）。
    var role by remember { mutableStateOf(if (kvGet("kewu_role") == "teacher") 1 else 0) }
    // ★ 自动记住：首次登录后把账号密码存进 kv，下次启动直接进主界面（App 层读 kewu_logged 决定）。
    //   这里进场即预填，用户无需重输；只有「我的→退出登录」才会清空。
    var account by remember { mutableStateOf(kvGet("kewu_account") ?: "") }
    var name by remember { mutableStateOf("") }
    var school by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf(kvGet("kewu_pwd") ?: "") }
    var pwd2 by remember { mutableStateOf("") }
    // 学校选择器：底部弹层开关（MIUI 风选项 + 模糊搜索）
    var schoolSheetOpen by remember { mutableStateOf(false) }
    // ★ 2026-10-26：注册页一出现就在后台线程预热高校数据（ALL_UNIVERSITIES 是 lazy），
    //   点「选择学校」时不再在主线程做首次初始化（vivo 闪退兼容修复，见 Universities.kt 头注释）。
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) { runCatching { ALL_UNIVERSITIES.size } }
    }
    // 登录/注册失败文案（网络异常 / 账号密码错误），成功后清空
    var loginError by remember { mutableStateOf("") }

    val pageBackdrop = rememberLayerBackdrop()
    // 2026-10-26：呼吸相位以 State 传递、只在 drawBehind 里读 .value —— 旧版在组合阶段读 Float，
    // 无限动画让整个登录页每帧重组（键盘输入、玻璃卡都跟着重组）
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
        // 背景层：coui 页面底色（surface）+ 两枚柔和品牌光斑（供玻璃折射），并记录到 backdrop
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(pageBackdrop)
                .background(cs.surface)
                .drawBehind {
                    val t = breath.value
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
        // ★ 2026-10-26 输入法避让：外层随键盘收缩 + 可滚动；内层最小高度 = 可视高度，键盘未弹时仍居中，
        //   键盘弹起后聚焦的输入框（注册页学号 / 密码等）会被滚动容器自动带到键盘上方。
        // 2026-10-26 edge-to-edge：safeDrawing = 状态栏 + 导航栏 + 刘海 + 输入法，玻璃卡不再顶进状态栏 / 被键盘盖住
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val viewportH = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().heightIn(min = viewportH), contentAlignment = Alignment.Center) {
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
                            Text("AI 课堂增效系统", color = cs.onSurfaceSecondary, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(20.dp))

                    // 分段控件：登录 / 注册（coui TabRowWithContour）
                    val segP = enterProgress(appeared, delayMs = 220)
                    Box(Modifier.alpha(segP)) {
                        Segmented(mode, listOf("登录", "注册")) { mode = it }
                    }
                    Spacer(Modifier.height(10.dp))
                    // 角色分段：我是学生 / 我是老师（同款 coui 分段；登录成功后按角色进两套 UI）
                    Box(Modifier.alpha(segP)) {
                        Segmented(role, listOf("我是学生", "我是老师")) {
                            role = it
                            kvSet("kewu_role", if (it == 1) "teacher" else "student")
                        }
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
                                    // ★ 身份标识按角色切换（2026-10-21）：学生=学号、教师=工号。
                                    //   工号常带字母（如 T2026001），所以教师端用文本键盘，学生端仍是数字键盘。
                                    val idLabel = if (role == 1) "工号" else "学号"
                                    // 登录态：仅「学号/工号 + 密码」两字段（用户要求精简）
                                    LoginField(
                                        title = idLabel,
                                        value = account,
                                        keyboardType = if (role == 1) KeyboardType.Text else KeyboardType.Number,
                                        onValueChange = { account = it },
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    LoginField(
                                        title = "密码",
                                        value = pwd,
                                        keyboardType = KeyboardType.Password,
                                        password = true,
                                        onValueChange = { pwd = it },
                                    )
                                    // 注册态：确认密码紧跟密码下方（用户明确要求），再姓名、学校
                                    if (m == 1) {
                                        Spacer(Modifier.height(12.dp))
                                        LoginField("确认密码", pwd2, KeyboardType.Password, password = true) { pwd2 = it }
                                        Spacer(Modifier.height(12.dp))
                                        LoginField("姓名", name, KeyboardType.Text) { name = it }
                                        Spacer(Modifier.height(12.dp))
                                        // ★ 学校改为选择器：点开底部弹层（coui OverlayBottomSheet + 模糊搜索）
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
                            role = role, // 注册时带给后端（后端 2026-10-20 起支持 role 参数）
                            enabled = enabled,
                            label = if (mode == 0) "登录" else "注册并登录",
                            onError = { loginError = it },
                            onDone = {
                                // ★ 自动记住：登录/注册成功后持久化账号密码 + 已登录标记，
                                //   下次启动直接进主界面（除非在「我的」点退出登录清空）。
                                kvSet("kewu_account", account.trim())
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
        }
        }

        // ★ 一级界面返回键（2026-10-19 用户需求）：登录/注册首屏也按「双击退出」处理 ——
        //   第一次按系统返回键弹出 coui Snackbar「再次按下返回键退出应用」，2s 内再按一次退出。
        //   （深一层只有学校选择弹层，coui OverlayBottomSheet 自带返回键处理，优先被弹层消费，不会冲突。）
        DoubleBackExitHint(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 56.dp),
        )

        // ★ 学校选择底部弹层（coui 官方 OverlayBottomSheet + 模糊搜索）。
        //   弹窗外壳（抓手/滑入滑出/遮罩/返回键）完全由 coui 提供，不自绘。
        if (schoolSheetOpen) {
            SchoolPickerSheet(
                selected = school,
                onDismiss = { schoolSheetOpen = false },
                // 选的学校先暂存，注册成功后写进本地资料（后端注册接口暂不收学校字段）
                onSelect = { school = it; schoolSheetOpen = false; kvSet("kewu_pending_school", it) },
            )
        }
    }
}

/** 背景光斑的呼吸相位（0..1 循环，~9s 周期）——drawBehind 里每帧读取。 */
@Composable
private fun rememberBreathPhase(): State<Float> {
    val transition = rememberInfiniteTransition(label = "breath")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "breathPhase",
    )
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

/**
 * 分段控件（2026-10-23 全局 coui 化）：原自建「双胶囊 + spring 滑块」改为 coui 原生
 * [TabRowWithContour]（ColorOS 带轮廓分段，选中滑块动画由 coui 提供）；本件不是液态玻璃，故替换。
 */
@Composable
private fun Segmented(selected: Int, items: List<String>, onSelect: (Int) -> Unit) {
    TabRowWithContour(
        tabs = items,
        selectedTabIndex = selected,
        onTabSelected = { i ->
            if (i != selected) {
                haptic(HapticKind.Tick)
                onSelect(i)
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 登录主按钮：三态状态机（idle → loading → success）。
 * - 容器：coui [Button]（主色实心；按压缩放 + 按压 Tint 由 coui 原生提供，不再自建 clickable + scale）；
 * - loading：文案淡出、coui [InfiniteProgressIndicator] 转圈淡入；
 * - success：转圈淡出、描线对勾逐段长出（Canvas 手绘，标志性动效保留），按钮变品牌绿 + 一次脉冲，随后回调 [onDone]。
 */
@Composable
private fun LoginButton(
    mode: Int,
    account: String,
    pwd: String,
    name: String,
    role: Int, // 0=学生 1=老师；注册时传给后端（STUDENT/TEACHER），登录时后端返回的角色为准
    enabled: Boolean,
    label: String,
    onError: (String) -> Unit,
    onDone: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(0) } // 0=idle 1=loading 2=success
    val checkProgress = remember { Animatable(0f) }
    // 登录成功瞬间的柔光闪（视觉印象）+ 配合线性马达震动
    val successFlash = remember { Animatable(0f) }

    val bg by animateColorAsState(
        targetValue = if (state == 2) KewuGreen else cs.primary,
        animationSpec = tween(280),
        label = "btnBg",
    )
    // 成功脉冲（按压缩放已交给 coui Button 自身）
    val scale by animateFloatAsState(
        targetValue = if (state == 2 && checkProgress.value < 0.6f) 1.04f else 1f,
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

    Button(
        onClick = {
            if (state != 0) return@Button // loading / success 期间不重复提交
            haptic(HapticKind.Tick)
            onError("") // 清除旧错误
            // ★ 提交前本地校验（2026-10-21）：去掉首尾空格，并挡掉「密码不足 6 位」。
            //   后端把多条校验错误拼成一条返回，虽然前端已拆条映射，但能在本地拦下更直接。
            val acc = account.trim()
            val idLabel = if (role == 1) "工号" else "学号"
            if (acc.isEmpty()) { onError("请输入${idLabel}"); return@Button }
            if (pwd.length < 6) { onError("密码长度至少 6 位，请重新设置"); return@Button }
            state = 1
            scope.launch {
                // ★ 真实请求：登录 / 注册（注册成功后自动登录拿 token）
                // 注册把分段选的角色带给后端；登录成功后 KewuApi.login 会用响应里的 role 覆写 kv
                val msg = if (mode == 0) {
                    KewuApi.login(acc, pwd)
                } else {
                    val r = KewuApi.register(
                        acc, pwd, name.trim(), if (role == 1) "TEACHER" else "STUDENT",
                        school = kvGet("kewu_pending_school").orEmpty(),
                    )
                    if (r == null) {
                        kvGet("kewu_pending_school")?.let { ProfileStore.setSchool(acc, it) }
                        kvSet("kewu_pending_school", null)
                    }
                    if (r == null) KewuApi.login(acc, pwd) else r
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
        modifier = Modifier.fillMaxWidth().scale(scale),
        // loading / success 期间保持「可用」外观（否则会闪成禁用灰），重复点击在 onClick 里拦
        enabled = enabled || state != 0,
        cornerRadius = 14.dp,
        minHeight = 50.dp,
        colors = ButtonDefaults.buttonColorsPrimary(color = bg),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                label,
                color = if (enabled || state == 2) cs.onPrimary else cs.disabledOnPrimaryButton,
                fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.alpha(textAlpha),
            )
            if (spinAlpha > 0.01f) {
                InfiniteProgressIndicator(
                    color = cs.onPrimary,
                    size = 20.dp,
                    strokeWidth = 2.dp,
                    modifier = Modifier.alpha(spinAlpha),
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
            if (successFlash.value > 0.001f) {
                Box(Modifier.matchParentSize().alpha(successFlash.value).background(Color.White))
            }
        }
    }
}

/**
 * 登录 / 注册输入框（2026-10-23 全局 coui 化）：原自建「MIUI 卡片式输入框 + 自绘清除 / 锁图标」
 * 改为 coui 原生 [TextField]：Rectangle 圆角描边框（聚焦描边转主色由 coui 提供）+ 白底（玻璃卡上保证可读）
 * + 浮动标签（useLabelAsPlaceholder=false，聚焦/有值时标签上浮，相当于旧版左侧小标题）。
 * - 普通字段：coui 自带「快速清除」键（showClearButton）；
 * - 密码字段：coui 自带「眼睛」显隐切换（showPasswordToggle，隐藏时自动掩码）。
 */
@Composable
private fun LoginField(
    title: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    val cs = COUITheme.colorScheme
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        backgroundMode = TextFieldMode.Rectangle,
        colors = TextFieldDefaults.textFieldColors(backgroundColor = cs.surfaceContainer),
        label = title,
        useLabelAsPlaceholder = false,
        singleLine = true,
        showClearButton = !password,
        showPasswordToggle = password,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}

// ───────────────────────── 学校选择（底部弹层 · coui OverlayBottomSheet · 模糊搜索） ─────────────────────────

/**
 * 注册页「学校」选择字段：coui 白卡 + coui [ArrowPreference]（标题「学校」+ 右侧当前值 + 箭头），
 * 圆角与 coui TextField 一致；点击弹出底部选择器（[SchoolPickerSheet]）。未选时右侧显示「请选择学校」。
 */
@Composable
private fun SchoolSelectorField(value: String, onClick: () -> Unit) {
    val cs = COUITheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = TextFieldDefaults.CornerRadius,
        insideMargin = PaddingValues(0.dp),
        colors = kewuCardColors(),
    ) {
        ArrowPreference(
            title = "学校",
            onClick = { haptic(HapticKind.Tick); onClick() },
            endActions = {
                Text(
                    if (value.isEmpty()) "请选择学校" else value,
                    color = if (value.isEmpty()) cs.onSurfaceVariantSummary else cs.onSurface,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp),
                )
            },
        )
    }
}

/**
 * 学校选择底部弹层：外壳为 `KewuBottomSheet`（= coui 官方 OverlayBottomSheet，窗口内覆盖层：
 * 抓手 / 滑入滑出 / 遮罩 / 返回键全是 coui 原生行为；不用 coui `Window*` 系列——其在 CMP 1.11
 * 下 `DialogProperties` 签名不匹配闪退，详见交接文档 12.1）。
 * 内容区：coui [InputField] 胶囊搜索框 + coui [BasicComponent] 结果行（名称/拼音/首字母模糊搜，
 * 全量 2900+ 所高校）+ 未命中时「使用『xxx』」自由输入兜底。
 *
 * 关闭动画：选中的值先暂存 [pendingSelect]，等弹层退场动画结束（KewuBottomSheet 的 onDismiss）
 * 再回调，退场动画能完整播放。
 */
@Composable
internal fun SchoolPickerSheet(selected: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
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
        // 搜索框：coui 原生胶囊搜索输入（自带放大镜与清除键）
        InputField(
            query = query,
            onQueryChange = { query = it },
            onSearch = {},
            expanded = false,
            onExpandedChange = {},
            label = "搜索学校（名称 / 拼音 / 首字母）",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))

        // 结果列表（模糊搜索：名称 / 全拼 / 简写；全量 2900+ 所）
        // ★ 2026-10-26 兼容性：搜索放后台线程（Dispatchers.Default），主线程只渲染结果；
        //   searched = 「已算完的那个查询串」，没算完前不显示「使用『xxx』」兜底项，避免一闪而过。
        val searchState by produceState<Pair<String, List<University>>?>(null, query) {
            value = query to withContext(Dispatchers.Default) {
                try { searchUniversities(query) } catch (_: Throwable) { emptyList() }
            }
        }
        val results = searchState?.second.orEmpty()
        val searched = searchState?.first == query
        Box(Modifier.fillMaxWidth().height(340.dp)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                // 未命中时给「自由输入」兜底项（后端 register 接受任意 nickname/school 字符串）
                if (searched && results.isEmpty() && query.isNotBlank()) {
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

/** 选项行：coui [BasicComponent]（学校名 + 省份副标题），选中态主色标题 + coui Ok 勾；整行可点。 */
@Composable
private fun SchoolOptionRow(name: String, sub: String, isSelected: Boolean, onClick: () -> Unit) {
    val cs = COUITheme.colorScheme
    BasicComponent(
        title = name,
        titleColor = if (isSelected) BasicComponentDefaults.titleColor(color = cs.primary) else BasicComponentDefaults.titleColor(),
        summary = sub.ifEmpty { null },
        onClick = { haptic(HapticKind.Tick); onClick() },
        endActions = if (isSelected) {
            { Icon(COUIIcons.Ok, contentDescription = "已选", tint = cs.primary, modifier = Modifier.size(20.dp)) }
        } else {
            null
        },
    )
}
