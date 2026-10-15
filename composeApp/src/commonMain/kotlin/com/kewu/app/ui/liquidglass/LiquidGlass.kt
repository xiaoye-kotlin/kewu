package com.kewu.app.ui.liquidglass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.blur.BlendColorEntry
import io.github.suqi8.coui.kmp.blur.BlurColors
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.blendColors
import io.github.suqi8.coui.kmp.blur.blur
import io.github.suqi8.coui.kmp.blur.drawBackdrop
import io.github.suqi8.coui.kmp.blur.highlight.Highlight
import io.github.suqi8.coui.kmp.blur.rememberLayerBackdrop

/** 创建一块用于记录「身后内容」的玻璃层，供 [LiquidGlassSurface] 采样模糊。 */
@Composable
fun rememberGlassBackdrop(): LayerBackdrop = rememberLayerBackdrop()

/**
 * 真实液态玻璃容器：用 coui-blur 把身后内容做毛玻璃采样
 */
@Composable
fun LiquidGlassSurface(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(26.dp),
    blurRadius: Dp = 26.dp,
    tintAlpha: Float = 0.16f,
    highlight: Highlight = Highlight.GlassStrokeMiddleLight,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val blurPx = blurRadius.value * density.density
    Box(
        modifier = modifier
            // 桌面端等无 RuntimeShader 的平台：退化为半透明白底，保证可见
            .background(Color.White.copy(alpha = 0.10f))
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    blur(blurPx)
                    blendColors(
                        BlurColors(
                            blendColors = listOf(
                                BlendColorEntry(Color.White.copy(alpha = tintAlpha)),
                            ),
                        ),
                    )
                },
                highlight = { highlight },
            )
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        content = content,
    )
}
