package com.kewu.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import com.kewu.app.KewuGreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 自定义底部弹层（2026-10-19 新增，替代 coui `WindowBottomSheet`）。
 *
 * ★ 为什么不用 coui 的 BottomSheet：coui 1.1.0 的 `WindowBottomSheet` / `OverlayBottomSheet`
 * 在本项目 CMP 1.11.2 下不可用 —— 前者创建真实 Android `Dialog` 调 `platformDialogProperties()`，
 * 其按旧签名调用 `DialogProperties`（含 IBinder/String 重载），与本项目 `DialogProperties`
 * 的 `(3 boolean + 1 int)` 构造签名不匹配，一点击即 `NoSuchMethodError` 闪退；后者依赖 coui
 * `Scaffold` 的 Dialog 状态，本 app 没用 coui Scaffold。
 *
 * 这里用标准 `androidx.compose.ui.window.Dialog`（本项目版本可用）+ coui 视觉语言自写：
 * 拖拽手柄 + 顶部大圆角 + 遮罩 + 上滑入场 / 下滑退场 + 返回键 dismiss + 点遮罩关闭。
 *
 * ⚠️ Dialog 渲染在独立窗口，采不到主窗口的 glass backdrop，故弹层内**不复用 KewuGlassCard**，
 * 用纯 M3 表面 + 描边，规避 coui 玻璃采样首帧 SIGSEGV。
 *
 * @param show 由调用方控制显隐；内部自行处理「退场动画播完才真正卸载 + 回调 onDismiss」。
 * @param onDismiss 弹层**完全关闭后**回调（退场动画结束的那一帧），调用方据此复位显隐状态。
 * @param content 内容区；传入 `close: () -> Unit`，内容内点选（如选中项/确认）后调用它触发退场。
 */
@Composable
fun KewuBottomSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    title: String? = null,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var mounted by remember { mutableStateOf(show) }
    var closing by remember { mutableStateOf(false) }

    // show 上升沿：挂载并复位 closing（进入动画从 closing=false 开始）
    LaunchedEffect(show) {
        if (show) {
            closing = false
            mounted = true
        } else if (mounted) {
            // show 下降沿：外部直接要求关闭时（理论上都走 close() 内部触发，这里兜底）
            closing = true
        }
    }

    if (mounted) {
        Dialog(
            onDismissRequest = { closing = true },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            // 入场/退场动画值。★ 本版本 animateFloatAsState 无 initialValue 参数，
            // 而弹层必须「首次挂载时在屏幕下方、随后滑上来」，故用 Animatable 显式持有初值。
            val slideAnim = remember { Animatable(1f) } // 1 = 完全在屏幕下方
            LaunchedEffect(closing) {
                slideAnim.animateTo(
                    targetValue = if (closing) 1f else 0f,
                    animationSpec = spring(dampingRatio = 0.62f, stiffness = 380f),
                )
                // ★ 唯一卸载点：退场动画播完才真正卸载并回调，避免 onDismiss 被触发两次
                if (closing && mounted) {
                    mounted = false
                    onDismiss()
                }
            }
            val scrimAnim = remember { Animatable(0f) }
            LaunchedEffect(closing) {
                scrimAnim.animateTo(
                    targetValue = if (closing) 0f else 0.42f,
                    animationSpec = tween(durationMillis = 200),
                )
            }
            val slide = slideAnim.value
            val scrim = scrimAnim.value

            Box(Modifier.fillMaxSize()) {
                // ★ 遮罩**单独一层**（2026-10-19 修透明度）：旧版把 .alpha(scrim) 加在外层 Box 上，
                //   alpha 会作用到整棵子树 —— 面板也被一起调成半透明。现在遮罩与面板是兄弟节点，
                //   面板背景 cs.surface 保持完全不透明。
                Box(
                    Modifier
                        .fillMaxSize()
                        .alpha(scrim.coerceIn(0f, 1f))
                        .background(Color.Black)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { closing = true },
                )
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .absoluteOffset(y = (slide * 1200f).dp)
                        .background(cs.surface, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        // 内容区吃掉点击，避免穿透到遮罩导致误关
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {}
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    // 拖拽手柄（居中胶囊）
                    Box(Modifier.fillMaxWidth().padding(bottom = 10.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(40.dp, 4.dp)
                                .background(cs.onSurfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(2.dp)),
                        )
                    }
                    if (title != null) {
                        androidx.compose.material3.Text(
                            title,
                            color = cs.onSurface,
                            fontSize = 17.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                    content { closing = true }
                }
            }
        }
    }
}

/**
 * 底部弹层内的「主操作按钮」：全宽圆角实心钮（品牌色 / 危险红），按压轻微缩放反馈。
 * 用于退出课程等确认场景。
 */
@Composable
fun ColumnScope.SheetConfirmButton(
    text: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val pressed = remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed.value) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 480f, visibilityThreshold = 0.0005f),
        label = "sheetBtn",
    )
    val bg = if (danger) cs.error else KewuGreen
    val fg = if (danger) cs.onError else Color.White
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .scale(scale)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                pressed.value = true
                onClick()
            }
            .background(bg, RoundedCornerShape(14.dp))
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(text, color = fg, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}
