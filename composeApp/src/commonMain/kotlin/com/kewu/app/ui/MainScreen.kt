package com.kewu.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import com.kewu.app.KewuBlue
import com.kewu.app.KewuDeepBlue
import com.kewu.app.ui.liquidglass.LiquidGlassSurface
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.blur.highlight.Highlight
import kotlin.math.abs
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class NavItem(
    val index: Int,
    val label: String,
    val gradient: List<Color>,
)

private val NAV_ITEMS = listOf(
    NavItem(0, "课堂", listOf(Color(0xFF1B6EF3), Color(0xFF0B1E4B))),
    NavItem(1, "助教", listOf(Color(0xFF7B5CFF), Color(0xFF2A1A6B))),
    NavItem(2, "任务", listOf(Color(0xFF00B4A0), Color(0xFF063E3A))),
    NavItem(3, "我的", listOf(Color(0xFFFF7A59), Color(0xFF5A1E0B))),
)

@Composable
fun MainScreen() {
    var selected by remember { mutableStateOf(0) }
    var preview by remember { mutableStateOf(0) }
    var previewFloat by remember { mutableStateOf(0f) }
    val backdrop = rememberGlassBackdrop()
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.White), // 透明状态栏下的顶部底色，与白色 TopBar 连成一体
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(), // 顶部避开状态栏，内容不被状态栏遮挡
        ) {
            TopBar()
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
                    .layerBackdrop(backdrop),
            ) {
                if (f < 0.001f) {
                    TabContent(NAV_ITEMS[from])
                } else {
                    Box(Modifier.fillMaxSize().alpha(1f - f)) { TabContent(NAV_ITEMS[from]) }
                    Box(Modifier.fillMaxSize().alpha(f)) { TabContent(NAV_ITEMS[to]) }
                }
            }
        }
        AppleLiquidGlassNavBar(
            items = NAV_ITEMS,
            selected = selected,
            onSelect = {
                selected = it
                preview = it
                previewFloat = it.toFloat()
            },
            preview = preview,
            onPreview = { preview = it },
            previewFloat = previewFloat,
            onPreviewFloat = { previewFloat = it },
            backdrop = backdrop,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun TopBar() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(Color.White),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(KewuBlue, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) { Text("课", color = Color.White, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(12.dp))
            Text("课悟 · Kewu", color = KewuDeepBlue, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TabContent(item: NavItem) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(item.gradient)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(item.label, color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("内容区域 · 演示页", color = Color.White.copy(alpha = 0.82f), fontSize = 15.sp)
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
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val tabW = remember { mutableStateOf(0f) }
    val expanded = remember { mutableStateOf(false) }   // 是否已长按进入「拾取态」
    val dragging = remember { mutableStateOf(false) }   // 拾取态下是否正在滑动
    val settling = remember { mutableStateOf(false) }   // 松手吸合中：立刻由大变小，不等滑行结束
    val dragCenter = remember { mutableStateOf(0f) }    // 泡泡中心 x（跟随手指）
    val dragVel = remember { mutableStateOf(0f) }       // 拖拽速度（用于液体拉伸形变）
    val tapExpand = remember { mutableStateOf(false) }   // 点选泡泡：复刻长按放大效果，tapExpandMs 后自动还原
    val barHeight = 66.dp                              // 导航栏（托盘）高度
    val bubbleRestH = barHeight                        // 静止：泡泡高度 = 导航栏高度（只保持边界对齐）
    val bubbleActiveH = 90.dp                          // 活动（长按 / 切换中）：泡泡高度 > 导航栏
    val barBottomGap = 24.dp                           // 栏底距屏幕底
    val containerHeight = barHeight + barBottomGap * 2 // 固定容器高：栏固定不动，泡泡绕栏心伸缩
    val bubbleWFactor = 1.08f                          // 泡泡宽度占单 tab 比例（x 轴做宽；拖拽 clamp / 点选命中判定都用这个静止宽度）

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

    // 拖拽时的「液体拉伸」：随速度横向拉长、纵向压扁；弹簧低刚度 → 带惯性过冲（加速度变形感）
    val stretch by animateFloatAsState(
        targetValue = (abs(dragVel.value) * 0.045f).coerceIn(0f, 0.5f),
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 240f),
        label = "stretch",
    )
    // 长按拾取时，非聚焦 tab 淡出，聚焦 tab 强调（对齐 iOS 手感）
    val focusT by animateFloatAsState(
        targetValue = if (expanded.value) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 380f),
        label = "focusT",
    )
    // 泡泡中心：用 Animatable 手动驱动 —— 拖拽时 snapTo 跟手、松手时 animateTo 吸到最近的 tab，
    // 避免 animateFloatAsState 在拖拽中"值过期"导致松手瞬移回旧 tab。
    val centerAnim = remember { Animatable(0f) }
    var centerPositioned by remember { mutableStateOf(false) }
    LaunchedEffect(tabW.value) {
        if (tabW.value > 0f && !centerPositioned) {
            centerPositioned = true
            centerAnim.snapTo(selected * tabW.value + tabW.value / 2f)
        }
    }
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
        ) {}

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
                LiquidGlassSurface(
                    backdrop = backdrop,
                    shape = RoundedCornerShape(50),
                    blurRadius = if (expanded.value || tapExpand.value) 22.dp else 18.dp,
                    tintAlpha = if (expanded.value || tapExpand.value) 0.14f else 0.10f,
                    highlight = Highlight.GlassStrokeMiddleLight,
                ) {
                    // 真实玻璃反光：左上聚拢镜面点光 + 底部内阴影（体积感），边缘接光交给 coui Highlight
                    Canvas(Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        drawRect(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.0f), Color.Black.copy(alpha = 0.14f)),
                            ),
                            topLeft = Offset(0f, h * 0.55f),
                            size = Size(w, h * 0.45f),
                            blendMode = BlendMode.Multiply,
                        )
                        if (expanded.value || tapExpand.value) {
                            // 玻璃自身棱边的彩虹镶边（薄膜干涉，真实物理现象）：沿胶囊描一圈光谱
                            val rimW = size.height * 0.045f
                            drawRoundRect(
                                brush = spectralBrush(alpha = 0.5f),
                                topLeft = Offset(rimW, rimW),
                                size = Size(w - rimW * 2f, h - rimW * 2f),
                                cornerRadius = CornerRadius(h / 2f - rimW),
                                style = Stroke(width = rimW),
                                blendMode = BlendMode.Screen,
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
                .pointerInput(Unit) {
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
                },
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
    val baseColor = if (selected) KewuBlue else Color(0xFF49566F)
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
