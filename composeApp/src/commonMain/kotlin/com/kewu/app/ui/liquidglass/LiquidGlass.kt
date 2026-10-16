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
import io.github.suqi8.coui.kmp.blur.runtimeShaderEffect

/** 创建一块用于记录「身后内容」的玻璃层，供 [LiquidGlassSurface] 采样模糊。 */
@Composable
fun rememberGlassBackdrop(): LayerBackdrop = rememberLayerBackdrop()

/**
 * AGSL「放大镜」：以玻璃中心为原点，把采样坐标向中心收 —— 于是身后内容在玻璃里**看起来被放大**，
 * 这就是液态玻璃的标志性折射感（coui 自带的 effects 只有 blur / blendColors / colorControls，
 * 没有 scale，所以只能用自定义 runtime shader 做）。
 *
 * 采样点始终落在原图内部（|d / scale| ≤ |d|），不会采到透明黑，边缘不需要特殊处理。
 */
private const val MAGNIFY_SHADER = """
uniform shader child;
uniform float2 u_center;
uniform float u_scale;

half4 main(float2 coord) {
    float2 d = coord - u_center;
    return child.eval(u_center + d / u_scale);
}
"""

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
    /** 玻璃内的放大倍率（1 = 不放大）。>1 时身后内容在玻璃里被放大，即液态玻璃的透镜感。 */
    magnify: Float = 1f,
    highlight: Highlight? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val blurPx = blurRadius.value * density.density
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(33.dp))
            // 桌面端等无 RuntimeShader 的平台：退化为半透明白底，保证可见
            .background(Color.White.copy(alpha = 0.10f))
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    blur(blurPx)
                    // 放大必须放在 blur **之后**：此时 downscaleFactor / padding 已是最终值，
                    // 才能把中心换算到「降采样后的 padded 纹理」坐标系（否则采样全落在透明黑）。
                    if (magnify > 1.001f) {
                        runtimeShaderEffect(
                            key = "KewuMagnify",
                            shaderString = MAGNIFY_SHADER,
                            uniformShaderName = "child",
                        ) {
                            val ds = downscaleFactor.toFloat().coerceAtLeast(1f)
                            setFloatUniform(
                                "u_center",
                                (padding + size.width / 2f) / ds,
                                (padding + size.height / 2f) / ds,
                            )
                            setFloatUniform("u_scale", magnify)
                        }
                    }
                    blendColors(
                        BlurColors(
                            blendColors = listOf(
                                BlendColorEntry(Color.White.copy(alpha = tintAlpha)),
                            ),
                        ),
                    )
                },
                highlight = highlight?.let { { it } },
            )
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        content = content,
    )
}
