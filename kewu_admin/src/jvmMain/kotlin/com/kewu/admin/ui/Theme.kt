package com.kewu.admin.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme as m3LightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Scaffold
import io.github.suqi8.coui.kmp.basic.SnackbarHost
import io.github.suqi8.coui.kmp.basic.SnackbarHostState
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.Colors
import io.github.suqi8.coui.kmp.theme.lightColorScheme
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner

/** 课悟品牌蓝（学生端 / 教师端 / 总控端统一）。 */
val KewuBlue = Color(0xFF1450C8)
val KewuDeepBlue = Color(0xFF0B1E4B)
val KewuGreen = Color(0xFF19E68C)
val KewuGreenDeep = Color(0xFF0B8A54)
val AdminRed = Color(0xFFD92D20)
val AdminAmber = Color(0xFFB54708)

/** 学生端同款 coui 浅色品牌色板：ColorOS 灰底白卡 + 课悟蓝主色 + 荧光绿点缀。 */
fun kewuAdminCouiColors(): Colors = lightColorScheme(
    primary = KewuBlue,
    primaryVariant = KewuBlue,
    disabledPrimary = KewuBlue.copy(alpha = 0.30f),
    disabledPrimaryButton = KewuBlue.copy(alpha = 0.30f),
    primaryContainer = KewuBlue,
    tertiaryContainer = KewuBlue.copy(alpha = 0.12f),
    onTertiaryContainer = KewuBlue,
    tertiaryContainerVariant = KewuGreen.copy(alpha = 0.12f),
    onSurfaceSecondary = Color(0xFF64748B),
    sliderKeyPointForeground = KewuBlue.copy(alpha = 0.55f),
)

/** 由 coui 色板派生 M3 语义色：旧组件继续读 MaterialTheme 时也与 coui 同屏一致。 */
private fun m3FromCoui(c: Colors) = m3LightColorScheme(
    primary = c.primary,
    onPrimary = c.onPrimary,
    primaryContainer = c.tertiaryContainer,
    onPrimaryContainer = c.onTertiaryContainer,
    secondary = KewuGreenDeep,
    secondaryContainer = KewuGreen.copy(alpha = 0.14f),
    onSecondaryContainer = KewuGreenDeep,
    background = c.surface,
    onBackground = c.onSurface,
    surface = c.surfaceContainer,
    onSurface = c.onSurface,
    surfaceVariant = Color(0xFFF5F6F7),
    onSurfaceVariant = c.onSurfaceSecondary,
    outline = c.outline,
    outlineVariant = c.dividerLine,
    error = AdminRed,
    onError = Color.White,
    errorContainer = AdminRed.copy(alpha = 0.10f),
    onErrorContainer = AdminRed,
    inverseSurface = Color(0xFF2E2F31),
    inverseOnSurface = Color.White,
)

/**
 * 当前界面的玻璃采样层（背景 + 光斑）。页面内的液态玻璃（玻璃页头 / 横幅 / 浮条）从这里取 backdrop；
 * 为 null 时（例如弹窗内）组件自动退化为 coui 白卡。
 */
val LocalAdminBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** 全局提示条（coui Snackbar）。 */
val LocalAdminSnackbar = staticCompositionLocalOf { SnackbarHostState() }

/** 总控端全局主题：COUITheme 为唯一视觉来源，M3 只做兼容映射；根挂 coui Scaffold（覆盖层宿主 + 提示条）。 */
@Composable
fun KewuAdminTheme(content: @Composable () -> Unit) {
    val colors = remember { kewuAdminCouiColors() }
    val snackbar = remember { SnackbarHostState() }
    // coui OverlayDialog / BottomSheet 内部需要 navigation-event dispatcher。
    val navEventOwner = rememberNavigationEventDispatcherOwner(parent = null)
    COUITheme(colors = colors) {
        MaterialTheme(colorScheme = m3FromCoui(colors)) {
            CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides navEventOwner,
                LocalAdminSnackbar provides snackbar,
            ) {
                Scaffold(
                    containerColor = COUITheme.colorScheme.surface,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.padding(bottom = 28.dp)) },
                ) { _ ->
                    content()
                }
            }
        }
    }
}
