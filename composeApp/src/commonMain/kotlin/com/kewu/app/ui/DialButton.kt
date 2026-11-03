package com.kewu.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.HapticKind
import com.kewu.app.haptic
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Surface
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Phone
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * 一键拨号小按钮（2026-11-03）：coui 品牌色胶囊「拨打」，交给系统拨号盘；没有拨号能力（桌面端）时复制号码。
 * 号码里只保留数字和 +；为空时不显示。
 */
@Composable
internal fun DialButton(phone: String?, modifier: Modifier = Modifier) {
    val number = phone?.filter { it.isDigit() || it == '+' }.orEmpty()
    if (number.isEmpty()) return
    val cs = COUITheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val snackbar = LocalKewuSnackbar.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = cs.primary,
        contentColor = cs.onPrimary,
        onClick = {
            haptic(HapticKind.Light)
            val ok = runCatching { uriHandler.openUri("tel:$number") }.isSuccess
            if (!ok) {
                clipboard.setText(AnnotatedString(number))
                snackbar.toast("号码已复制")
            }
        },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(COUIIcons.Phone, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("拨打", color = cs.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}
