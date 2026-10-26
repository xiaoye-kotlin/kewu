package com.kewu.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.BackHandler
import com.kewu.app.HapticKind
import com.kewu.app.ImageLoader
import com.kewu.app.haptic
import com.kewu.app.openUrl
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.Image as ImageIcon
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.suqi8.coui.kmp.icon.extended.RotateLeft
import io.github.suqi8.coui.kmp.icon.extended.Share
import io.github.suqi8.coui.kmp.icon.extended.ZoomOut
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 内置图片查看器（2026-10-26）：学生 / 老师查看作业图片附件不再跳浏览器。
 *
 * 用法：任意页面 `ImageViewer.open(listOf(ViewerImage(url, name)), index)`；宿主 [ImageViewerHost]
 * 挂在 [KewuTheme] 根层，盖在主界面与底部导航之上。
 *
 * 交互：左右滑切图 / 双指缩放 / 双击放大到点击处 / 放大后拖动查看 / 单击隐藏控件 / 下拉关闭 / 旋转 / 返回键关闭。
 * 视觉：黑底大图 + 顶部、底部两条**液态玻璃**控件条（采样的是查看器自己的图片层，图片从玻璃下穿过时有折射与模糊），
 * 条内文字与按钮是 coui 原生件。
 *
 * 玻璃约束（交接文档第七节）：玻璃条与被采样的图片层平级；入场 / 退场只对玻璃条做位移，
 * 透明度变化只发生在图片层（被采样层）的 draw 阶段，玻璃祖先不套 alpha / graphicsLayer。
 */
data class ViewerImage(val source: String, val name: String = "")

object ImageViewer {
    internal data class Request(val images: List<ViewerImage>, val index: Int)

    internal val request = mutableStateOf<Request?>(null)

    fun open(images: List<ViewerImage>, index: Int = 0) {
        if (images.isEmpty()) return
        request.value = Request(images, index.coerceIn(0, images.lastIndex))
    }

    fun close() {
        request.value = null
    }
}

/** 查看器宿主：放在根层最后一个子节点（z 序最上，交接文档 16.3）。 */
@Composable
fun ImageViewerHost() {
    val req = ImageViewer.request.value
    // 退场动画期间还要继续显示上一份内容
    var shown by remember { mutableStateOf<ImageViewer.Request?>(null) }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(req) {
        if (req != null) {
            shown = req
            enter.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 420f))
        } else if (shown != null) {
            enter.animateTo(0f, tween(200, easing = FastOutSlowInEasing))
            shown = null
        }
    }
    val cur = shown ?: return
    BackHandler(enabled = req != null) { ImageViewer.close() }
    ViewerContent(cur, enterProgress = { enter.value }, closing = req == null)
}

@Composable
private fun ViewerContent(req: ImageViewer.Request, enterProgress: () -> Float, closing: Boolean) {
    val images = req.images
    val pager = rememberPagerState(initialPage = req.index) { images.size }
    val backdrop = rememberGlassBackdrop()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var chromeVisible by remember { mutableStateOf(true) }
    // 每页的缩放倍数（放大时禁止 pager 横滑，拖动交给图片平移）与旋转（1/4 圈数）
    val scales = remember { mutableStateMapOf<Int, Float>() }
    val rotations = remember { mutableStateMapOf<Int, Int>() }
    val resetTicks = remember { mutableStateMapOf<Int, Int>() }
    // 下拉关闭：位移（px）
    var dismissY by remember { mutableFloatStateOf(0f) }
    val dismissThreshold = with(density) { 120.dp.toPx() }

    // 控件条位移：隐藏 / 进出场时滑出屏幕（只动 placement，不动 alpha）
    val chromeShown = chromeVisible && !closing
    val chromeP by produceState(0f, chromeShown) {
        animate(value, if (chromeShown) 1f else 0f, animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f)) { v, _ -> value = v }
    }

    Box(Modifier.fillMaxSize()) {
        // ── 被采样层：黑底 + 图片（透明度只在 draw 阶段变化）──
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .drawBehind {
                    val fade = 1f - (abs(dismissY) / (dismissThreshold * 3f)).coerceIn(0f, 0.6f)
                    drawRect(Color.Black.copy(alpha = 0.97f * enterProgress().coerceIn(0f, 1f) * fade))
                },
        ) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = (scales[pager.currentPage] ?: 1f) <= 1.01f,
                beyondViewportPageCount = 1,
                key = { it },
            ) { page ->
                ZoomableImage(
                    image = images[page],
                    rotationQuarter = rotations[page] ?: 0,
                    resetTick = resetTicks[page] ?: 0,
                    onScale = { scales[page] = it },
                    onTap = { chromeVisible = !chromeVisible },
                    onDismissDrag = { dy -> dismissY += dy },
                    onDismissEnd = {
                        if (abs(dismissY) > dismissThreshold) {
                            ImageViewer.close()
                        } else {
                            scope.launch { animate(dismissY, 0f, animationSpec = spring(stiffness = 600f)) { v, _ -> dismissY = v } }
                        }
                    },
                    modifier = Modifier.graphicsLayer {
                        val p = enterProgress().coerceIn(0f, 1f)
                        val s = 0.9f + 0.1f * p
                        scaleX = s
                        scaleY = s
                        alpha = p
                        translationY = dismissY
                    },
                )
            }
        }

        val cur = pager.currentPage.coerceIn(0, images.lastIndex)

        // ── 顶部玻璃条：关闭 + 序号 + 文件名 ──
        KewuGlassCard(
            backdrop = backdrop,
            shape = RoundedCornerShape(24.dp),
            blurRadius = 18.dp,
            tintAlpha = 0.10f,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth()
                .offset { IntOffset(0, ((1f - chromeP) * -140f).dp.roundToPx()) },
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(COUIIcons.Close, "关闭") { ImageViewer.close() }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(
                        if (images.size > 1) "${cur + 1} / ${images.size}" else "图片",
                        color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    )
                    val name = images[cur].name
                    if (name.isNotBlank()) {
                        Text(name, color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp, maxLines = 1)
                    }
                }
                GlassIconButton(COUIIcons.Share, "用其他应用打开") {
                    openUrl(ImageLoader.resolve(images[cur].source))
                }
            }
        }

        // ── 底部玻璃条：旋转 / 还原 + 页码圆点 ──
        KewuGlassCard(
            backdrop = backdrop,
            shape = RoundedCornerShape(28.dp),
            blurRadius = 18.dp,
            tintAlpha = 0.10f,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 16.dp)
                .offset { IntOffset(0, ((1f - chromeP) * 160f).dp.roundToPx()) },
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                GlassIconButton(COUIIcons.RotateLeft, "向左旋转") {
                    rotations[cur] = ((rotations[cur] ?: 0) + 3) % 4
                    resetTicks[cur] = (resetTicks[cur] ?: 0) + 1
                }
                if (images.size in 2..9) {
                    Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        images.indices.forEach { i ->
                            Box(
                                Modifier
                                    .size(if (i == cur) 7.dp else 6.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.White.copy(alpha = if (i == cur) 0.95f else 0.38f)),
                            )
                        }
                    }
                } else if (images.size > 9) {
                    Text("${cur + 1}/${images.size}", color = Color.White, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 8.dp))
                }
                GlassIconButton(COUIIcons.ZoomOut, "还原") {
                    rotations[cur] = 0
                    resetTicks[cur] = (resetTicks[cur] ?: 0) + 1
                }
            }
        }
    }
}

@Composable
private fun GlassIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(
        onClick = { haptic(HapticKind.Tick); onClick() },
        backgroundColor = Color.White.copy(alpha = 0.14f),
        cornerRadius = 20.dp,
        minWidth = 40.dp,
        minHeight = 40.dp,
    ) {
        Icon(icon, contentDescription = desc, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

/** 单页：加载 → 适配屏幕 → 手势缩放 / 平移 / 双击 / 下拉。 */
@Composable
private fun ZoomableImage(
    image: ViewerImage,
    rotationQuarter: Int,
    resetTick: Int,
    onScale: (Float) -> Unit,
    onTap: () -> Unit,
    onDismissDrag: (Float) -> Unit,
    onDismissEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var retry by remember { mutableIntStateOf(0) }
    val loaded by produceState<LoadResult>(
        ImageLoader.cached(image.source, VIEWER_MAX_DIM)?.let { LoadResult.Ok(it) } ?: LoadResult.Loading,
        image.source, retry,
    ) {
        if (value !is LoadResult.Ok) {
            value = LoadResult.Loading
            value = ImageLoader.load(image.source, VIEWER_MAX_DIM)?.let { LoadResult.Ok(it) } ?: LoadResult.Failed
        }
    }

    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val r = loaded) {
            LoadResult.Loading -> InfiniteProgressIndicator(color = Color.White, size = 30.dp)
            LoadResult.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(COUIIcons.ImageIcon, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(10.dp))
                Text("图片加载失败", color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text("请检查网络后重试", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                TextButton(
                    text = "重新加载",
                    onClick = { haptic(HapticKind.Tick); retry++ },
                    colors = ButtonDefaults.textButtonColorsPrimary(color = Color.White.copy(alpha = 0.16f), textColor = Color.White),
                )
            }
            is LoadResult.Ok -> {
                ZoomableBitmap(
                    r.bitmap, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(),
                    rotationQuarter, resetTick, onScale, onTap, onDismissDrag, onDismissEnd,
                )
            }
        }
    }
}

@Composable
private fun ZoomableBitmap(
    bitmap: ImageBitmap,
    boxW: Float,
    boxH: Float,
    rotationQuarter: Int,
    resetTick: Int,
    onScale: (Float) -> Unit,
    onTap: () -> Unit,
    onDismissDrag: (Float) -> Unit,
    onDismissEnd: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    // 图片中心相对屏幕中心的位移（屏幕 px）
    var offset by remember { mutableStateOf(Offset.Zero) }
    val rotated = rotationQuarter % 2 == 1
    // 适配：旋转 90° 时按宽高互换后的尺寸放进屏幕
    val iw = bitmap.width.toFloat()
    val ih = bitmap.height.toFloat()
    val fit = if (rotated) min(boxW / ih, boxH / iw) else min(boxW / iw, boxH / ih)
    val drawW = iw * fit
    val drawH = ih * fit
    val visW = if (rotated) drawH else drawW
    val visH = if (rotated) drawW else drawH
    val center = Offset(boxW / 2f, boxH / 2f)

    fun clamp(o: Offset, s: Float): Offset {
        val mx = max(0f, (visW * s - boxW) / 2f)
        val my = max(0f, (visH * s - boxH) / 2f)
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    fun setScale(s: Float) {
        scale = s
        onScale(s)
    }

    LaunchedEffect(resetTick) {
        if (resetTick > 0) {
            setScale(1f)
            offset = Offset.Zero
        }
    }

    fun animateTo(targetScale: Float, targetOffset: Offset) {
        val s0 = scale
        val o0 = offset
        scope.launch {
            animate(0f, 1f, animationSpec = spring(dampingRatio = 0.9f, stiffness = 520f)) { t, _ ->
                setScale(s0 + (targetScale - s0) * t)
                offset = o0 + (targetOffset - o0) * t
            }
        }
    }

    // 手势挂在全屏容器上（屏幕坐标，不受图片缩放 / 旋转影响），图片外的黑边也能单击 / 双击 / 下拉
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(bitmap, boxW, boxH, rotationQuarter) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        haptic(HapticKind.Tick)
                        if (scale > 1.05f) {
                            animateTo(1f, Offset.Zero)
                        } else {
                            // 以点击处为中心放大：点击点下的图片内容保持不动
                            val target = 2.6f
                            val c = tap - center
                            animateTo(target, clamp(c - (c - offset) * (target / scale), target))
                        }
                    },
                )
            }
            .pointerInput(bitmap, boxW, boxH, rotationQuarter) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var decided = false
                    var dismissing = false
                    var drag = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            // 以双指中点为缩放中心：中点下的图片内容跟着手指走
                            val c = event.calculateCentroid() - center
                            val k = newScale / scale
                            offset = clamp(c + (offset - c) * k + pan, newScale)
                            setScale(newScale)
                            event.changes.forEach { it.consume() }
                            decided = true
                        } else if (pressed.size == 1) {
                            val ch = pressed.first()
                            val delta = ch.positionChange()
                            if (scale > 1.01f) {
                                offset = clamp(offset + delta, scale)
                                ch.consume()
                            } else {
                                if (!decided) {
                                    drag += delta
                                    if (drag.getDistance() > slop) {
                                        decided = true
                                        dismissing = abs(drag.y) > abs(drag.x) * 1.2f
                                    }
                                }
                                // 横向交给 pager 切图；纵向是下拉关闭
                                if (dismissing) {
                                    onDismissDrag(delta.y)
                                    ch.consume()
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (dismissing) onDismissEnd()
                    if (scale < 1.02f && scale != 1f) {
                        setScale(1f)
                        offset = Offset.Zero
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val d = LocalDensity.current
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(with(d) { drawW.toDp() }, with(d) { drawH.toDp() })
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                    rotationZ = rotationQuarter * -90f
                },
        )
    }
}

private const val VIEWER_MAX_DIM = 2400

internal sealed interface LoadResult {
    data object Loading : LoadResult
    data object Failed : LoadResult
    data class Ok(val bitmap: ImageBitmap) : LoadResult
}

/**
 * 附件缩略图：圆角裁切 + 加载占位（coui 次级底色）+ 失败图标。点击打开查看器由调用方处理。
 * [source] 为 http 链接或本机文件路径。
 */
@Composable
fun RemoteImage(
    source: String,
    modifier: Modifier = Modifier,
    maxDim: Int = 480,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val cs = COUITheme.colorScheme
    val loaded by produceState<LoadResult>(
        ImageLoader.cached(source, maxDim)?.let { LoadResult.Ok(it) } ?: LoadResult.Loading,
        source,
    ) {
        if (value !is LoadResult.Ok) {
            value = ImageLoader.load(source, maxDim)?.let { LoadResult.Ok(it) } ?: LoadResult.Failed
        }
    }
    Box(modifier.background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
        when (val r = loaded) {
            LoadResult.Loading -> InfiniteProgressIndicator(size = 18.dp)
            LoadResult.Failed -> Icon(COUIIcons.ImageIcon, contentDescription = "加载失败", tint = cs.onSurfaceVariantSummary,
                modifier = Modifier.size(22.dp))
            is LoadResult.Ok -> Image(r.bitmap, contentDescription = null, contentScale = contentScale,
                modifier = Modifier.fillMaxSize())
        }
    }
}
