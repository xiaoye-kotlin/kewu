package com.kewu.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.kewu.app.FocusVisualConfigInfo
import kewu.composeapp.generated.resources.Res
import kewu.composeapp.generated.resources.focus_bg_1
import kewu.composeapp.generated.resources.focus_bg_2
import kewu.composeapp.generated.resources.focus_bg_3
import kewu.composeapp.generated.resources.focus_bg_4
import kewu.composeapp.generated.resources.focus_bg_5
import kewu.composeapp.generated.resources.focus_bg_6
import kewu.composeapp.generated.resources.focus_noise
import kewu.composeapp.generated.resources.focus_nature
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import kotlin.random.Random

/**
 * 番茄钟专注背景（2026-11-16）。
 *
 * 两种模式：
 *  - [calmRandom]=true（沉浸专注页用）：从 6 张「低信息密度 / 低对比 / 低饱和 / 静态 / 无文字」的柔和图里
 *    **随机**选一张铺满，不叠暗化、不叠噪点、不加 blur —— 尽量不抢注意力。随机结果用 remember 固定，
 *    同一次专注期间不闪变；下次进入才会换一张。
 *  - [calmRandom]=false：按 AI 的 [config.bgStyle] 三态渲染（DARK_BLUE_SOLID / BLURRED_NATURE / DARK_GRADIENT），
 *    叠暗化遮罩 + 可选噪点。
 *
 * 视觉层级严格从底到顶（Compose 先声明的在下、后声明的盖在上面）：
 *  1) 底层背景 → 2) 暗化遮罩（随机模式跳过）→ 3) 噪点层（随机模式跳过）→ 4) 内容层 [content]。
 *
 * 避坑：
 *  - 性能：blur 只在 AI 的 BLURRED_NATURE 分支加在图片上；随机模式与纯色/渐变分支完全不碰 Modifier.blur。
 *  - 内存：所有背景图都是仓库内降采样小图（随机图 8-15KB、自然图 66KB、噪点 33KB），painterResource
 *    直接解码这一份，不会把大图解进内存。
 *  - 顺序：背景用 matchParentSize 铺底，content 最后声明 → 永远在最上层。
 *
 * @param config 后端 ai-suggestion 的 visual_config（非随机模式用）
 * @param calmRandom 是否用随机柔和背景（沉浸专注页传 true）
 */
@Composable
fun FocusBackground(
    config: FocusVisualConfigInfo,
    modifier: Modifier = Modifier,
    enableNoise: Boolean = true,
    calmRandom: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (calmRandom) {
            // ── 随机低干扰背景：remember 固定一次，专注期间不闪；无暗化/无噪点/无 blur ──
            val pool: List<DrawableResource> = remember {
                listOf(
                    Res.drawable.focus_bg_1, Res.drawable.focus_bg_2, Res.drawable.focus_bg_3,
                    Res.drawable.focus_bg_4, Res.drawable.focus_bg_5, Res.drawable.focus_bg_6,
                )
            }
            val picked: DrawableResource = remember { pool[Random.nextInt(pool.size)] }
            Image(
                painter = painterResource(picked),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            val blurRadius = config.blurRadius.coerceIn(0, 20)
            val darkness = config.darknessOverlay.coerceIn(10, 30)

            // ── 1) 底层背景：三态互斥，每态独立分支，绝不叠加多余 modifier ──
            when (config.bgStyle) {
                "BLURRED_NATURE" -> Image(
                    painter = painterResource(Res.drawable.focus_nature),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().blur(blurRadius.dp),
                )
                "DARK_GRADIENT" -> Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF000000))
                        )
                    )
                )
                else -> Box(Modifier.matchParentSize().background(Color(0xFF1E293B)))
            }

            // ── 2) 暗化遮罩 ──
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = darkness / 100f)))

            // ── 3) 噪点层（可选）──
            if (enableNoise) {
                Image(
                    painter = painterResource(Res.drawable.focus_noise),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }

        // ── 4) 内容层：最后声明 → 永远盖在背景之上 ──
        content()
    }
}
