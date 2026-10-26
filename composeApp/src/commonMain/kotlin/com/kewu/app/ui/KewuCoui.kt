package com.kewu.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.HapticKind
import com.kewu.app.KewuBlue
import com.kewu.app.haptic
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardColors
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.basic.Scaffold
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.SmallTopAppBar
import io.github.suqi8.coui.kmp.basic.SnackbarDuration
import io.github.suqi8.coui.kmp.basic.SnackbarHost
import io.github.suqi8.coui.kmp.basic.SnackbarHostState
import io.github.suqi8.coui.kmp.basic.Surface
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Back
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.overlay.OverlayBottomSheet
import io.github.suqi8.coui.kmp.overlay.OverlayDialog
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.Colors
import io.github.suqi8.coui.kmp.theme.lightColorScheme
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.material3.lightColorScheme as m3LightColorScheme
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner

// ───────────────────────── coui 全局主题（2026-10-23 全局 coui 化） ─────────────────────────
//
// 课悟的界面语言 = **coui 原生组件 + 项目自研液态玻璃**（KewuGlassCard / 底部导航泡泡 / 发送钮等）。
// 除液态玻璃外，按钮 / 输入框 / 开关 / 卡片 / 设置行 / 弹窗 / 底部弹层 / 提示条 / 进度 / 顶栏 / 图标
// 一律走 coui-ui / coui-preference / coui-icons 原生件，颜色与字号走 [COUITheme]。
//
// 根层挂 coui [Scaffold]：它提供 `LocalDialogStates` / `COUIPopupHost`，`OverlayDialog` /
// `OverlayBottomSheet` / `OverlayListPopup` / 下拉类 Preference 才能渲染（窗口内覆盖层，
// 不创建 Android Dialog —— 避开 coui `Window*` 系列在 CMP 1.11 下 `DialogProperties` 签名不匹配的闪退）。
// `contentWindowInsets = 0`：根 Scaffold 不吃任何内边距，状态栏 / 导航条仍由各主框架自己处理。

/** 课悟品牌色映射到 coui 浅色方案：主色 = KewuBlue，其余沿用 ColorOS 默认（灰底白卡）。 */
fun kewuCouiColors(): Colors = lightColorScheme(
    primary = KewuBlue,
    primaryVariant = KewuBlue,
    disabledPrimary = KewuBlue.copy(alpha = 0.30f),
    disabledPrimaryButton = KewuBlue.copy(alpha = 0.30f),
    primaryContainer = KewuBlue,
    tertiaryContainer = KewuBlue.copy(alpha = 0.12f),
    onTertiaryContainer = KewuBlue,
    tertiaryContainerVariant = KewuBlue.copy(alpha = 0.12f),
    sliderKeyPointForeground = KewuBlue.copy(alpha = 0.55f),
)

/**
 * 由 coui 色板派生 M3 色板：液态玻璃（[com.kewu.app.ui.liquidglass.KewuGlassCard]）与少量
 * 仍需 M3 语义色的绘制代码读的是 `MaterialTheme.colorScheme`，这里与 coui 对齐，保证同屏色调一致。
 * 新代码一律直接读 `COUITheme.colorScheme`。
 */
private fun m3FromCoui(c: Colors) = m3LightColorScheme(
    primary = c.primary,
    onPrimary = c.onPrimary,
    primaryContainer = c.tertiaryContainer,
    onPrimaryContainer = c.onTertiaryContainer,
    secondary = c.onSurfaceSecondary,
    secondaryContainer = c.secondaryContainer,
    onSecondaryContainer = c.onSecondaryVariant,
    background = c.surface,
    onBackground = c.onSurface,
    surface = c.surfaceContainer,
    onSurface = c.onSurface,
    surfaceVariant = Color(0xFFF5F6F7),
    onSurfaceVariant = c.onSurfaceSecondary,
    outline = c.outline,
    outlineVariant = c.dividerLine,
    error = c.error,
    onError = c.onError,
    errorContainer = c.errorContainer,
    onErrorContainer = c.onErrorContainer,
    inverseSurface = Color(0xFF2E2F31),
    inverseOnSurface = Color.White,
)

/** 全局提示条（coui Snackbar）。任何页面 `LocalKewuSnackbar.current.toast("…")` 即可。 */
val LocalKewuSnackbar = staticCompositionLocalOf { SnackbarHostState() }

/** 发一条短提示（coui Snackbar，自动消失）。 */
suspend fun SnackbarHostState.toast(message: String) {
    showSnackbar(message, duration = SnackbarDuration.Short)
}

/** App 根主题：COUITheme + 对齐的 M3 色板 + 根 coui Scaffold（覆盖层宿主 + 全局 Snackbar）。 */
@Composable
fun KewuTheme(content: @Composable () -> Unit) {
    val colors = remember { kewuCouiColors() }
    val snackbar = remember { SnackbarHostState() }
    // coui OverlayDialog/DialogContentLayout 内部的 NavigationBackHandler 从
    // LocalNavigationEventDispatcherOwner 取 dispatcher，取不到直接抛
    // IllegalStateException（任何 coui 弹窗一展示就闪退：结束课程确认 / 退出登录确认都中过招）。
    // 根 dispatcher 必须显式提供：Android 侧的 ViewTree 回退在弹窗的子组合里链不到
    // Activity 的 decorView。parent = null → 自建根 dispatcher（无父可链）。
    val navEventOwner = rememberNavigationEventDispatcherOwner(parent = null)
    COUITheme(colors = colors) {
        M3Theme(colorScheme = m3FromCoui(colors)) {
            CompositionLocalProvider(
                LocalKewuSnackbar provides snackbar,
                LocalNavigationEventDispatcherOwner provides navEventOwner,
            ) {
                Scaffold(
                    containerColor = COUITheme.colorScheme.surface,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    snackbarHost = {
                        // 抬到底部导航托盘之上（托盘底 24 + 栏高 66 + 留白）
                        SnackbarHost(snackbar, modifier = Modifier.padding(bottom = 104.dp))
                    },
                ) { _ -> content() }
            }
        }
    }
}

// ───────────────────────── 通用 coui 件 ─────────────────────────

/**
 * 子页顶栏：coui [SmallTopAppBar] + coui 返回图标。主框架已处理状态栏，故不再吃系统栏内边距；
 * 背景透明，露出页面底色（以及身后的液态玻璃采样层）。
 */
@Composable
fun KewuTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    actions: @Composable RowScope.() -> Unit = {},
) {
    val cs = COUITheme.colorScheme
    SmallTopAppBar(
        title = title,
        modifier = modifier,
        color = Color.Transparent,
        subtitle = subtitle,
        navigationIcon = {
            if (onBack != null) {
                IconButton(
                    onClick = { haptic(HapticKind.Tick); onBack() },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Icon(COUIIcons.Back, contentDescription = "返回", tint = cs.onSurface) }
            }
        },
        actions = actions,
        defaultWindowInsetsPadding = false,
        showDivider = false,
    )
}

/** 分组小标题（coui [SmallTitle]）。 */
@Composable
fun KewuSectionTitle(text: String, modifier: Modifier = Modifier) {
    SmallTitle(text, modifier = modifier)
}

/**
 * 设置组卡片（coui [Card]）：内部放 `ArrowPreference` / `SwitchPreference` / `BasicComponent` 等行，
 * 行与行之间无缝，形成 ColorOS 的「分组圆角卡」。
 */
@Composable
fun KewuCardGroup(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
        cornerRadius = cornerRadius,
        colors = kewuCardColors(),
        content = content,
    )
}

/** 课悟白卡配色（coui surfaceContainer 白底 + 品牌浅蓝按压 Tint）。 */
@Composable
fun kewuCardColors(color: Color = COUITheme.colorScheme.surfaceContainer): CardColors = CardColors(
    color = color,
    contentColor = COUITheme.colorScheme.onSurface,
    pressedColor = COUITheme.colorScheme.tertiaryContainer,
)

/** 状态小胶囊（coui [Surface] + coui [Text]）。 */
@Composable
fun KewuTag(text: String, color: Color, contentColor: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(50), color = color, contentColor = contentColor) {
        Text(
            text, color = contentColor, fontSize = 11.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), maxLines = 1,
        )
    }
}

/** 主操作按钮（coui [TextButton] 主色）。[danger] = 危险操作走 error 色。 */
@Composable
fun KewuPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val cs = COUITheme.colorScheme
    TextButton(
        text = text,
        onClick = { haptic(HapticKind.Light); onClick() },
        modifier = modifier,
        enabled = enabled,
        colors = if (danger) ButtonDefaults.textButtonColorsPrimary(color = cs.error, disabledColor = cs.error.copy(alpha = 0.3f))
            else ButtonDefaults.textButtonColorsPrimary(),
    )
}

/** 次操作按钮（coui [TextButton] 灰底）。 */
@Composable
fun KewuSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(
        text = text,
        onClick = { haptic(HapticKind.Tick); onClick() },
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(),
    )
}

/** 加载中占位（coui [InfiniteProgressIndicator]）。 */
@Composable
fun KewuLoading(modifier: Modifier = Modifier, text: String? = null) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        InfiniteProgressIndicator()
        if (text != null) Text(text, color = COUITheme.colorScheme.onSurfaceSecondary, fontSize = 13.sp)
    }
}

/** 空态占位（coui 白卡 + 次级文字）。 */
@Composable
fun KewuEmpty(text: String, modifier: Modifier = Modifier) {
    KewuCardGroup(modifier) {
        Box(Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(text, color = COUITheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.5.sp, textAlign = TextAlign.Center)
        }
    }
}

/**
 * 确认弹窗（coui [OverlayDialog] + [DialogButtonBar]）。由 [show] 控制显隐，
 * 关闭动画播完后回调 [onDismissFinished]（不需要可不传）。
 */
@Composable
fun KewuConfirmDialog(
    show: Boolean,
    title: String,
    summary: String? = null,
    confirmText: String = "确定",
    cancelText: String = "取消",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onDismissFinished: (() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    OverlayDialog(
        show = show,
        title = title,
        summary = summary,
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished,
    ) {
        Column {
            content?.invoke()
            DialogButtonBar(
                negative = DialogButtonBarAction(cancelText) { haptic(HapticKind.Tick); onDismiss() },
                positive = DialogButtonBarAction(confirmText) { haptic(HapticKind.Light); onConfirm() },
                hasContentAbove = content != null || summary != null,
            )
        }
    }
}

/**
 * 底部弹层：coui 官方 [OverlayBottomSheet]（窗口内覆盖层：抓手 / 滑入滑出 / 遮罩 / 返回键全是 COUI 原生行为）。
 * 保留旧 `KewuBottomSheet(show, onDismiss, title) { close -> }` 调用形态：内容里调 `close()` 触发退场，
 * 退场动画**播完**才回调 [onDismiss]，调用方据此复位状态（直接改 show 会截断退场动画）。
 */
@Composable
fun KewuBottomSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    title: String? = null,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(show) { visible = show }
    val close = { visible = false }
    OverlayBottomSheet(
        show = visible,
        title = title,
        onDismissRequest = close,
        onDismissFinished = { if (!visible) onDismiss() },
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) { content(close) }
    }
}

/** 底部弹层里的整宽确认按钮。 */
@Composable
fun SheetConfirmButton(text: String, danger: Boolean = false, onClick: () -> Unit) {
    KewuPrimaryButton(text, onClick, Modifier.fillMaxWidth(), danger = danger)
}

/** 页面内容统一外边距：coui 列表左右 12dp。 */
val KewuPagePadding = PaddingValues(horizontal = 12.dp)

/** 全屏页底色（coui 灰底），供不经过根 Scaffold 的子树使用。 */
@Composable
fun kewuPageColor(): Color = COUITheme.colorScheme.surface

/** 让子树占满并铺 coui 页面底色。 */
@Composable
fun KewuPage(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier = modifier.fillMaxSize(), color = kewuPageColor(), content = content)
}
