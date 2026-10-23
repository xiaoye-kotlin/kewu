package com.kewu.app.ui

import com.kewu.app.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.HapticKind
import com.kewu.app.haptic
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardColors
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Copy
import io.github.suqi8.coui.kmp.icon.extended.Reply
import io.github.suqi8.coui.kmp.icon.extended.Share
import io.github.suqi8.coui.kmp.icon.extended.Undo
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch

/**
 * 动作菜单（2026-10-19 重做，第三轮 12.7）：**低矮横向长条** —— 一条扁扁的 coui `Card` 里
 * 横向排开「复制 / 引用 / 分享」，每项 = 小图标(16dp) + 小字(12.5sp) 内联，整条高度 ~44dp。
 *
 * 旧版是「三个 66dp 大方块 + 文字在下方」，图标过大、体态太高不好看，已弃用。
 * 入场：居中轻微缩放 + 淡入 + 小位移（非玻璃节点，alpha/scale 安全）；点遮罩 / 返回键关闭。
 *
 * 定位（2026-10-19 第三轮 13.x）：
 * - 传 [anchorRect]（气泡在**本全屏盒坐标系**里的矩形）→ 卡片贴气泡正上方、水平居中对齐
 *   气泡中心，夹紧在屏幕内；上方高度不够自动翻到气泡下侧。用于长按聊天气泡。
 * - 不传 → 回退到按 [anchor] 的屏幕对齐（底部居中留 150dp / 顶部留 108dp）。
 *
 * 注：菜单容器改为 coui 原生 Card 后**不再需要 backdrop**（无玻璃采样），
 * 因此已移除 `backdrop`/`LayerBackdrop`/`KewuGlassCard` 依赖。
 * 2026-10-23 全局 coui 化：项内图标改 coui-icons（Copy / Reply / Share / Undo），文字改 coui Text，
 * 配色改读 [COUITheme]；定位与进出场动画不变。
 */
@Composable
fun LiquidGlassActionMenu(
    visible: Boolean,
    actions: List<GlassMenuAction>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    anchor: GlassMenuAnchor = GlassMenuAnchor.BottomCenter,
    /** 气泡锚点矩形（相对本全屏盒的坐标）：非空时卡片贴气泡正上方，忽略 [anchor] */
    anchorRect: Rect? = null,
) {
    if (!visible) return
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()

    // 进出场进度：1 → 0。外层盒做轻微缩放 + 淡入 + 位移（不再是玻璃凝结）。
    val p = remember { Animatable(1f) }
    LaunchedEffect(Unit) { p.animateTo(0f, tween(durationMillis = 160)) }
    fun dismiss() {
        scope.launch {
            p.animateTo(1f, tween(durationMillis = 140))
            onDismiss()
        }
    }
    BackHandler { dismiss() }

    // 贴气泡定位需要：全屏盒尺寸（夹紧用）+ 卡片自身尺寸（居中/翻侧用）
    var screenSize by remember { mutableStateOf(IntSize.Zero) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        Modifier.fillMaxSize().onSizeChanged { screenSize = it },
        contentAlignment = when (anchor) {
            GlassMenuAnchor.TopStart -> Alignment.TopStart
            GlassMenuAnchor.TopEnd -> Alignment.TopEnd
            GlassMenuAnchor.BottomCenter -> Alignment.BottomCenter
        },
    ) {
        // 遮罩：吸收点击关闭
        Box(
            Modifier
                .fillMaxSize()
                .alpha(0.24f * (1f - p.value))
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { dismiss() },
        )

        val enterV = 1f - p.value // 1 = 完全进场
        Box(
            modifier
                .onSizeChanged { cardSize = it }
                .then(
                    if (anchorRect != null) {
                        // 贴气泡正上方：底边距气泡顶 6dp、水平居中对齐气泡中心（夹紧屏幕内）；
                        // 上方放不下就翻到气泡下侧。入场小位移（drift）与位置合成同一个 offset。
                        Modifier.offset {
                            val drift = (p.value * 20f).dp.roundToPx()
                            if (cardSize == IntSize.Zero || screenSize == IntSize.Zero) {
                                IntOffset(0, drift)
                            } else {
                                val gap = 6.dp.roundToPx()
                                val edge = 10.dp.roundToPx()
                                val maxX = (screenSize.width - cardSize.width - edge)
                                    .coerceAtLeast(edge)
                                val x = (anchorRect.center.x - cardSize.width / 2f)
                                    .coerceIn(edge.toFloat(), maxX.toFloat()).toInt()
                                var y = (anchorRect.top - cardSize.height - gap).toInt()
                                if (y < edge) y = (anchorRect.bottom + gap).toInt()
                                IntOffset(x, y + drift)
                            }
                        }
                    } else {
                        Modifier.padding(
                            horizontal = 16.dp,
                            vertical = when (anchor) {
                                GlassMenuAnchor.BottomCenter -> 150.dp
                                else -> 108.dp
                            },
                        )
                    },
                )
                .alpha(enterV.coerceIn(0f, 1f))
                .scale(0.92f + 0.08f * enterV),
        ) {
            // coui 原生 Card 作为长条容器（仅作外观承载，点击交给内部各项）
            Card(
                modifier = Modifier,
                cornerRadius = 22.dp,
                colors = CardColors(
                    color = cs.surfaceContainer,
                    contentColor = cs.onSurface,
                    pressedColor = cs.surfaceContainer,
                ),
                onClick = { /* 容器本身不响应点击；各项在内部自行处理 */ },
            ) {
                Row(
                    Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    actions.forEach { action ->
                        MenuBarItem(
                            label = action.label,
                            glyph = action.glyph,
                            destructive = action.destructive,
                            onClick = {
                                action.onClick()
                                dismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 长条内的一项：**小图标 + 小字** 内联，矮扁的一枚胶囊。
 * 按压有自己的浅色底 + 轻震动，维持整条高度 ~44dp 的扁平比例。
 */
@Composable
private fun MenuBarItem(
    label: String,
    glyph: MenuGlyphKind,
    destructive: Boolean,
    onClick: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .height(36.dp)
            .clickable(interactionSource = interaction, indication = null) {
                haptic(HapticKind.Tick)
                onClick()
            }
            .background(
                if (pressed) {
                    if (destructive) cs.error.copy(alpha = 0.12f) else cs.tertiaryContainer
                } else {
                    Color.Transparent
                },
                RoundedCornerShape(18.dp),
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            glyph.icon(), contentDescription = null,
            tint = if (destructive) cs.error else cs.onSurfaceSecondary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            label,
            color = if (destructive) cs.error else cs.onSurface,
            fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * 菜单位置回退值：不传 anchorRect 时按屏幕对齐（底部居中 / 顶部左右）。
 * 气泡长按场景已改走 anchorRect 贴气泡，不再用这两个值。
 */
enum class GlassMenuAnchor { TopStart, TopEnd, BottomCenter }

/** 菜单项定义：标签 + 图标种类 + 是否危险项 + 点击回调。 */
class GlassMenuAction(
    val label: String,
    val glyph: MenuGlyphKind,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

enum class MenuGlyphKind { Copy, Quote, Share, Exit }

/** 菜单项图标（coui-icons）：复制 / 引用 / 分享 / 退出。 */
private fun MenuGlyphKind.icon(): ImageVector = when (this) {
    MenuGlyphKind.Copy -> COUIIcons.Copy
    MenuGlyphKind.Quote -> COUIIcons.Reply
    MenuGlyphKind.Share -> COUIIcons.Share
    MenuGlyphKind.Exit -> COUIIcons.Undo
}
