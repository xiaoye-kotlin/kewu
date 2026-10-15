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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import kotlinx.coroutines.CoroutineScope
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
    val dragCenter = remember { mutableStateOf(0f) }    // 泡泡中心 x（跟随手指）
    val dragVel = remember { mutableStateOf(0f) }       // 拖拽速度（用于液体拉伸形变）
    val barHeight = 66.dp                              // 导航栏（托盘）高度
    val bubbleRestH = barHeight                        // 静止：泡泡高度 = 导航栏高度（只保持边界对齐）
    val bubbleActiveH = 90.dp                          // 活动（长按 / 切换中）：泡泡高度 > 导航栏
    val barBottomGap = 24.dp                           // 栏底距屏幕底
    val containerHeight = barHeight + barBottomGap * 2 // 固定容器高：栏固定不动，泡泡绕栏心伸缩
    val bubbleWFactor = 1.08f                          // 泡泡宽度占单 tab 比例（x 轴做宽）

    // 泡泡宽度 = 单 tab 的 bubbleWFactor
    val indicatorW by animateFloatAsState(
        targetValue = tabW.value * bubbleWFactor,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 380f),
        label = "indicatorW",
    )
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

    // 活动态判定：长按拾取 / 拖拽中 / 正在切换（中心尚未到位）。
    // 静止时 height=barHeight（宽扁胶囊，边界贴栏）；活动时 height>bubbleActiveH（弹高）。
    val targetCenter = selected * tabW.value + tabW.value / 2f
    val active = expanded.value || dragging.value || abs(centerAnim.value - targetCenter) > 2f
    // 活动时轻微整体放大（q 弹过冲）；配合下面的 height 动画 → 长按/切换时"变大变高"
    val bubbleScale by animateFloatAsState(
        targetValue = if (active) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.34f, stiffness = 320f),
        label = "bubbleScale",
    )
    // 泡泡高度：静止=栏高，活动=高于栏；低阻尼弹簧带来回弹
    val bubbleHeight by animateDpAsState(
        targetValue = if (active) bubbleActiveH else bubbleRestH,
        animationSpec = spring(dampingRatio = 0.58f, stiffness = 360f),
        label = "bubbleHeight",
    )

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .height(containerHeight),
    ) {
        // 1) 底部玻璃胶囊托盘（淡，提供 bar 质感；全圆角无直边）
        LiquidGlassSurface(
            backdrop = backdrop,
            shape = RoundedCornerShape(33.dp),
            blurRadius = 22.dp,
            tintAlpha = 0.12f,
            modifier = Modifier.fillMaxWidth().height(barHeight).align(Alignment.Center),
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
            // 柔和阴影：径向渐变自然淡出（替代生硬 Material drop shadow）
            Canvas(Modifier.fillMaxSize()) {
                val r = if (size.width > size.height) size.width else size.height
                drawOval(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.30f), Color.Black.copy(alpha = 0.0f)),
                        center = Offset(size.width / 2f, size.height * 0.62f),
                        radius = r * 0.6f,
                    ),
                    topLeft = Offset.Zero,
                    size = size,
                )
            }
            // 玻璃泡泡本体（单独缩放；叠加液体拉伸形变）
            Box(
                Modifier
                    .width(indicatorWdp)
                    .height(bubbleHeight)
                    .scale(bubbleScale * (1f + stretch), bubbleScale * (1f - stretch * 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                LiquidGlassSurface(
                    backdrop = backdrop,
                    shape = RoundedCornerShape(50),
                    blurRadius = if (expanded.value) 22.dp else 18.dp,
                    tintAlpha = if (expanded.value) 0.14f else 0.10f,
                    highlight = Highlight.GlassStrokeMiddleLight,
                ) {
                    // 真实玻璃反光：左上聚拢镜面点光 + 底部内阴影（体积感），边缘接光交给 coui Highlight
                    Canvas(Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(Color.White.copy(alpha = 0.50f), Color.White.copy(alpha = 0.0f)),
                                center = Offset(w * 0.28f, h * 0.24f),
                                radius = h * 0.34f,
                            ),
                            radius = h * 0.34f,
                            center = Offset(w * 0.28f, h * 0.24f),
                            blendMode = BlendMode.Screen,
                        )
                        drawRect(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.0f), Color.Black.copy(alpha = 0.14f)),
                            ),
                            topLeft = Offset(0f, h * 0.55f),
                            size = Size(w, h * 0.45f),
                            blendMode = BlendMode.Multiply,
                        )
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
                        dragCenter = dragCenter,
                        dragVel = dragVel,
                        bubbleWFactor = bubbleWFactor,
                        centerAnim = centerAnim,
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
            NavTab(item, i == preview, dimT = if (i == preview) 0f else focusT, rim = rim)
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
    dragCenter: MutableState<Float>,
    dragVel: MutableState<Float>,
    bubbleWFactor: Float,
    centerAnim: Animatable<Float, AnimationVector1D>,
    onSelect: (Int) -> Unit,
    onPreview: (Int) -> Unit,
    onPreviewFloat: (Float) -> Unit,
) {
    val longPressMs = 120L   // 长按判定：稍按一会即进入"拾取/跟手"态
    val moveCancelPx = 12f
    awaitPointerEventScope {
        while (true) {
            val down = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: continue
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
                // 松手：算离哪边更近 → 被"吸"过去。先 snap 中心到松手点、再切 dragging=false（位置不变，无跳变），
                // 最后 animateTo 吸合。expanded 放 finally 里清：快速连滑/紧接着点击时，本次吸合会被下一次
                // animateTo 取消，若不用 finally，expanded 会永远停在 true → 泡泡卡在变大无法变小。
                val ni = tabIndexFromX(dragCenter.value, tabW.value, itemCount)
                scope.launch {
                    centerAnim.snapTo(dragCenter.value)
                    dragging.value = false
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
    return (x / tabW).coerceIn(0f, (itemCount - 1).toFloat())
}

@Composable
private fun NavTab(item: NavItem, selected: Boolean, dimT: Float = 0f, rim: Float = 0f) {
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
            .alpha(dimAlpha)
            .scale(dimScale),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.size(24.dp).scale(scale)) {
            val sz = size
            drawGlyph(item.index, baseColor, sz)
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
        Spacer(Modifier.height(3.dp))
        Box(contentAlignment = Alignment.Center) {
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
                        fontSize = 11.sp,
                        fontWeight = w,
                        modifier = Modifier.offset(x = dx, y = 0.dp),
                    )
                }
            }
            Text(item.label, color = baseColor, fontSize = 11.sp, fontWeight = w)
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
        1 -> { // 助教：放大镜
            drawCircle(color, style = stroke, radius = s * 0.30f, center = Offset(s * 0.40f, s * 0.40f))
            drawLine(
                color,
                Offset(s * 0.61f, s * 0.61f),
                Offset(s * 0.90f, s * 0.90f),
                strokeWidth = s * 0.085f,
                cap = StrokeCap.Round,
            )
        }
        2 -> { // 任务：勾选圆圈
            drawCircle(color, style = stroke, radius = s * 0.38f, center = Offset(s * 0.5f, s * 0.5f))
            val check = Path().apply {
                moveTo(s * 0.34f, s * 0.52f)
                lineTo(s * 0.46f, s * 0.64f)
                lineTo(s * 0.68f, s * 0.36f)
            }
            drawPath(
                check,
                color,
                style = Stroke(width = s * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
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
        1 -> { // 助教：放大镜
            if (color != null) {
                drawCircle(color = color, style = stroke, radius = s * 0.30f, center = Offset(s * 0.40f, s * 0.40f), blendMode = blendMode)
                drawLine(color = color, start = Offset(s * 0.61f, s * 0.61f), end = Offset(s * 0.90f, s * 0.90f), strokeWidth = s * 0.085f, cap = StrokeCap.Round, blendMode = blendMode)
            } else if (brush != null) {
                drawCircle(brush = brush, style = stroke, radius = s * 0.30f, center = Offset(s * 0.40f, s * 0.40f), blendMode = blendMode)
                drawLine(brush = brush, start = Offset(s * 0.61f, s * 0.61f), end = Offset(s * 0.90f, s * 0.90f), strokeWidth = s * 0.085f, cap = StrokeCap.Round, blendMode = blendMode)
            }
        }
        2 -> { // 任务：勾选圆圈
            val check = Path().apply {
                moveTo(s * 0.34f, s * 0.52f)
                lineTo(s * 0.46f, s * 0.64f)
                lineTo(s * 0.68f, s * 0.36f)
            }
            if (color != null) {
                drawCircle(color = color, style = stroke, radius = s * 0.38f, center = Offset(s * 0.5f, s * 0.5f), blendMode = blendMode)
                drawPath(check, color = color, style = Stroke(width = s * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = blendMode)
            } else if (brush != null) {
                drawCircle(brush = brush, style = stroke, radius = s * 0.38f, center = Offset(s * 0.5f, s * 0.5f), blendMode = blendMode)
                drawPath(check, brush = brush, style = Stroke(width = s * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = blendMode)
            }
        }
        3 -> { // 我的：人形
            if (color != null) {
                drawCircle(color = color, radius = s * 0.17f, center = Offset(s * 0.5f, s * 0.36f), style = stroke, blendMode = blendMode)
                drawArc(color = color, startAngle = 180f, sweepAngle = 180f, useCenter = false, topLeft = Offset(s * 0.22f, s * 0.50f), size = Size(s * 0.56f, s * 0.52f), style = stroke, blendMode = blendMode)
            } else if (brush != null) {
                drawCircle(brush = brush, radius = s * 0.17f, center = Offset(s * 0.5f, s * 0.36f), style = stroke, blendMode = blendMode)
                drawArc(brush = brush, startAngle = 180f, sweepAngle = 180f, useCenter = false, topLeft = Offset(s * 0.22f, s * 0.50f), size = Size(s * 0.56f, s * 0.52f), style = stroke, blendMode = blendMode)
            }
        }
    }
}
