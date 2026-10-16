package com.kewu.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.MaterialTheme
import com.kewu.app.KewuBlue
import com.kewu.app.ui.liquidglass.LiquidGlassSurface
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import kotlin.math.abs
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 底部导航栏的一个 tab。
 *
 * ★ 配色约定（用户要求）：**页面背景一律走 `MaterialTheme.colorScheme`，不允许自己造背景色**
 * （原来每个 tab 一套硬编码渐变已全部去掉）。所以这里只留索引和文字。
 */
private data class NavItem(
    val index: Int,
    val label: String,
)

private val NAV_ITEMS = listOf(
    NavItem(0, "课堂"),
    NavItem(1, "助教"),
    NavItem(2, "任务"),
    NavItem(3, "我的"),
)

@Composable
fun MainScreen(
    onGoToTab: (Int) -> Unit = {},
    onLogout: () -> Unit = {},
) {
    var selected by remember { mutableStateOf(0) }
    var preview by remember { mutableStateOf(0) }
    var previewFloat by remember { mutableStateOf(0f) }
    // 向下浏览内容时收起底部导航栏（课堂页滚动时回调），回到顶部自动展开
    var navHidden by remember { mutableStateOf(false) }
    // ★ 泡泡中心 / tab 宽度提升到本层：页内入口（作业待交→任务、头像→我的）也要能驱动
    //   泡泡滑行。此前 centerAnim 藏在导航栏内部，goToTab 只改 selected/preview 不动它
    //   → 内容切了、标签高亮了，泡泡却卡在旧 tab（用户实测 bug）。
    val navScope = rememberCoroutineScope()
    val tabW = remember { mutableStateOf(0f) }
    val centerAnim = remember { Animatable(0f) }
    var tabSwitchJob by remember { mutableStateOf<Job?>(null) } // 内容区 crossfade 动画协程
    // 收起动画：从大到小 + 淡出 + 轻微下沉。低阻尼弹簧（damping 0.42）让它「弹性」消失
    // —— 缩过头再回弹一下，不是线性淡出。
    val navVisible by animateFloatAsState(
        targetValue = if (navHidden) 0f else 1f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = 300f),
        label = "navVisible",
    )
    val backdrop = rememberGlassBackdrop()
    // 主界面首次入场：TopBar 下滑、内容上浮、导航栏弹起。★ 全部用 placement 位移
    // （lambda 版 offset = 布局平移，不产生离屏层）—— 内容区/导航栏都是 coui 玻璃
    // 采样者，套 alpha/graphicsLayer 有首帧 SIGSEGV 风险，只能位移。
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val enterT by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "mainEnter",
    )
    // 页内入口跳 tab（课堂页头像 → 「我的」= 3、作业待交 → 「任务」= 2 等）。
    // ★ 必须在本 Composable 里自己接上：App.kt 只传 onLogout，形参 onGoToTab 默认是
    //   **空 lambda** —— 不接的话这些点击全部静默无效（实测：作业卡反复点击无反应，
    //   事件已送达 App 但没人改 selected/preview/previewFloat）。接完再转调外部
    //   onGoToTab（调用方若有传），内外两边都生效。
    // ★ 泡泡同步：centerAnim 弹簧滑到目标 tab 中心 —— 滑行中 centerAnim.value 与
    //   targetCenter 有差 → 导航栏的 active 自动为真 → 泡泡放大、按速度横向拉伸
    //   （加速度扭曲那套），滑到再缩回，与点选 tab 完全同一套观感。
    // ★ 内容区 crossfade 也走弹簧动画（previewFloat 连续变化 → 两页 alpha 混合），
    //   与拖拽跟手复用同一机制（实测无 coui SIGSEGV）；拖拽直写 previewFloat 不受影响。
    val goToTab: (Int) -> Unit = { idx ->
        if (idx in NAV_ITEMS.indices) {
            selected = idx
            preview = idx
            navHidden = false // 切 tab 时一律把导航栏放出来
            if (tabW.value > 0f) {
                navScope.launch {
                    centerAnim.animateTo(
                        targetValue = idx * tabW.value + tabW.value / 2f,
                        animationSpec = spring(dampingRatio = 0.62f, stiffness = 380f),
                    )
                }
            }
            tabSwitchJob?.cancel()
            tabSwitchJob = navScope.launch {
                val anim = Animatable(previewFloat)
                anim.animateTo(
                    targetValue = idx.toFloat(),
                    animationSpec = spring(dampingRatio = 0.68f, stiffness = 420f),
                ) {
                    previewFloat = value
                }
            }
        }
        onGoToTab(idx)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background), // 全 app 底色走 M3，不自造色
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(), // 顶部避开状态栏，内容不被状态栏遮挡
        ) {
            TopBar(
                // 入场：从上方滑下（TopBar 无玻璃采样，alpha 可用）
                Modifier
                    .offset { IntOffset(0, ((1f - enterT) * -14f).dp.roundToPx()) }
                    .alpha(enterT),
            )
            // 内容区：记录进玻璃层，供底部导航栏采样折射。
            // 用 previewFloat（小数 tab 索引）驱动两页交叉淡入淡出 → 拖拽时丝滑过渡。
            val n = NAV_ITEMS.size
            val pf = previewFloat.coerceIn(0f, (n - 1).toFloat())
            val from = pf.toInt().coerceIn(0, n - 1)
            val to = (from + 1).coerceIn(0, n - 1)
            val f = (pf - from).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .layerBackdrop(backdrop)
                    // 入场：从下方浮上（placement 位移，不套 layer）
                    .offset { IntOffset(0, ((1f - enterT) * 20f).dp.roundToPx()) },
            ) {
                if (f < 0.001f) {
                    TabContent(NAV_ITEMS[from], goToTab, onLogout) { navHidden = it }
                } else {
                    Box(Modifier.fillMaxSize().alpha(1f - f)) {
                        TabContent(NAV_ITEMS[from], goToTab, onLogout) { navHidden = it }
                    }
                    Box(Modifier.fillMaxSize().alpha(f)) {
                        TabContent(NAV_ITEMS[to], goToTab, onLogout) { navHidden = it }
                    }
                }
            }
        }
        AppleLiquidGlassNavBar(
            items = NAV_ITEMS,
            selected = selected,
            onSelect = {
                selected = it
                preview = it
                navHidden = false // 切 tab 时一律把导航栏放出来
                // 内容区交叉淡入也走弹簧（与泡泡滑行同步）；centerAnim 由 navGestures
                // 内部驱动，这里不重复动画它。
                tabSwitchJob?.cancel()
                tabSwitchJob = navScope.launch {
                    val anim = Animatable(previewFloat)
                    anim.animateTo(
                        targetValue = it.toFloat(),
                        animationSpec = spring(dampingRatio = 0.68f, stiffness = 420f),
                    ) {
                        previewFloat = value
                    }
                }
            },
            preview = preview,
            onPreview = { preview = it },
            previewFloat = previewFloat,
            onPreviewFloat = {
                // 拖拽跟手：直写 + 取消在途的 tab 切换动画（否则两种驱动互相打架）
                tabSwitchJob?.cancel()
                previewFloat = it
            },
            backdrop = backdrop,
            hidden = navHidden,
            tabW = tabW,
            centerAnim = centerAnim,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .scale((0.35f + 0.65f * navVisible).coerceAtLeast(0f))
                .alpha(navVisible.coerceIn(0f, 1f))
                // 入场：从屏幕底部弹上来（与收起动画的 offset 合并；都是 placement 位移）
                .offset {
                    IntOffset(
                        0,
                        (((1f - enterT) * 40f).dp.roundToPx()) + (((1f - navVisible) * 14f).dp.roundToPx()),
                    )
                },
        )
    }
}

@Composable
private fun TopBar(modifier: Modifier = Modifier) {
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
                    .size(36.dp)
                    .background(cs.primary, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) { Text("课", color = cs.onPrimary, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(12.dp))
            Text("课悟 · Kewu", color = cs.onSurface, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TabContent(
    item: NavItem,
    onGoToTab: (Int) -> Unit,
    onLogout: () -> Unit = {},
    onNavHide: (Boolean) -> Unit,
) {
    if (item.index == 0) {
        // 课堂主页：大状态卡 + 统计条 + 课程卡片流（仿 KernelSU 主页信息架构）。
        // 注意：Coui 玻璃的采样者必须在被采样层**之外**，所以 ClassroomPage 内部自己开了一个
        // pageBackdrop（背景层）并让玻璃内容与它平级；这里不需要把导航栏的 backdrop 传进去。
        ClassroomPage(onGoToTab = onGoToTab, onNavHide = onNavHide)
        return
    }
    if (item.index == 2) {
        // 任务页：课堂页「作业待交」小卡的跳转目标（作业 / 待办列表，可勾选完成）
        TaskPage(onNavHide = onNavHide)
        return
    }
    if (item.index == 3) {
        // 我的页：个人主页 + 校园功能入口；点「退出登录」回到启动屏。
        MyPage(onLogout = onLogout, onNavHide = onNavHide)
        return
    }
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            // ★ 背景一律走 M3 语义色，不再每个 tab 一套硬编码渐变。
            // 顺带解决了原来的老问题：渐变透过导航栏玻璃会变成"上浅下深 + 中间分界线"，
            // 纯色背景下「身后底色」天然均匀，玻璃怎么采样都不会有色差。
            .background(cs.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(item.label, color = cs.onSurface, fontSize = 42.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("内容区域 · 演示页", color = cs.onSurfaceVariant, fontSize = 15.sp)
        }
    }
}

@Composable
private fun AppleLiquidGlassNavBar(
    items: List<NavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    preview: Int,
    onPreview: (Int) -> Unit,
    previewFloat: Float,
    onPreviewFloat: (Float) -> Unit,
    backdrop: LayerBackdrop,
    hidden: Boolean = false,
    tabW: MutableState<Float>,
    centerAnim: Animatable<Float, AnimationVector1D>,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val expanded = remember { mutableStateOf(false) }   // 是否已长按进入「拾取态」
    val dragging = remember { mutableStateOf(false) }   // 拾取态下是否正在滑动
    val settling = remember { mutableStateOf(false) }   // 松手吸合中：立刻由大变小，不等滑行结束
    val dragCenter = remember { mutableStateOf(0f) }    // 泡泡中心 x（跟随手指）
    val dragVel = remember { mutableStateOf(0f) }       // 拖拽速度（用于液体拉伸形变）
    val tapExpand = remember { mutableStateOf(false) }   // 点选泡泡：复刻长按放大效果，tapExpandMs 后自动还原
    val barHeight = 66.dp                              // 导航栏（托盘）高度
    // ★ 静止态泡泡就比导航栏**高一点、宽一点**：上下各凸出 ~5dp、左右也比单个 tab 明显更宽，
    //   于是在课堂页（tab 0，最左）泡泡左缘会直接探到托盘外面 —— 一眼就能看到"这里有个泡泡"，
    //   而不是需要它动起来才看得出。
    val bubbleRestH = 76.dp                            // 静止：略高于导航栏（原为 = barHeight，完全贴齐看不出）
    val bubbleActiveH = 100.dp                         // 活动（长按 / 切换中）：再弹高
    val barBottomGap = 24.dp                           // 栏底距屏幕底
    val containerHeight = barHeight + barBottomGap * 2 // 固定容器高：栏固定不动，泡泡绕栏心伸缩
    val bubbleWFactor = 1.34f                          // 泡泡宽度占单 tab 比例（静止就这么宽：横向胶囊 + 探出托盘）

    // 长按拾取 / 点选放大时的额外拉宽（0 → 0.42，总宽 1.50×tabW）：
    // 活动态高度 90dp > 静止 66dp，宽度若不变会成"竖椭圆"；拉宽后宽(≈321px) > 高(≈247px) → 横向椭圆。
    // settling（松手吸合中）换快弹簧 → 宽度立刻缩回，与高度/缩放同步"快点变小"。
    // 弹簧必须快：点选放大只保持 350ms，慢一步就"还没变宽就已经变小了"。
    val bubbleWBoost by animateFloatAsState(
        targetValue = if (expanded.value || tapExpand.value) 0.42f else 0f,
        animationSpec = if (settling.value) spring(dampingRatio = 0.8f, stiffness = 650f)
        else spring(dampingRatio = 0.65f, stiffness = 800f),
        label = "bubbleWBoost",
    )

    // 泡泡宽度 = 单 tab 的 (bubbleWFactor + bubbleWBoost)。
    // 不再给宽度叠第二层弹簧：boost 弹簧 + indicatorW 弹簧串联 ~600ms，点选放大 350ms 的窗口里
    // 根本宽不起来；直接取当前值，染色边界也因此与泡泡视觉边缘严格同步（不再各跑各的）。
    val indicatorW = tabW.value * (bubbleWFactor + bubbleWBoost)
    val indicatorWdp = (indicatorW / density.density).dp

    // 横向「液体拉伸」见下方：拖拽时按手指速度、点选滑行时按 centerAnim 速度，二者取较大者。
    // 长按拾取时，非聚焦 tab 淡出，聚焦 tab 强调（对齐 iOS 手感）
    val focusT by animateFloatAsState(
        targetValue = if (expanded.value) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 380f),
        label = "focusT",
    )
    // 泡泡中心：用 Animatable 手动驱动 —— 拖拽时 snapTo 跟手、松手时 animateTo 吸到最近的 tab，
    // 避免 animateFloatAsState 在拖拽中"值过期"导致松手瞬移回旧 tab。
    // （Animatable 由 MainScreen 持有并传入：页内入口跳 tab 时也要能驱动它滑行，
    //   否则泡泡会卡在旧 tab —— 见 MainScreen 里 goToTab 的注释。）
    var centerPositioned by remember { mutableStateOf(false) }
    LaunchedEffect(tabW.value) {
        if (tabW.value > 0f && !centerPositioned) {
            centerPositioned = true
            centerAnim.snapTo(selected * tabW.value + tabW.value / 2f)
        }
    }
    // 液体拉伸：横向拉长、纵向压扁。拖拽时随手指速度；点选切换滑行时随「泡泡中心的运动速度」——否则在
    // 「课堂点任务」这类纯点选切换里，centerAnim 直接 animateTo 滑行、dragVel 恒为 0，泡泡只会平移、不拉伸，
    // 观感断了。两种来源取较大者，保证「只要泡泡在动就有横向延伸」。
    // （centerAnim.velocity 在它动画时实时给出 px/s；点选上限按滑行观感调小，避免一下拉满。）
    val stretch by animateFloatAsState(
        targetValue = (if (dragging.value) (abs(dragVel.value) * 0.045f).coerceIn(0f, 0.5f) else 0f)
            .coerceAtLeast(if (!dragging.value) (abs(centerAnim.velocity) * 0.0006f).coerceIn(0f, 0.32f) else 0f),
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 240f),
        label = "stretch",
    )
    val bubbleCenter = if (dragging.value) dragCenter.value else centerAnim.value
    val bubbleOffsetX = bubbleCenter - indicatorW / 2f

    // 活动态判定：长按拾取 / 拖拽中 / 点选泡泡放大（0.5s）/ 正在切换（中心尚未到位）。
    // 静止时 height=barHeight（宽扁胶囊，边界贴栏）；活动时 height>bubbleActiveH（弹高）。
    // settling（松手吸合中）时不再看"中心未到位" → 泡泡立刻由大变小，吸合滑行并行跑完；
    // 直接点选滑行不走 settling，仍是"滑行中保持变大、滑到再缩回"的原有速度（用户确认合适，不动）。
    val targetCenter = selected * tabW.value + tabW.value / 2f
    val active = expanded.value || dragging.value || tapExpand.value ||
            (abs(centerAnim.value - targetCenter) > 2f && !settling.value)
    // 活动时轻微整体放大（q 弹过冲）；配合下面的 height 动画 → 长按/切换时"变大变高"。
    // settling（松手吸合中）换更快的弹簧 → 拖拽松手后泡泡由大变小"快点"；点选路径保持原速。
    val bubbleScale by animateFloatAsState(
        targetValue = if (active) 1.12f else 1f,
        animationSpec = if (settling.value) spring(dampingRatio = 0.8f, stiffness = 650f)
        else spring(dampingRatio = 0.34f, stiffness = 320f),
        label = "bubbleScale",
    )
    // 泡泡高度：静止=栏高，活动=高于栏；低阻尼弹簧带来回弹。
    // settling 时同样换快弹簧（松手即刻缩回栏高）。
    val bubbleHeight by animateDpAsState(
        targetValue = if (active) bubbleActiveH else bubbleRestH,
        animationSpec = if (settling.value) spring(dampingRatio = 0.8f, stiffness = 650f)
        else spring(dampingRatio = 0.58f, stiffness = 360f),
        label = "bubbleHeight",
    )
    // 彩虹渐隐时序（用户要求）：松手时 active 立刻变 false，但泡泡高度/缩放弹簧还要
    // ~400ms 才缩回静止尺寸——若直接用 active 当开关，彩虹会在泡泡还大着时瞬间消失。
    // 所以分两级：只要 bubbleHeight 还没回到静止高度就保持全亮；真正回位之后再 tween 渐隐。
    // （松手回弹有 settling 快弹簧 / 点选慢弹簧两套速度，用「实际高度」当条件比写死延时更稳。）
    val rainbowDone = !active && bubbleHeight <= bubbleRestH
    val rainbowFade by animateFloatAsState(
        targetValue = if (rainbowDone) 0f else 1f,
        animationSpec = tween(
            durationMillis = if (rainbowDone) 260 else 150,
            easing = if (rainbowDone) LinearOutSlowInEasing else FastOutSlowInEasing,
        ),
        label = "rainbowFade",
    )
    // 长按判定触发时导航栏托盘轻微放大；松手（长按消失）立马回弹（带回弹过冲）。
    // 坑：animateFloatAsState 默认 visibilityThreshold=0.01，而托盘缩放 delta 仅 0.035（3.5 个阈值）——
    // Compose 的 FloatSpringSpec 按"进入 1 个阈值内"估算时长并截断动画、吸附到目标值，
    // 估算时长(~106ms)恰好落在弹簧第一次穿过目标时刻，undershoot 还没开始就被吃掉 → 看不到回弹。
    // 故回弹弹簧显式给 0.0005f 小阈值（70 个阈值单位 → 估算时长 ~384ms），弹簧跑完整轨迹，过冲清晰可见。
    // 放大弹簧保持原参数/默认阈值（该手感用户已确认，不动）。
    val trayScale by animateFloatAsState(
        targetValue = if (expanded.value) 1.035f else 1f,
        animationSpec = if (settling.value)
            spring(dampingRatio = 0.4f, stiffness = 800f, visibilityThreshold = 0.0005f)
        else spring(dampingRatio = 0.55f, stiffness = 320f),
        label = "trayScale",
    )

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 58.dp)
            .height(containerHeight),
    ) {
        // 1) 底部玻璃胶囊托盘（淡，提供 bar 质感；全圆角无直边）
        LiquidGlassSurface(
            backdrop = backdrop,
            shape = RoundedCornerShape(33.dp),
            blurRadius = 22.dp,
            tintAlpha = 0.12f,
            modifier = Modifier.fillMaxWidth().height(barHeight).align(Alignment.Center).scale(trayScale),
        ) {
            // 托盘也描一圈极淡的棱边：页面背景改成 M3 纯色后，光靠白色 tint 在浅色底上
            // 拓不出边界，导航栏会"消失"；有了这圈边，泡泡探出去才有参照物。
            Canvas(Modifier.fillMaxSize()) {
                val h = size.height
                val r = h / 2f
                val edge = h * 0.011f
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.045f),
                    topLeft = Offset(edge / 2f, edge / 2f),
                    size = Size(size.width - edge, h - edge),
                    cornerRadius = CornerRadius(r - edge / 2f),
                    style = Stroke(width = edge),
                )
                val rimW = h * 0.032f
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.32f),
                    topLeft = Offset(rimW, rimW),
                    size = Size(size.width - rimW * 2f, h - rimW * 2f),
                    cornerRadius = CornerRadius(r - rimW),
                    style = Stroke(width = rimW),
                    blendMode = BlendMode.Screen,
                )
            }
        }

        // 2) 可交互玻璃泡泡：选中指示 + 长按放大 + 拖拽切换；不被托盘裁剪，可「弹出」
        //    外层盒留出余量用于柔和阴影外溢；内部玻璃盒单独缩放（阴影不随之膨胀）
        val shadowPadX = 18.dp
        val shadowPadY = 14.dp
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset {
                    IntOffset(
                        (bubbleOffsetX - shadowPadX.value * density.density).toInt(),
                        0,
                    )
                }
                .width(indicatorWdp + shadowPadX * 2)
                .height(bubbleHeight + shadowPadY * 2),
            contentAlignment = Alignment.Center,
        ) {

            // 玻璃泡泡本体（单独缩放；叠加液体拉伸形变）
            Box(
                Modifier
                    .clip(RoundedCornerShape(50.dp))
                    .width(indicatorWdp)
                    .height(bubbleHeight)
                    .scale(bubbleScale * (1f + stretch), bubbleScale * (1f - stretch * 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                // ★ 更透 + 透镜放大：tint 更淡、blur 更小，让身后的页面内容真的"透过来"；
                //   再由 magnify 把透过来的内容在泡泡里放大 —— 这才是液态玻璃的折射感，
                //   光靠"淡"只会变成一块看不清内容的灰片。
                val bubbleActive = expanded.value || tapExpand.value
                LiquidGlassSurface(
                    backdrop = backdrop,
                    shape = RoundedCornerShape(50),
                    blurRadius = if (bubbleActive) 16.dp else 12.dp,
                    tintAlpha = if (bubbleActive) 0.11f else 0.075f,
                    magnify = if (bubbleActive) 1.36f else 1.26f,
                ) {
                    // 棱边：泡泡"透"了之后，**必须**靠棱边才能读出来是个泡泡 ——
                    // Apple 液态玻璃的可见性主要来自棱边高光，而不是内部填充。所以这里
                    // 常态就画一圈对称的棱边（外圈极淡暗轮廓 + 内圈白高光），
                    // 上下完全对称，不会出现之前那种"上浅下深 + 中间分界线"。
                    Canvas(Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val r = h / 2f
                        // 外圈：极淡的暗轮廓（浅色背景上给泡泡一个边界）
                        val edge = h * 0.012f
                        drawRoundRect(
                            color = Color.Black.copy(alpha = 0.05f),
                            topLeft = Offset(edge / 2f, edge / 2f),
                            size = Size(w - edge, h - edge),
                            cornerRadius = CornerRadius(r - edge / 2f),
                            style = Stroke(width = edge),
                        )
                        // 内圈：白高光（棱边反光）
                        val rimW = h * 0.045f
                        drawRoundRect(
                            color = Color.White.copy(alpha = if (bubbleActive) 0.60f else 0.42f),
                            topLeft = Offset(rimW, rimW),
                            size = Size(w - rimW * 2f, h - rimW * 2f),
                            cornerRadius = CornerRadius(r - rimW),
                            style = Stroke(width = rimW),
                            blendMode = BlendMode.Screen,
                        )
                        // 非默认态（active：长按拾取 / 拖拽中 / 点选放大 / 切换滑行中——
                        // 正是泡泡「放大」的时候）再叠两层彩虹描边。真实液态玻璃的彩虹来自
                        // 薄膜干涉（前/后表面两次反射的光程差），观感是「一带颜色」而不是
                        // 「一根描边」：主带贴白高光内侧、细而亮；次带稍靠内、宽而柔。
                        // 两层错开分布，物理上才像一层膜，而不是同一条边描了两遍。
                        // 彩虹描边（非默认态）。两带都往棱边收、变细——之前铺到 [0.07h,0.17h]
                        // 太占面积，像给泡泡描了条粗边；现在主带 [0.058h,0.084h]、次带
                        // [0.095h,0.125h]，紧贴白高光内侧，远看是棱边泛光、近看才分出两级条纹。
                        // ★ 混合模式必须用默认 SrcOver，不能 Screen：Screen 是「相加提亮」，
                        //   在 M3 浅色背景上会把彩色直接洗成白色（实测 sat≈0，肉眼完全看不见）；
                        //   SrcOver 才能让光谱真正透出来。（图标那圈色散用 Screen 没事——图标本身是深色。）
                        // ★ 透明度乘 rainbowFade：松手后先等泡泡缩回静止高度，再渐隐 260ms，
                        //   不会出现「泡泡还大着彩虹先没」的断电感（rainbowDone 见上方）。
                        if (rainbowFade > 0.01f) {
                            val mainInset = h * 0.058f   // 白高光占 [0.023h, 0.068h]，主带紧贴其内
                            val mainW = h * 0.026f
                            drawRoundRect(
                                brush = spectralBrush(alpha = 0.55f * rainbowFade),
                                topLeft = Offset(mainInset, mainInset),
                                size = Size(w - mainInset * 2f, h - mainInset * 2f),
                                cornerRadius = CornerRadius(r - mainInset),
                                style = Stroke(width = mainW),
                            )
                            val softInset = h * 0.095f
                            val softW = h * 0.030f
                            drawRoundRect(
                                brush = spectralBrush(alpha = 0.26f * rainbowFade),
                                topLeft = Offset(softInset, softInset),
                                size = Size(w - softInset * 2f, h - softInset * 2f),
                                cornerRadius = CornerRadius(r - softInset),
                                style = Stroke(width = softW),
                            )
                        }
                    }
                }
            }
        }

        // 3) 标签行（最上层，承载手势：点选 / 长按放大 / 拖拽切换）
        Row(
            Modifier
                .fillMaxWidth()
                .height(barHeight)
                .align(Alignment.Center)
                .onSizeChanged { tabW.value = it.width.toFloat() / items.size }
                // 收起态（下滑隐藏）时不挂手势：scale/alpha 只影响绘制、不影响点击区域，
                // 若不摘掉手势，导航栏看不见却仍能误触。
                .then(if (hidden) Modifier else Modifier.pointerInput(Unit) {
                    navGestures(
                        scope = scope,
                        tabW = tabW,
                        itemCount = items.size,
                        expanded = expanded,
                        dragging = dragging,
                        settling = settling,
                        dragCenter = dragCenter,
                        dragVel = dragVel,
                        bubbleWFactor = bubbleWFactor,
                        centerAnim = centerAnim,
                        tapExpand = tapExpand,
                        onSelect = onSelect,
                        onPreview = onPreview,
                        onPreviewFloat = onPreviewFloat,
                    )
                }),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { i, item ->
                // 泡泡真实边缘离该 tab 中心越近 → rim 越大（玻璃棱边色散/薄膜干涉只发生在棱边，中心无彩虹）
                val tx = tabW.value * (i + 0.5f)
                val scaleH = bubbleScale * (1f + stretch)
                val half = (indicatorW / 2f) * scaleH
                val ble = bubbleCenter - half
                val bre = bubbleCenter + half
                val rim = if (tabW.value > 1f) {
                    val dL = abs(tx - ble)
                    val dR = abs(tx - bre)
                    val dEdge = if (dL < dR) dL else dR
                    (1f - dEdge / (tabW.value * 0.6f)).coerceIn(0f, 1f)
                } else 0f
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    // ble/bre/tx：泡泡左右缘与该 tab 中心（Row 坐标）→ NavTab 里算"泡泡框住了图标多少"
                    NavTab(
                        item,
                        selected = i == preview,
                        dimT = if (i == preview) 0f else focusT,
                        rim = rim,
                        coverL = ble,
                        coverR = bre,
                        tabCenterX = tx,
                        tabLeftX = tabW.value * i,
                    )
                }
            }
        }
    }
}

private suspend fun PointerInputScope.navGestures(
    scope: CoroutineScope,
    tabW: MutableState<Float>,
    itemCount: Int,
    expanded: MutableState<Boolean>,
    dragging: MutableState<Boolean>,
    settling: MutableState<Boolean>,
    dragCenter: MutableState<Float>,
    dragVel: MutableState<Float>,
    bubbleWFactor: Float,
    centerAnim: Animatable<Float, AnimationVector1D>,
    tapExpand: MutableState<Boolean>,
    onSelect: (Int) -> Unit,
    onPreview: (Int) -> Unit,
    onPreviewFloat: (Float) -> Unit,
) {
    val longPressMs = 120L   // 长按判定：稍按一会即进入"拾取/跟手"态
    // 点选泡泡的放大保持时长：与点选切换的滑行到位时长一致（滑行 spring(0.7, 420) ≈350ms 落定），
    // 两种"放大"观感统一（之前 500ms 偏长）
    val tapExpandMs = 350L
    val moveCancelPx = 12f
    var tapExpandJob: Job? = null   // 点选泡泡的放大计时（新手势/松手/再次点选时取消）
    var releaseJob: Job? = null     // 上一步松手吸合协程（新手势开始时必须取消：它跑完时 finally 会清
    // expanded，若落在新手势长按保持期间，托盘/泡泡会在手指未松时就回弹）
    awaitPointerEventScope {
        while (true) {
            val down = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: continue
            settling.value = false // 新手势开始：恢复点选路径的原有速度逻辑
            tapExpandJob?.cancel() // 取消可能残留的点选放大计时
            releaseJob?.cancel() // 取消上一步松手吸合协程，防止其 finally 在本次长按保持期间清 expanded
            // （否则第二次起长按会出现"刚放大就回弹、手指还没松"）
            val downX = down.position.x
            var engaged = false
            var isTap = true
            var lastCx = downX
            val job = scope.launch {
                delay(longPressMs)
                engaged = true
                expanded.value = true
                dragging.value = true
                val half = tabW.value * bubbleWFactor / 2f
                dragCenter.value = downX.coerceIn(half, tabW.value * itemCount - half)
                onPreview(tabIndexFromX(downX, tabW.value, itemCount))
                onPreviewFloat(tabIndexFloatFromX(downX, tabW.value, itemCount))
            }
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (!engaged) {
                    val dx = change.position.x - downX
                    val dy = change.position.y - down.position.y
                    if (abs(dx) > moveCancelPx) {
                        // 手指一横向移动 → 立即进入拖拽跟手（取消长按计时），不再卡一下再瞬移
                        job.cancel()
                        engaged = true
                        expanded.value = true
                        dragging.value = true
                    } else if (abs(dy) > moveCancelPx) {
                        // 竖向移动 → 视为其它手势，放弃
                        job.cancel()
                        isTap = false
                        break
                    } else {
                        continue
                    }
                }
                // engaged：跟手处理
                isTap = false
                val half = tabW.value * bubbleWFactor / 2f
                val cx = change.position.x.coerceIn(half, tabW.value * itemCount - half)
                val dv = cx - lastCx
                lastCx = cx
                // 速度低通滤波 → 拖得越快拉得越长（加速度形变由 stretch 弹簧过冲体现）
                dragVel.value = dragVel.value * 0.6f + dv * 0.4f
                dragCenter.value = cx
                onPreview(tabIndexFromX(cx, tabW.value, itemCount))
                onPreviewFloat(tabIndexFloatFromX(cx, tabW.value, itemCount))
                change.consume()
            }
            job.cancel()
            dragVel.value = 0f
            if (engaged) {
                tapExpandJob?.cancel() // 松手：取消可能残留的点选放大计时（否则放大状态会续住）
                // 松手：算离哪边更近 → 被"吸"过去。先 snap 中心到松手点、再切 dragging=false（位置不变，无跳变），
                // 最后 animateTo 吸合。expanded 放 finally 里清：快速连滑/紧接着点击时，本次吸合会被下一次
                // animateTo 取消，若不用 finally，expanded 会永远停在 true → 泡泡卡在变大无法变小。
                val ni = tabIndexFromX(dragCenter.value, tabW.value, itemCount)
                releaseJob = scope.launch {
                    centerAnim.snapTo(dragCenter.value)
                    dragging.value = false
                    // 松手即刻缩回：settling=true 让 active 不再被"中心未到位"续住 → 泡泡立刻由大变小
                    // （吸合滑行与缩小并行跑）；expanded 也立即清，finally 里再兜底防"卡在变大"。
                    settling.value = true
                    expanded.value = false
                    try {
                        centerAnim.animateTo(
                            targetValue = ni * tabW.value + tabW.value / 2f,
                            animationSpec = spring(dampingRatio = 0.62f, stiffness = 380f),
                        )
                    } finally {
                        expanded.value = false
                    }
                }
                onSelect(ni)
            } else if (isTap) {
                // 点击：从当前位置平滑滑到目标 tab 中心
                val ti = tabIndexFromX(downX, tabW.value, itemCount)
                scope.launch {
                    centerAnim.animateTo(
                        targetValue = ti * tabW.value + tabW.value / 2f,
                        animationSpec = spring(dampingRatio = 0.7f, stiffness = 420f),
                    )
                }
                // 点在泡泡上：复刻长按的放大效果（同一套 scale/height/玻璃质感弹簧），
                // 保持 tapExpandMs（与点选切换滑行时长一致）后自动变回原大小。
                // （点当前 tab 时泡泡本来不动、没有反馈；点其它 tab 有滑行动画，不叠加放大）
                val halfW = tabW.value * bubbleWFactor / 2f
                if (abs(downX - centerAnim.value) <= halfW + 8f) {
                    tapExpandJob?.cancel()
                    tapExpandJob = scope.launch {
                        tapExpand.value = true
                        try {
                            delay(tapExpandMs)
                        } finally {
                            tapExpand.value = false
                        }
                    }
                }
                onSelect(ti)
            }
        }
    }
}

// 就近 tab：tab 中心在 (i+0.5)*tabW，两 tab 边界在 i*tabW，用 floor 即得"离哪边更近"。
// 拖拽预览高亮 / 松手落定都用它 → "停在哪就落到最近的那个 tab"（含第 0 个课堂，往左也能切到）。
private fun tabIndexFromX(x: Float, tabW: Float, itemCount: Int): Int {
    if (tabW <= 0f) return 0
    return (x / tabW).toInt().coerceIn(0, itemCount - 1)
}

private fun tabIndexFloatFromX(x: Float, tabW: Float, itemCount: Int): Float {
    if (tabW <= 0f) return 0f
    // 泡泡/手指落在某 tab 中心 (i+0.5)*tabW 时，预览值应恰为 i（纯该 tab，不交叉淡变）；
    // 落在两 tab 边界 i*tabW 时才是 0.5 混合。故减 0.5 对齐"tab 中心 = 整数索引"，
    // 否则长按不动时内容会与右侧 tab 半淡变（偏移半格）、拖到目标 tab 中心松手还会跳变。
    return (x / tabW - 0.5f).coerceIn(0f, (itemCount - 1).toFloat())
}

// 把内容裁剪到水平带 [left, right]（tab 局部坐标，px）：用于泡泡经过时只把被框住的那段文字染成主色。
// 选用自定义 Shape + clip，而非 Modifier.clipRect（本版 Compose 无此 Modifier 重载）。
private class HorizontalBandShape(
    private val left: Float,
    private val right: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ): Outline {
        val l = left.coerceIn(0f, size.width)
        val r = right.coerceIn(0f, size.width)
        val rr = if (l > r) l else r
        return Outline.Rectangle(Rect(l, 0f, rr, size.height))
    }
}

@Composable
private fun NavTab(
    item: NavItem,
    selected: Boolean,
    dimT: Float = 0f,
    rim: Float = 0f,
    coverL: Float = 0f,      // 泡泡左缘（Row 坐标）
    coverR: Float = 0f,      // 泡泡右缘（Row 坐标）
    tabCenterX: Float = 0f,  // 该 tab 中心 x（Row 坐标）
    tabLeftX: Float = 0f,    // 该 tab 左缘 x（Row 坐标）：文字染色 clipRect 需要把 Row 坐标换算到 tab 局部
) {
    // 未选中 = M3 的 onSurfaceVariant；选中 = KewuBlue（品牌强调色，不是背景色）
    val baseColor = if (selected) KewuBlue else MaterialTheme.colorScheme.onSurfaceVariant
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 420f),
        label = "tabScale",
    )
    // 长按拾取时，未聚焦 tab 整体淡出并轻微缩小
    val dimAlpha = 1f - 0.62f * dimT
    val dimScale = 1f - 0.08f * dimT
    val r = rim.coerceIn(0f, 1f)
    val w = if (selected) FontWeight.Bold else FontWeight.Medium
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(dimAlpha)
            .scale(dimScale),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.size(28.dp).scale(scale)) {
            val sz = size
            drawGlyph(item.index, baseColor, sz)
            // 泡泡经过：被泡泡框住的部分染上主色（经过多少染多少，其余保持原色）。
            // 换算到 canvas 坐标要除掉视觉缩放（selected 1.12 / 变暗 0.92），否则缩放后边缘对不齐。
            val visScale = scale * dimScale
            val cx = sz.minDimension / 2f
            if (visScale > 0.01f) {
                val cl = ((coverL - tabCenterX + cx * visScale) / visScale).coerceIn(0f, sz.width)
                val cr = ((coverR - tabCenterX + cx * visScale) / visScale).coerceIn(0f, sz.width)
                if (cr - cl > 0.5f) {
                    clipRect(left = cl, right = cr) {
                        drawGlyph(item.index, KewuBlue, sz)
                    }
                }
            }
            if (r > 0.01f) {
                // 真实色散：红/青沿相反方向错位（玻璃棱边把不同波长折射到不同位置）
                val off = r * sz.minDimension * 0.10f
                withTransform({ translate(off, off * 0.3f) }) {
                    drawGlyphStroke(item.index, size = sz, color = Color(0xFFFF2D55).copy(alpha = r))
                }
                withTransform({ translate(-off, -off * 0.3f) }) {
                    drawGlyphStroke(item.index, size = sz, color = Color(0xFF00E5FF).copy(alpha = r))
                }
                // 薄膜干涉式光谱描边（彩虹）
                drawGlyphStroke(item.index, size = sz, brush = spectralBrush(alpha = r))
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (r > 0.01f) {
                // 文字的彩虹描边：沿水平扇形铺开的光谱副本，两侧各露出红/紫镶边
                val spectral = listOf(
                    Color(0xFFFF0040), Color(0xFFFFA500), Color(0xFFFFFF00),
                    Color(0xFF00FF66), Color(0xFF00BFFF), Color(0xFF8A2BE2),
                )
                spectral.forEachIndexed { k, c ->
                    val dx = ((k - 2.5f) * r).dp
                    Text(
                        item.label,
                        color = c.copy(alpha = r * 0.55f),
                        fontSize = 13.sp,
                        fontWeight = w,
                        modifier = Modifier.offset(x = dx, y = 0.dp),
                    )
                }
            }
            Text(item.label, color = baseColor, fontSize = 13.sp, fontWeight = w)
            // 泡泡经过：被框住的文字部分染主色（与图标同一边界、同一 KewuBlue，消除"图标蓝、文字灰"的色断层）。
            // 文字盒 fillMaxWidth → 左缘 = tabLeftX（Row 坐标），故 clipRect 用覆盖边缘减 tabLeftX 换算到 tab 局部。
            // 文字盒随父 Column 的 dimScale 一同缩放，clip 与内容同步缩放，无需再除缩放。
            if (!selected && coverR - coverL > 0.5f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(HorizontalBandShape(coverL - tabLeftX, coverR - tabLeftX)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(item.label, color = KewuBlue, fontSize = 13.sp, fontWeight = w)
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlyph(
    index: Int,
    color: Color,
    size: Size,
) {
    val s = size.minDimension
    val stroke = Stroke(width = s * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (index) {
        0 -> { // 课堂：房子
            val roof = Path().apply {
                moveTo(s * 0.5f, s * 0.10f)
                lineTo(s * 0.08f, s * 0.46f)
                lineTo(s * 0.92f, s * 0.46f)
                close()
            }
            drawPath(roof, color, style = stroke)
            drawRect(
                color,
                topLeft = Offset(s * 0.24f, s * 0.46f),
                size = Size(s * 0.52f, s * 0.44f),
                style = stroke,
            )
        }

        1 -> { // 助教：老师指黑板（黑板框 + 内容线 + 人头圆环 + 肩膀 + 举起指黑板的手臂）
            // 黑板（右上）：圆角框 + 内部一条内容线
            drawRoundRect(
                color,
                style = stroke,
                topLeft = Offset(s * 0.36f, s * 0.05f),
                size = Size(s * 0.59f, s * 0.55f),
                cornerRadius = CornerRadius(s * 0.05f),
            )
            drawLine(
                color,
                Offset(s * 0.55f, s * 0.30f),
                Offset(s * 0.83f, s * 0.30f),
                strokeWidth = s * 0.075f,
                cap = StrokeCap.Round,
            )
            // 人头：圆环（右缘轻贴黑板左缘，保留原 SVG 头环与黑板穿插的关系）
            drawCircle(color, style = stroke, radius = s * 0.115f, center = Offset(s * 0.23f, s * 0.44f))
            // 肩膀：从左下往上拱到脖下、再垂到右肩
            val shoulders = Path().apply {
                moveTo(s * 0.02f, s * 0.85f)
                cubicTo(s * 0.05f, s * 0.70f, s * 0.15f, s * 0.625f, s * 0.24f, s * 0.645f)
                cubicTo(s * 0.35f, s * 0.67f, s * 0.43f, s * 0.74f, s * 0.48f, s * 0.80f)
            }
            drawPath(shoulders, color, style = stroke)
            // 手臂：从右肩斜向上指，圆头恰好点到黑板下缘
            drawLine(
                color,
                Offset(s * 0.46f, s * 0.79f),
                Offset(s * 0.585f, s * 0.565f),
                strokeWidth = s * 0.085f,
                cap = StrokeCap.Round,
            )
        }

        2 -> { // 任务：清单（圆角框 + 勾 + 两行条目 + 状态点）
            drawRoundRect(
                color,
                style = stroke,
                topLeft = Offset(s * 0.10f, s * 0.10f),
                size = Size(s * 0.80f, s * 0.80f),
                cornerRadius = CornerRadius(s * 0.17f),
            )
            val check = Path().apply {
                moveTo(s * 0.25f, s * 0.375f)
                lineTo(s * 0.335f, s * 0.46f)
                lineTo(s * 0.50f, s * 0.30f)
            }
            drawPath(
                check,
                color,
                style = Stroke(width = s * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            drawLine(
                color,
                Offset(s * 0.585f, s * 0.36f),
                Offset(s * 0.755f, s * 0.36f),
                strokeWidth = s * 0.075f,
                cap = StrokeCap.Round,
            )
            drawLine(
                color,
                Offset(s * 0.585f, s * 0.60f),
                Offset(s * 0.755f, s * 0.60f),
                strokeWidth = s * 0.075f,
                cap = StrokeCap.Round,
            )
            drawCircle(color, style = stroke, radius = s * 0.075f, center = Offset(s * 0.335f, s * 0.655f))
        }

        3 -> { // 我的：人形
            drawCircle(color, radius = s * 0.17f, center = Offset(s * 0.5f, s * 0.36f))
            drawArc(
                color,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(s * 0.22f, s * 0.50f),
                size = Size(s * 0.56f, s * 0.52f),
                style = stroke,
            )
        }
    }
}

// 薄膜干涉式光谱（彩虹）笔刷：玻璃棱边把不同波长折射/干涉成彩色镶边，alpha 随强度淡入。
private fun spectralBrush(alpha: Float = 1f): Brush = Brush.linearGradient(
    0.00f to Color(0xFFFF0040).copy(alpha = alpha),
    0.18f to Color(0xFFFFA500).copy(alpha = alpha),
    0.36f to Color(0xFFFFFF00).copy(alpha = alpha),
    0.54f to Color(0xFF00FF66).copy(alpha = alpha),
    0.72f to Color(0xFF00BFFF).copy(alpha = alpha),
    0.90f to Color(0xFF8A2BE2).copy(alpha = alpha),
    1.00f to Color(0xFFFF0040).copy(alpha = alpha),
)

// 用描边重绘图标轮廓（供色散错位 / 光谱描边叠加），color 或 brush 二选一
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlyphStroke(
    index: Int,
    size: Size,
    color: Color? = null,
    brush: Brush? = null,
    blendMode: BlendMode = BlendMode.Screen,
) {
    val s = size.minDimension
    val stroke = Stroke(width = s * 0.16f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (index) {
        0 -> { // 课堂：房子
            val roof = Path().apply {
                moveTo(s * 0.5f, s * 0.10f)
                lineTo(s * 0.08f, s * 0.46f)
                lineTo(s * 0.92f, s * 0.46f)
                close()
            }
            if (color != null) {
                drawPath(roof, color = color, style = stroke, blendMode = blendMode)
                drawRect(
                    color = color,
                    topLeft = Offset(s * 0.24f, s * 0.46f),
                    size = Size(s * 0.52f, s * 0.44f),
                    style = stroke, blendMode = blendMode,
                )
            } else if (brush != null) {
                drawPath(roof, brush = brush, style = stroke, blendMode = blendMode)
                drawRect(
                    brush = brush,
                    topLeft = Offset(s * 0.24f, s * 0.46f),
                    size = Size(s * 0.52f, s * 0.44f),
                    style = stroke, blendMode = blendMode,
                )
            }
        }

        1 -> { // 助教：老师指黑板（辉光版笔触细一档，细节多防糊）
            val glow = Stroke(width = s * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val shoulders = Path().apply {
                moveTo(s * 0.02f, s * 0.85f)
                cubicTo(s * 0.05f, s * 0.70f, s * 0.15f, s * 0.625f, s * 0.24f, s * 0.645f)
                cubicTo(s * 0.35f, s * 0.67f, s * 0.43f, s * 0.74f, s * 0.48f, s * 0.80f)
            }
            if (color != null) {
                drawRoundRect(
                    color = color,
                    style = glow,
                    topLeft = Offset(s * 0.36f, s * 0.05f),
                    size = Size(s * 0.59f, s * 0.55f),
                    cornerRadius = CornerRadius(s * 0.05f),
                    blendMode = blendMode,
                )
                drawLine(color = color, start = Offset(s * 0.55f, s * 0.30f), end = Offset(s * 0.83f, s * 0.30f), strokeWidth = s * 0.11f, cap = StrokeCap.Round, blendMode = blendMode)
                drawCircle(color = color, style = glow, radius = s * 0.115f, center = Offset(s * 0.23f, s * 0.44f), blendMode = blendMode)
                drawPath(shoulders, color = color, style = glow, blendMode = blendMode)
                drawLine(color = color, start = Offset(s * 0.46f, s * 0.79f), end = Offset(s * 0.585f, s * 0.565f), strokeWidth = s * 0.11f, cap = StrokeCap.Round, blendMode = blendMode)
            } else if (brush != null) {
                drawRoundRect(
                    brush = brush,
                    style = glow,
                    topLeft = Offset(s * 0.36f, s * 0.05f),
                    size = Size(s * 0.59f, s * 0.55f),
                    cornerRadius = CornerRadius(s * 0.05f),
                    blendMode = blendMode,
                )
                drawLine(brush = brush, start = Offset(s * 0.55f, s * 0.30f), end = Offset(s * 0.83f, s * 0.30f), strokeWidth = s * 0.11f, cap = StrokeCap.Round, blendMode = blendMode)
                drawCircle(brush = brush, style = glow, radius = s * 0.115f, center = Offset(s * 0.23f, s * 0.44f), blendMode = blendMode)
                drawPath(shoulders, brush = brush, style = glow, blendMode = blendMode)
                drawLine(brush = brush, start = Offset(s * 0.46f, s * 0.79f), end = Offset(s * 0.585f, s * 0.565f), strokeWidth = s * 0.11f, cap = StrokeCap.Round, blendMode = blendMode)
            }
        }

        2 -> { // 任务：清单（辉光版）
            val glow = Stroke(width = s * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val check = Path().apply {
                moveTo(s * 0.25f, s * 0.375f)
                lineTo(s * 0.335f, s * 0.46f)
                lineTo(s * 0.50f, s * 0.30f)
            }
            if (color != null) {
                drawRoundRect(
                    color = color,
                    style = glow,
                    topLeft = Offset(s * 0.10f, s * 0.10f),
                    size = Size(s * 0.80f, s * 0.80f),
                    cornerRadius = CornerRadius(s * 0.17f),
                    blendMode = blendMode,
                )
                drawPath(check, color = color, style = glow, blendMode = blendMode)
                drawLine(color = color, start = Offset(s * 0.585f, s * 0.36f), end = Offset(s * 0.755f, s * 0.36f), strokeWidth = s * 0.12f, cap = StrokeCap.Round, blendMode = blendMode)
                drawLine(color = color, start = Offset(s * 0.585f, s * 0.60f), end = Offset(s * 0.755f, s * 0.60f), strokeWidth = s * 0.12f, cap = StrokeCap.Round, blendMode = blendMode)
                drawCircle(color = color, style = glow, radius = s * 0.075f, center = Offset(s * 0.335f, s * 0.655f), blendMode = blendMode)
            } else if (brush != null) {
                drawRoundRect(
                    brush = brush,
                    style = glow,
                    topLeft = Offset(s * 0.10f, s * 0.10f),
                    size = Size(s * 0.80f, s * 0.80f),
                    cornerRadius = CornerRadius(s * 0.17f),
                    blendMode = blendMode,
                )
                drawPath(check, brush = brush, style = glow, blendMode = blendMode)
                drawLine(brush = brush, start = Offset(s * 0.585f, s * 0.36f), end = Offset(s * 0.755f, s * 0.36f), strokeWidth = s * 0.12f, cap = StrokeCap.Round, blendMode = blendMode)
                drawLine(brush = brush, start = Offset(s * 0.585f, s * 0.60f), end = Offset(s * 0.755f, s * 0.60f), strokeWidth = s * 0.12f, cap = StrokeCap.Round, blendMode = blendMode)
                drawCircle(brush = brush, style = glow, radius = s * 0.075f, center = Offset(s * 0.335f, s * 0.655f), blendMode = blendMode)
            }
        }

        3 -> { // 我的：人形
            if (color != null) {
                drawCircle(
                    color = color,
                    radius = s * 0.17f,
                    center = Offset(s * 0.5f, s * 0.36f),
                    style = stroke,
                    blendMode = blendMode
                )
                drawArc(
                    color = color,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(s * 0.22f, s * 0.50f),
                    size = Size(s * 0.56f, s * 0.52f),
                    style = stroke,
                    blendMode = blendMode
                )
            } else if (brush != null) {
                drawCircle(
                    brush = brush,
                    radius = s * 0.17f,
                    center = Offset(s * 0.5f, s * 0.36f),
                    style = stroke,
                    blendMode = blendMode
                )
                drawArc(
                    brush = brush,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(s * 0.22f, s * 0.50f),
                    size = Size(s * 0.56f, s * 0.52f),
                    style = stroke,
                    blendMode = blendMode
                )
            }
        }
    }
}
