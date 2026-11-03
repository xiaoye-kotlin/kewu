package com.kewu.admin.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardColors
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldDefaults
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.theme.COUITheme

// ───────────────────────── coui 风格基础控件 ─────────────────────────
//
// 业务页历史上写的是 M3 的 Button / OutlinedButton / TextButton / OutlinedTextField / AlertDialog。
// 这里在 `com.kewu.admin.ui` 包内提供**同名、同用法**的 coui 实现：页面去掉 material3 的 import 后
// 自动解析到本包版本，调用点不用逐个改写。按钮内容色通过 M3 LocalContentColor 下发，
// 因此页面里的 M3 Text / Icon 也会自动着色。新代码请直接用 [AdminButton]。

enum class AdminButtonStyle { Primary, Secondary, Ghost, Danger, DangerGhost }

@Composable
private fun buttonPalette(style: AdminButtonStyle, hovered: Boolean, pressed: Boolean, enabled: Boolean): Pair<Color, Color> {
    val cs = COUITheme.colorScheme
    val (bg, fg) = when (style) {
        AdminButtonStyle.Primary -> KewuBlue to Color.White
        AdminButtonStyle.Danger -> AdminRed to Color.White
        AdminButtonStyle.Secondary -> KewuBlue.copy(alpha = 0.09f) to KewuBlue
        AdminButtonStyle.Ghost -> Color.Transparent to KewuBlue
        AdminButtonStyle.DangerGhost -> Color.Transparent to AdminRed
    }
    val hoverBg = when (style) {
        AdminButtonStyle.Primary -> Color(0xFF1C5FE0)
        AdminButtonStyle.Danger -> Color(0xFFE5483B)
        AdminButtonStyle.Secondary -> KewuBlue.copy(alpha = 0.15f)
        AdminButtonStyle.Ghost -> KewuBlue.copy(alpha = 0.08f)
        AdminButtonStyle.DangerGhost -> AdminRed.copy(alpha = 0.08f)
    }
    val pressBg = when (style) {
        AdminButtonStyle.Primary -> Color(0xFF0F43AB)
        AdminButtonStyle.Danger -> Color(0xFFB8261B)
        AdminButtonStyle.Secondary -> KewuBlue.copy(alpha = 0.20f)
        AdminButtonStyle.Ghost -> KewuBlue.copy(alpha = 0.13f)
        AdminButtonStyle.DangerGhost -> AdminRed.copy(alpha = 0.13f)
    }
    if (!enabled) {
        val disabledBg = if (bg == Color.Transparent) bg else cs.onSurface.copy(alpha = 0.07f)
        return disabledBg to cs.onSurface.copy(alpha = 0.32f)
    }
    return when {
        pressed -> pressBg to fg
        hovered -> hoverBg to fg
        else -> bg to fg
    }
}

/** coui 风格按钮：悬停变色 + 按压回弹；Primary / Secondary / Ghost / Danger。 */
@Composable
fun AdminButtonBase(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: AdminButtonStyle = AdminButtonStyle.Primary,
    enabled: Boolean = true,
    compact: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = rememberAdminInteraction()
    val (bgTarget, fgTarget) = buttonPalette(style, interaction.hovered, interaction.pressed, enabled)
    val bg by animateColorAsState(bgTarget, tween(140), label = "btnBg")
    val fg by animateColorAsState(fgTarget, tween(140), label = "btnFg")
    val shape = RoundedCornerShape(if (compact) 10.dp else 12.dp)
    Row(
        modifier
            .pressScale(interaction.pressed && enabled)
            .heightIn(min = if (compact) 32.dp else 40.dp)
            .clip(shape)
            .background(bg, shape)
            .hoverable(interaction.source, enabled)
            .clickable(interactionSource = interaction.source, indication = null, enabled = enabled, onClick = onClick)
            .padding(PaddingValues(horizontal = if (compact) 10.dp else 16.dp, vertical = if (compact) 5.dp else 8.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides fg) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, color = fg)) {
                content()
            }
        }
    }
}

/** 新代码用的按钮：文字 + 可选图标 + 加载态。 */
@Composable
fun AdminButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: AdminButtonStyle = AdminButtonStyle.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    compact: Boolean = false,
) {
    AdminButtonBase(onClick, modifier, style, enabled && !loading, compact) {
        val fg = LocalContentColor.current
        if (loading) {
            InfiniteProgressIndicator(color = fg, size = 16.dp, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(if (compact) 16.dp else 18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = fg, fontSize = if (compact) 13.sp else 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** 兼容 M3 用法的主按钮（coui 风格）。 */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = AdminButtonBase(onClick, modifier, AdminButtonStyle.Primary, enabled, content = content)

/** 兼容 M3 用法的次按钮（coui 浅主色底）。 */
@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = AdminButtonBase(onClick, modifier, AdminButtonStyle.Secondary, enabled, content = content)

/** 兼容 M3 用法的文字按钮（无底色，悬停浮出浅底）。 */
@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = AdminButtonBase(onClick, modifier, AdminButtonStyle.Ghost, enabled, compact = true, content = content)

/** 兼容 M3 用法的输入框：coui TextField（Rectangle 模式）。 */
@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    singleLine: Boolean = false,
    minLines: Int = 1,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val cs = COUITheme.colorScheme
    val password = visualTransformation is PasswordVisualTransformation
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        backgroundMode = TextFieldMode.Rectangle,
        colors = TextFieldDefaults.textFieldColors(backgroundColor = cs.surface),
        label = label,
        useLabelAsPlaceholder = false,
        singleLine = singleLine,
        minLines = minLines,
        enabled = enabled,
        showClearButton = !password && singleLine,
        showPasswordToggle = password,
    )
}

/**
 * 兼容 M3 用法的弹窗：窗口内 Dialog + coui 白卡（圆角 26dp）+ 上浮淡入。
 * 弹窗内不放液态玻璃（Dialog 层不在采样层之上），所以可以安全淡入。
 */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val cs = COUITheme.colorScheme
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val p = rememberEnterProgress(0, baseDelayMs = 0L)
        Card(
            modifier = modifier.widthIn(min = 400.dp, max = 880.dp).padding(24.dp).riseEnter(p, 18f),
            cornerRadius = 26.dp,
            colors = CardColors(color = cs.surfaceContainer, contentColor = cs.onSurface, pressedColor = cs.surfaceContainer),
        ) {
            CompositionLocalProvider(LocalContentColor provides cs.onSurface) {
                Column(Modifier.padding(horizontal = 26.dp, vertical = 24.dp)) {
                    if (title != null) {
                        ProvideTextStyle(MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)) { title() }
                        Spacer(Modifier.padding(top = 14.dp))
                    }
                    if (text != null) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                            Box(Modifier.fillMaxWidth(), propagateMinConstraints = false) { text() }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 22.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}
