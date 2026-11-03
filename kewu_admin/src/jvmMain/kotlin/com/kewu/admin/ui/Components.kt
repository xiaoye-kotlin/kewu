package com.kewu.admin.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardColors
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.basic.Surface
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldDefaults
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * 页面头部：液态玻璃条（有采样层时），凝结入场（只位移 + blur/tint 插值，内部文字淡入）。
 * 无采样层时退化为普通标题行。
 */
@Composable
fun PageHeader(
    title: String,
    subtitle: String,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val cs = COUITheme.colorScheme
    val backdrop = LocalAdminBackdrop.current
    val p = rememberEnterProgress(0, baseDelayMs = 0L)
    val inner: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).alpha(p.coerceIn(0f, 1f))) {
                Text(title, color = cs.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = cs.onSurfaceSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
    if (backdrop != null) {
        AdminGlassCard(
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp).glassEnter(p, 18f),
            shape = RoundedCornerShape(24.dp),
            blurRadius = glassCondenseBlur(p, 24f),
            tintAlpha = 0.42f * p.coerceIn(0f, 1f),
        ) { inner() }
    } else {
        Box(Modifier.fillMaxWidth().padding(bottom = 18.dp)) { inner() }
    }
}

/** 液态玻璃横幅 / 提示条（页面内用；无采样层时退化为浅色卡）。祖先只能有位移动画。 */
@Composable
fun GlassStrip(
    modifier: Modifier = Modifier,
    index: Int = 1,
    tint: Color = Color.Transparent,
    cornerRadius: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val backdrop = LocalAdminBackdrop.current
    val p = rememberEnterProgress(index, baseDelayMs = 30L)
    val shape = RoundedCornerShape(cornerRadius)
    if (backdrop != null) {
        AdminGlassCard(
            backdrop = backdrop,
            modifier = modifier.glassEnter(p, 20f),
            shape = shape,
            blurRadius = glassCondenseBlur(p, 22f),
            tintAlpha = 0.40f * p.coerceIn(0f, 1f),
        ) {
            Column(Modifier.fillMaxWidth().background(tint).alpha(p.coerceIn(0f, 1f)), content = content)
        }
    } else {
        Column(modifier.clip(shape).background(COUITheme.colorScheme.surfaceContainer).background(tint), content = content)
    }
}

/** coui 原生 squircle 白卡 + 错峰上浮淡入。卡内不能放液态玻璃（会被 alpha 包裹）。 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    index: Int = 1,
    enter: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = COUITheme.colorScheme
    val p = if (enter) rememberEnterProgress(index) else 1f
    Card(
        modifier = if (enter) modifier.riseEnter(p) else modifier,
        cornerRadius = 20.dp,
        colors = CardColors(
            color = cs.surfaceContainer,
            contentColor = cs.onSurface,
            pressedColor = cs.tertiaryContainer,
        ),
        content = content,
    )
}

@Composable
fun MetricCard(
    title: String,
    value: String,
    detail: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accent: Color = KewuBlue,
) {
    val cs = COUITheme.colorScheme
    val interaction = rememberAdminInteraction()
    val iconBg by animateColorAsState(accent.copy(alpha = if (interaction.hovered) 0.18f else 0.11f), tween(160), label = "metricIcon")
    Panel(modifier.hoverable(interaction.source)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).pressScale(interaction.hovered, 1.08f).background(iconBg, RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, color = cs.onSurfaceSecondary, fontSize = 12.sp)
                Text(value, color = cs.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(detail, color = cs.onSurfaceSecondary, fontSize = 11.sp)
            }
        }
    }
}

enum class StatusTone { SUCCESS, WARNING, ERROR, INFO, NEUTRAL }

@Composable
fun toneColor(tone: StatusTone): Color = when (tone) {
    StatusTone.SUCCESS -> KewuGreenDeep
    StatusTone.WARNING -> AdminAmber
    StatusTone.ERROR -> AdminRed
    StatusTone.INFO -> KewuBlue
    StatusTone.NEUTRAL -> COUITheme.colorScheme.onSurfaceSecondary
}

@Composable
fun StatusPill(text: String, tone: StatusTone = StatusTone.NEUTRAL) {
    val color = toneColor(tone)
    Surface(
        color = color.copy(alpha = 0.10f),
        contentColor = color,
        shape = RoundedCornerShape(50),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(color, RoundedCornerShape(50)))
            Spacer(Modifier.width(6.dp))
            Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

data class TableColumn(val title: String, val weight: Float = 1f)

@Composable
fun <T> DataTable(
    items: List<T>,
    columns: List<TableColumn>,
    modifier: Modifier = Modifier,
    key: ((T) -> Any)? = null,
    onRowClick: ((T) -> Unit)? = null,
    row: @Composable (T, TableColumn) -> Unit,
) {
    val cs = COUITheme.colorScheme
    Panel(modifier) {
        Row(Modifier.fillMaxWidth().background(cs.surface.copy(alpha = 0.7f)).padding(horizontal = 18.dp, vertical = 13.dp)) {
            columns.forEach { column ->
                Box(Modifier.weight(column.weight)) {
                    Text(column.title, color = cs.onSurfaceSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        HorizontalDivider(color = cs.dividerLine)
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = key) { item ->
                val interaction = rememberAdminInteraction()
                val rowBg by animateColorAsState(
                    when {
                        interaction.pressed -> KewuBlue.copy(alpha = 0.09f)
                        interaction.hovered -> KewuBlue.copy(alpha = 0.045f)
                        else -> Color.Transparent
                    },
                    tween(120),
                    label = "rowBg",
                )
                Row(
                    Modifier.fillMaxWidth()
                        .background(rowBg)
                        .hoverable(interaction.source)
                        .then(
                            if (onRowClick != null) {
                                Modifier.clickable(interactionSource = interaction.source, indication = null) { onRowClick(item) }
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 18.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    columns.forEach { column -> Box(Modifier.weight(column.weight)) { row(item, column) } }
                }
                HorizontalDivider(color = cs.dividerLine.copy(alpha = 0.65f))
            }
        }
    }
}

@Composable
fun TableText(text: String, bold: Boolean = false, color: Color = COUITheme.colorScheme.onSurface) {
    Text(
        text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        color = color,
        fontSize = 14.sp,
        modifier = Modifier.padding(end = 10.dp),
    )
}

@Composable
fun LoadingBlock(message: String = "正在加载数据…") {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        InfiniteProgressIndicator(size = 30.dp)
        Text(message, color = COUITheme.colorScheme.onSurfaceSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
fun EmptyBlock(title: String = "暂无数据", detail: String = "当前筛选条件下没有记录") {
    val cs = COUITheme.colorScheme
    val p = rememberEnterProgress(1)
    Column(
        Modifier.fillMaxSize().padding(30.dp).riseEnter(p),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Inbox, null, modifier = Modifier.size(42.dp), tint = cs.onSurfaceSecondary)
        Text(title, color = cs.onSurface, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Text(detail, color = cs.onSurfaceSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
fun ErrorBlock(message: String, onRetry: () -> Unit) {
    val cs = COUITheme.colorScheme
    val p = rememberEnterProgress(1)
    Column(
        Modifier.fillMaxSize().padding(30.dp).riseEnter(p),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("数据加载失败", color = AdminRed, fontWeight = FontWeight.SemiBold)
        Text(message, color = cs.onSurfaceSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        AdminButton("重新加载", onRetry, Modifier.padding(top = 16.dp), style = AdminButtonStyle.Secondary)
    }
}

@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val cs = COUITheme.colorScheme
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.height(52.dp),
        backgroundMode = TextFieldMode.Rectangle,
        colors = TextFieldDefaults.textFieldColors(backgroundColor = cs.surfaceContainer),
        label = placeholder,
        useLabelAsPlaceholder = true,
        singleLine = true,
    )
}

/** 下拉筛选：coui 风格触发块（悬停浮色）+ 弹出菜单。 */
@Composable
fun OptionSelector(
    value: String,
    options: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    val cs = COUITheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val interaction = rememberAdminInteraction()
    val bg by animateColorAsState(
        if (interaction.hovered || expanded) KewuBlue.copy(alpha = 0.07f) else cs.surfaceContainer,
        tween(140),
        label = "selectorBg",
    )
    Box(modifier) {
        Row(
            Modifier
                .height(52.dp)
                .pressScale(interaction.pressed)
                .clip(RoundedCornerShape(14.dp))
                .background(bg)
                .hoverable(interaction.source)
                .clickable(interactionSource = interaction.source, indication = null) { expanded = true }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(options.firstOrNull { it.first == value }?.second ?: value, color = cs.onSurface, fontSize = 14.sp)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Outlined.ExpandMore, null, tint = cs.onSurfaceSecondary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(14.dp),
            containerColor = cs.surfaceContainer,
        ) {
            options.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(label, color = if (key == value) KewuBlue else cs.onSurface, fontWeight = if (key == value) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = { onSelect(key); expanded = false },
                )
            }
        }
    }
}

@Composable
fun PaginationBar(page: Int, size: Int, total: Int, onPage: (Int) -> Unit) {
    val cs = COUITheme.colorScheme
    val maxPage = ((total + size - 1) / size).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Text("共 $total 条 · 第 $page / $maxPage 页", color = cs.onSurfaceSecondary, fontSize = 12.sp)
        Spacer(Modifier.width(10.dp))
        AdminIconAction(Icons.Outlined.ChevronLeft, "上一页", enabled = page > 1) { onPage(page - 1) }
        AdminIconAction(Icons.Outlined.ChevronRight, "下一页", enabled = page < maxPage) { onPage(page + 1) }
    }
}

/** 圆形图标按钮：悬停浮出浅主色底 + 按压回弹。 */
@Composable
fun AdminIconAction(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = COUITheme.colorScheme.onSurfaceSecondary,
    onClick: () -> Unit,
) {
    val interaction = rememberAdminInteraction()
    val bg by animateColorAsState(
        if (interaction.hovered && enabled) KewuBlue.copy(alpha = 0.09f) else Color.Transparent,
        tween(120),
        label = "iconBg",
    )
    Box(
        modifier
            .size(34.dp)
            .pressScale(interaction.pressed && enabled, 0.9f)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .hoverable(interaction.source, enabled)
            .clickable(interactionSource = interaction.source, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(19.dp))
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String = "确认",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = COUITheme.colorScheme.onSurface, fontSize = 19.sp, fontWeight = FontWeight.SemiBold) },
        text = { Text(text, color = COUITheme.colorScheme.onSurfaceSecondary, fontSize = 14.sp, lineHeight = 21.sp) },
        confirmButton = {
            AdminButton(confirmText, onConfirm, style = if (destructive) AdminButtonStyle.Danger else AdminButtonStyle.Primary)
        },
        dismissButton = { AdminButton("取消", onDismiss, style = AdminButtonStyle.Secondary) },
    )
}

@Composable
fun DetailRow(label: String, value: String) {
    val cs = COUITheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(label, color = cs.onSurfaceSecondary, fontSize = 14.sp, modifier = Modifier.width(110.dp))
        Text(value, color = cs.onSurface, fontSize = 14.sp, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
    }
}
