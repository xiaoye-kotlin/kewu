package com.kewu.app.ui.liquidglass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.blur.BackdropEffectScope
import io.github.suqi8.coui.kmp.blur.blur
import io.github.suqi8.coui.kmp.blur.colorControls
import io.github.suqi8.coui.kmp.blur.drawBackdrop
import io.github.suqi8.coui.kmp.blur.highlight.BloomStroke
import io.github.suqi8.coui.kmp.blur.highlight.Highlight
import io.github.suqi8.coui.kmp.blur.highlight.LightPosition
import io.github.suqi8.coui.kmp.blur.highlight.LightSource
import io.github.suqi8.coui.kmp.blur.isRuntimeShaderSupported
import io.github.suqi8.coui.kmp.blur.runtimeShaderEffect
import io.github.suqi8.coui.kmp.blur.LayerBackdrop

/**
 * 课悟统一液态玻璃卡：**官方 coui example 范式**（`LiquidGlassNavigationBar` / `Vibrancy` / `Lens`）。
 *
 * 旧 `LiquidGlassSurface` 只有「blur + 自造 magnify + 白 tint」，被判定「视觉弱、不是 coui 标准风格」。
 * 这里对齐官方玻璃灵魂：`effects { colorControls(sat=1.5) + blur + lens() }` + `Highlight` 双峰镜面高光 +
 * `onDrawSurface` 铺 tint。
 *
 * - `colorControls(saturation = 1.5f)`：vibrancy 活力增强，玻璃里的背景鲜亮通透（非灰蒙）；
 * - `blur`：毛玻璃；
 * - `refractionLens`：圆角折射透镜（SDF，官方 `lens()` 在 coui-ui 模块，本项目仅引入 coui-blur，故本地复刻），
 *   玻璃边缘把身后内容「掰弯」，液态玻璃最标志性的折射感；
 * - `Highlight`：顶部主光 + 底部次光的双峰 Bloom 镜面水光；
 * - `onDrawSurface`：铺一层浅 tint 给玻璃实体感。
 */
@Composable
fun KewuGlassCard(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(20.dp),
    blurRadius: Dp = 22.dp,
    tintAlpha: Float = 0.28f,
    highlight: Highlight? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = !cs.background.isLight()
    val blurPx = blurRadius.value * androidx.compose.ui.platform.LocalDensity.current.density
    val refractPx = (blurRadius.value * 0.85f).dp
    val h = highlight ?: defaultKewuHighlight(isDark)
    val tint = if (isDark) Color.Black.copy(alpha = 0.30f) else Color.White.copy(alpha = tintAlpha)

    Box(
        modifier = modifier
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    colorControls(brightness = 0f, contrast = 1f, saturation = 1.5f)
                    if (blurPx > 0.5f) blur(blurPx.dp.toPx(), blurPx.dp.toPx())
                    if (refractPx.value > 0.5f && isRuntimeShaderSupported()) {
                        refractionLens(
                            refractionHeight = refractPx.toPx(),
                            refractionAmount = refractPx.toPx(),
                        )
                    }
                },
                highlight = { h },
                onDrawSurface = { drawRect(tint) },
            )
            .clip(shape),
        content = content,
    )
}

/** 官方同款双峰镜面高光（BloomStroke）：顶部 0.5/-0.3 主光 + 底部 0.8/-0.5 次光。 */
internal fun defaultKewuHighlight(isDark: Boolean): Highlight = Highlight(
    width = 1.dp,
    alpha = if (isDark) 0.9f else 0.75f,
    style = BloomStroke(
        color = Color.White,
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

/**
 * 圆角折射透镜（改编自官方 coui `component.liquid.Lens` 的 SDF 折射 shader，Apache-2.0）。
 * 在玻璃圆角区域的近边缘带，按「距边缘深度」把采样坐标沿法线方向外推，形成 iOS/ColorOS 液态玻璃
 * 那种边缘把身后内容「掰弯」的折射感。纯 `runtimeShaderEffect`（draw 期着色器），可安全随动。
 */
internal fun BackdropEffectScope.refractionLens(
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

internal fun BackdropEffectScope.roundedRectCornerRadii(): FloatArray? {
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

/** 近似深浅判定（M3 背景浅 = 亮色）。 */
internal fun Color.isLight(): Boolean {
    val lum = 0.2126f * red + 0.7152f * green + 0.0722f * blue
    return lum > 0.5f
}
