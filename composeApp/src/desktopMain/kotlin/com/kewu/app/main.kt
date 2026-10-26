package com.kewu.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.suqi8.coui.kmp.theme.COUITheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import java.awt.MouseInfo
import java.awt.Toolkit

// 桌面端窗口目标尺寸（dp）：竖屏瘦长长方形——宽度收窄（接近手机端 ~411dp，略宽一点即可）、
// 高度适中（2026-10-19 用户反馈：900dp 在 1080p 屏上几乎占满整个屏幕，改 740dp 留出系统区）。
// 落地时再按屏幕实际可用高度二次收敛（见 fitWindowToScreen），避免高分屏/高缩放下
// 超出屏幕被撑满。
private val TargetWidth = 430.dp
private val TargetHeight = 740.dp

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // ★★★ Windows 原生崩溃修复（2026-10-24，hs_err_pid*.log 实锤）★★★
    // JDK 21 的 AWT 在 Windows 上有已知 bug：系统显示配置变化事件（显示器插拔、DPI/缩放变化、
    // 显卡驱动事件）触发 Win32GraphicsEnvironment.displayChanged → D3DGraphicsDevice.displayChanged
    // → Win32GraphicsConfig.getBounds，在 awt.dll 内 EXCEPTION_ACCESS_VIOLATION，整个 JVM 直接死，
    // 表现为「打开某个界面时程序闪退」。课悟桌面端渲染走 Skiko/OpenGL，java2d 的 D3D 管线
    // 根本用不到 —— 在 AWT 初始化前禁用它，彻底绕开崩溃路径。
    System.setProperty("sun.java2d.d3d", "false")
    System.setProperty("sun.java2d.ddoffscreen", "false")
    application {
    // 初始尺寸直接给定（竖屏瘦长长方形），第一帧就是竖屏；fitWindowToScreen 仅在不超屏前提下二次收敛。
    val windowState = rememberWindowState(
        size = DpSize(TargetWidth, TargetHeight),
        position = WindowPosition(Alignment.Center),
    )
    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "课悟 · Kewu",
        // 去掉 Windows 默认标题栏（右上角系统 ❌），改用自绘的液态玻璃「最小化 / 关闭」。
        decoration = androidx.compose.ui.window.WindowDecoration.Undecorated(),
        // 固定窗口尺寸：不可缩放，尺寸完全由 fitWindowToScreen 决定。
        resizable = false,
    ) {
        fitWindowToScreen(windowState)
        DesktopRoot(
            onMinimize = { windowState.isMinimized = true },
            onClose = ::exitApplication,
            // 拖动区需要 WindowScope（拿 AWT window），必须在 Window content 里构造。
            dragArea = {
                DesktopWindowDragRegion(
                    state = windowState,
                    modifier = Modifier.fillMaxSize(),
                )
            },
        )
    }
    }
}

/**
 * 窗口拖动区：`detectDragGestures` 收手势 → 用 `MouseInfo` 绝对屏幕坐标算位移 →
 * 写回 `WindowState.position`（Compose 官方受管通道）移动窗口。
 *
 * ★ 为什么不用官方 `WindowDraggableArea`：它在 Windows 走 `StandardMoveHandler`，靠
 *   `window.addMouseMotionListener(...)` 收 AWT 的 `mouseDragged`。但 Compose 的渲染层
 *   （SkikoLayer）会消费鼠标事件，Swing 机制下被 consume 的事件不再传播给祖先监听器，
 *   挂在 AWT Window 上的监听器收不到拖动事件 → 本项目里拖不动。
 * ★ 为什么不用 Compose 指针增量（dragAmount）算位置：窗口移动会反过来改变鼠标相对窗口内的
 *   坐标，下一帧增量成为负反馈 → 窗口在目标点两侧震荡（抖动）。鼠标**绝对屏幕坐标**独立于
 *   窗口移动，用它算位移没有反馈环。
 * ★ 为什么写 `state.position` 而非 `win.setLocation`：`WindowState` 是 Compose 管理窗口位置的
 *   唯一入口（文档：app 改 state → 回写窗口；用户改窗口 → 同步回 state）。绕过它直接
 *   `setLocation` 会和内部同步逻辑打架，表现为拖不动。
 */
@Composable
private fun WindowScope.DesktopWindowDragRegion(
    state: WindowState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    val density = LocalDensity.current
    Box(
        modifier = modifier.pointerInput(Unit) {
            // 拖动会话基准：按下瞬间 Compose 维护的窗口绝对 Dp 位置 + 鼠标绝对屏幕坐标(px)，各读一次。
            // ★ 用 state.position 作基准，而不是 AWT 的 win.location：两者坐标系不一定一致（系统缩放、
            //   首帧 state.size 被 fitWindowToScreen 改掉触发重定位等），用像素位置直接当 Dp 写回
            //   会在第一帧跳到错位坐标——表现为「一拖就瞬移」。state.position 与 WindowPosition.Absolute
            //   是同一受管坐标系，用它的 Dp 作基准就不会有首帧跳变。
            var startWinDp: androidx.compose.ui.unit.DpOffset? = null
            var startMouse: IntOffset? = null
            detectDragGestures(
                onDragStart = {
                    startWinDp = androidx.compose.ui.unit.DpOffset(
                        state.position.x,
                        state.position.y,
                    )
                    startMouse = MouseInfo.getPointerInfo()?.location
                        ?.let { IntOffset(it.x, it.y) }
                },
                onDragEnd = { startWinDp = null; startMouse = null },
                onDragCancel = { startWinDp = null; startMouse = null },
            ) { change, _ ->
                change.consume()
                val sw = startWinDp ?: return@detectDragGestures
                val sm = startMouse ?: return@detectDragGestures
                val now = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
                // 鼠标位移(px) → Dp，叠加到基准 Dp 位置上，写进 Compose 受管的 position。
                val dxDp = with(density) { (now.x - sm.x).toDp() }
                val dyDp = with(density) { (now.y - sm.y).toDp() }
                state.position = WindowPosition.Absolute(sw.x + dxDp, sw.y + dyDp)
            }
        },
        content = { content() },
    )
}

/**
 * 按屏幕实际可用区域收敛窗口尺寸，并锁成固定尺寸。
 *
 * ★ 为什么要二次收敛：`rememberWindowState(size = DpSize(520.dp, 900.dp))` 是按 dp 给的，
 *   在 Windows 125%/150% 显示缩放下 900dp 会被换算成远超屏幕高度的像素，窗口直接被撑满
 *   （用户反馈「高度被设成 max」）。这里拿到屏幕像素尺寸 + 当前 density，反算出不超过屏幕的
 *   dp 上限，取 `min(目标值, 上限)`。
 */
@Composable
private fun fitWindowToScreen(state: WindowState) {
    val density = LocalDensity.current
    LaunchedEffect(density) {
        val screen = Toolkit.getDefaultToolkit().screenSize
        val margin = 80
        val maxW = with(density) { (screen.width - margin).coerceAtLeast(320).toFloat().toDp() }
        val maxH = with(density) { (screen.height - margin).coerceAtLeast(400).toFloat().toDp() }
        state.size = DpSize(TargetWidth.coerceAtMost(maxW), TargetHeight.coerceAtMost(maxH))
    }
}

/**
 * 桌面根布局：App 内容整体进玻璃采样层，其上浮一条自绘标题栏（拖动区 + 最小化/关闭玻璃按钮）。
 *
 * ★ coui 硬约束：玻璃 sampler（标题栏按钮）必须位于被采样层（App 内容挂了 layerBackdrop 的 Box）
 *   **之外**，且不能被 alpha/graphicsLayer 包裹，否则首帧可能 SIGSEGV。所以这里用外层 Box 把
 *   「采样层」和「标题栏」做成兄弟节点。
 */
@Composable
private fun DesktopRoot(
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    dragArea: @Composable () -> Unit,
) {
    val backdrop = rememberGlassBackdrop()
    Box(
        Modifier
            .fillMaxSize()
            .background(COUITheme.colorScheme.surface),
    ) {
        // 被采样层：整个 App 内容。标题栏玻璃按钮透出的就是这里的内容。
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop),
        ) {
            App()
        }

        // 标题栏（采样层之外）：右上角一条可拖动窗口 + 两颗玻璃按钮。
        DesktopTitleBar(
            backdrop = backdrop,
            onMinimize = onMinimize,
            onClose = onClose,
            dragArea = dragArea,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 8.dp),
        )
    }
}

/** 自绘标题栏：顶部右侧一条可拖动窗口，右上角「最小化 / 关闭」液态玻璃按钮。 */
@Composable
private fun DesktopTitleBar(
    backdrop: LayerBackdrop,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    dragArea: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 图标颜色在 composable 作用域取一次（Canvas 的 DrawScope 内不能再调 @Composable）。
    val iconColor = COUITheme.colorScheme.onSurface
    Row(
        modifier = modifier.height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 拖动区：标题栏左侧一整段空白，按下即可拖动窗口（宽度给足，方便抓住）。
        Box(
            Modifier
                .width(260.dp)
                .fillMaxHeight(),
        ) {
            dragArea()
        }
        GlassIconButton(backdrop = backdrop, onClick = onMinimize) {
            // 最小化：一条横线
            Canvas(Modifier.size(14.dp)) {
                val y = size.height / 2f
                drawLine(
                    color = iconColor,
                    start = androidx.compose.ui.geometry.Offset(0f, y),
                    end = androidx.compose.ui.geometry.Offset(size.width, y),
                    strokeWidth = 1.6.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
        GlassIconButton(backdrop = backdrop, onClick = onClose) {
            // 关闭：两条对角线组成 ✕
            Canvas(Modifier.size(14.dp)) {
                val w = size.width
                val h = size.height
                drawLine(
                    color = iconColor,
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(w, h),
                    strokeWidth = 1.6.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = iconColor,
                    start = androidx.compose.ui.geometry.Offset(w, 0f),
                    end = androidx.compose.ui.geometry.Offset(0f, h),
                    strokeWidth = 1.6.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/** 标题栏上的圆形液态玻璃图标按钮。 */
@Composable
private fun GlassIconButton(
    backdrop: LayerBackdrop,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    KewuGlassCard(
        backdrop = backdrop,
        modifier = Modifier.size(34.dp),
        shape = RoundedCornerShape(17.dp),
        blurRadius = 10.dp,
        tintAlpha = 0.22f,
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}