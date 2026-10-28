package com.kewu.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.HapticKind
import com.kewu.app.haptic
import com.kewu.app.todayCn
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.DatePicker
import io.github.suqi8.coui.kmp.basic.DateValue
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TimePicker
import io.github.suqi8.coui.kmp.basic.TimeValue
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlin.time.Clock

/**
 * 截止时间选择（2026-10-26：发布作业不再手输时间）。
 *
 * 行：coui [ArrowPreference]「截止时间」+ 右侧当前值；点开 [KewuBottomSheet]（coui OverlayBottomSheet）：
 * 快捷 chip（今晚 / 明晚 / 3 天后 / 一周后，均 23:59）+ coui [DatePicker] 年月日滚轮 + coui [TimePicker]
 * 24 小时制时分滚轮 + 「确定」。输出格式与后端约定一致：`yyyy-MM-dd HH:mm`（东八区）。
 */
@Composable
fun DeadlinePickerField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val cs = COUITheme.colorScheme
    var open by remember { mutableStateOf(false) }
    KewuCardGroup(modifier) {
        ArrowPreference(
            title = "截止时间",
            onClick = { haptic(HapticKind.Tick); open = true },
            endActions = {
                Text(
                    value.ifBlank { "请选择" },
                    color = if (value.isBlank()) cs.onSurfaceVariantSummary else cs.onSurface,
                    fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
            },
        )
    }
    if (open) {
        DeadlineSheet(initial = value, onDismiss = { open = false }, onConfirm = { onChange(it) })
    }
}

@Composable
private fun DeadlineSheet(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val start = remember { parseDeadline(initial) ?: defaultDeadline() }
    var date by remember { mutableStateOf(start.first) }
    var time by remember { mutableStateOf(start.second) }
    var picked by remember { mutableStateOf<String?>(null) }
    val thisYear = remember { dateAfterDays(0).year }

    KewuBottomSheet(
        show = true,
        title = "选择截止时间",
        onDismiss = {
            picked?.let(onConfirm)
            onDismiss()
        },
    ) { close ->
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("今晚" to 0, "明晚" to 1, "3 天后" to 3, "一周后" to 7).forEach { (label, days) ->
                Chip(selected = false, label = label, onClick = {
                    haptic(HapticKind.Tick)
                    date = dateAfterDays(days)
                    time = TimeValue(23, 59)
                })
            }
        }
        Spacer(Modifier.height(8.dp))
        DatePicker(
            value = date,
            onValueChange = { date = it },
            modifier = Modifier.fillMaxWidth(),
            yearRange = thisYear..(thisYear + 2),
            // 默认月份是英文缩写（Jan/Feb…），改成「1月」…「12月」
            yearLabel = { "${it}年" },
            yearColumnWidth = 92.dp,
            monthLabel = { "${it}月" },
            dayLabel = { "${it}日" },
        )
        Spacer(Modifier.height(4.dp))
        TimePicker(
            value = time,
            onValueChange = { time = it },
            modifier = Modifier.fillMaxWidth(),
            is24Hour = true,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "截止：${formatDeadline(date, time)}",
            color = COUITheme.colorScheme.onSurfaceSecondary, fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        SheetConfirmButton("确定") {
            haptic(HapticKind.Light)
            picked = formatDeadline(date, time)
            close()
        }
    }
}

private fun two(v: Int) = if (v < 10) "0$v" else "$v"

internal fun formatDeadline(d: DateValue, t: TimeValue): String =
    "${d.year}-${two(d.month)}-${two(d.day)} ${two(t.hour)}:${two(t.minute)}"

/** 解析 `yyyy-MM-dd HH:mm`（容忍 `/`、`T`、缺时间）；不合法返回 null。 */
internal fun parseDeadline(s: String): Pair<DateValue, TimeValue>? {
    val m = Regex("""(\d{4})[-/](\d{1,2})[-/](\d{1,2})(?:[ T](\d{1,2}):(\d{2}))?""").find(s.trim()) ?: return null
    val (y, mo, d, h, mi) = m.destructured
    val month = mo.toInt().takeIf { it in 1..12 } ?: return null
    val day = d.toInt().takeIf { it in 1..31 } ?: return null
    return DateValue(y.toInt(), month, day) to TimeValue(h.toIntOrNull()?.coerceIn(0, 23) ?: 23, mi.toIntOrNull()?.coerceIn(0, 59) ?: 59)
}

/** 默认：一周后 23:59。 */
private fun defaultDeadline(): Pair<DateValue, TimeValue> = dateAfterDays(7) to TimeValue(23, 59)

/** 东八区今天起第 [days] 天的日期。 */
private fun dateAfterDays(days: Int): DateValue {
    val ms = Clock.System.now().toEpochMilliseconds() + days * 86_400_000L
    val (y, m, d) = todayCn(ms).split("-").map { it.toInt() }
    return DateValue(y, m, d)
}
