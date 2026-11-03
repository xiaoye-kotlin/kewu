package com.kewu.admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardColors
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldDefaults
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.theme.COUITheme

@Composable
fun PageHeader(
    title: String,
    subtitle: String,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = actions)
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    // coui 原生 squircle 白卡：与学生端 / 教师端的内容卡观感一致。
    val cs = COUITheme.colorScheme
    Card(
        modifier = modifier,
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
    Panel(modifier) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).background(accent.copy(alpha = 0.11f), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

enum class StatusTone { SUCCESS, WARNING, ERROR, INFO, NEUTRAL }

@Composable
fun StatusPill(text: String, tone: StatusTone = StatusTone.NEUTRAL) {
    val color = when (tone) {
        StatusTone.SUCCESS -> KewuGreen
        StatusTone.WARNING -> AdminAmber
        StatusTone.ERROR -> AdminRed
        StatusTone.INFO -> KewuBlue
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = color.copy(alpha = 0.10f),
        contentColor = color,
        shape = RoundedCornerShape(50),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(color, RoundedCornerShape(50)))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
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
    Panel(modifier) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 18.dp, vertical = 13.dp)) {
            columns.forEach { column ->
                Box(Modifier.weight(column.weight)) {
                    Text(column.title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = key) { item ->
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (onRowClick != null) Modifier.clickable { onRowClick(item) } else Modifier)
                        .padding(horizontal = 18.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    columns.forEach { column -> Box(Modifier.weight(column.weight)) { row(item, column) } }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
            }
        }
    }
}

@Composable
fun TableText(text: String, bold: Boolean = false, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(
        text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        color = color,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(end = 10.dp),
    )
}

@Composable
fun LoadingBlock(message: String = "正在加载数据…") {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
fun EmptyBlock(title: String = "暂无数据", detail: String = "当前筛选条件下没有记录") {
    Column(Modifier.fillMaxSize().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.Inbox, null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
fun ErrorBlock(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("数据加载失败", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text("重新加载") }
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

@Composable
fun OptionSelector(
    value: String,
    options: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, shape = RoundedCornerShape(12.dp), modifier = Modifier.height(48.dp)) {
            Text(options.firstOrNull { it.first == value }?.second ?: value)
            Icon(Icons.Outlined.ArrowDropDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onSelect(key); expanded = false })
            }
        }
    }
}

@Composable
fun PaginationBar(page: Int, size: Int, total: Int, onPage: (Int) -> Unit) {
    val maxPage = ((total + size - 1) / size).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Text("共 $total 条 · 第 $page / $maxPage 页", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        IconButton(onClick = { onPage(page - 1) }, enabled = page > 1) { Icon(Icons.Outlined.ChevronLeft, "上一页") }
        IconButton(onClick = { onPage(page + 1) }, enabled = page < maxPage) { Icon(Icons.Outlined.ChevronRight, "下一页") }
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
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            if (destructive) {
                Button(onClick = onConfirm, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(confirmText) }
            } else {
                Button(onClick = onConfirm) { Text(confirmText) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(value, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
    }
}
