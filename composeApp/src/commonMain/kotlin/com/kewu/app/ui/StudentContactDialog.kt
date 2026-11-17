package com.kewu.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuBlue
import com.kewu.app.StudentContact
import com.kewu.app.haptic
import io.github.suqi8.coui.kmp.basic.Surface
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.overlay.OverlayDialog
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * §88 教师听课状态里点学生姓名弹出的「个人主页」资料卡。
 *
 * 服务端专注结论只带 username / name（不含班级），这里按 courseId 找到任课班、拉班级通讯录，
 * 用姓名 / 学号匹配出联系方式；匹配不到或老后端无通讯录时仍展示姓名并给「复制姓名」兜底。
 * 一键拨打复用 [DialButton]（与联系老师完全相同的拨号逻辑）。
 */
@Composable
internal fun StudentContactDialog(
    name: String,
    username: String?,
    courseId: String,
    onDismiss: () -> Unit,
) {
    var contact by remember { mutableStateOf<StudentContact?>(null) }
    var tried by remember { mutableStateOf(false) }
    LaunchedEffect(name, username, courseId) {
        // 找到该课程对应的班级（可能多个班共课，取第一个能匹配到联系人的）
        val classes = runCatching { KewuApi.getTeacherClasses()?.classes }.getOrNull().orEmpty()
        val candidates = classes.filter { it.courseId == courseId }.ifEmpty { classes }
        for (cls in candidates) {
            val (list, _) = KewuApi.getClassContacts(cls.id)
            val hit = list?.firstOrNull { c ->
                c.name == name || c.name == username || c.userId == username || c.studentNo == username
            }
            if (hit != null) { contact = hit; break }
        }
        tried = true
    }

    OverlayDialog(
        show = true,
        title = if (name.isNotBlank()) name else (username ?: "学生资料"),
        summary = "学生资料 · 联系方式仅任课老师可见",
        onDismissRequest = onDismiss,
        onDismissFinished = { },
    ) {
        val cs = COUITheme.colorScheme
        val display = contact?.name ?: name.ifBlank { username ?: "同学" }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(50)),
                    color = KewuBlue.copy(alpha = 0.16f),
                ) {
                    androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                        Text(display.take(1), color = KewuBlue, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(display, color = cs.onSurface, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    val sub = contact?.let {
                        buildString {
                            if (it.studentNo.isNotBlank()) append("学号 ${it.studentNo}")
                            if (!it.school.isNullOrBlank()) { if (isNotEmpty()) append(" · "); append(it.school) }
                        }
                    }.takeIf { !it.isNullOrBlank() } ?: "课堂专注分析对象"
                    Text(sub, color = cs.onSurfaceSecondary, fontSize = 12.5.sp)
                }
            }

            Spacer(Modifier.height(18.dp))
            InfoRow("手机号", contact?.phone?.takeIf { it.isNotBlank() } ?: "未公开")
            Spacer(Modifier.height(8.dp))
            InfoRow("邮箱", contact?.email?.takeIf { it.isNotBlank() } ?: "未公开")

            Spacer(Modifier.height(18.dp))
            if (!contact?.phone.isNullOrBlank()) {
                DialButton(contact?.phone, Modifier.fillMaxWidth())
            } else {
                val clipboard = LocalClipboardManager.current
                KewuSecondaryButton(
                    text = "复制姓名",
                    onClick = {
                        haptic(HapticKind.Light)
                        clipboard.setText(AnnotatedString(display))
                    },
                )
            }
            if (!tried) {
                Spacer(Modifier.height(8.dp))
                Text("正在查找联系方式…", color = cs.onSurfaceVariantSummary, fontSize = 11.5.sp)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val cs = COUITheme.colorScheme
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = cs.onSurfaceSecondary, fontSize = 13.sp, modifier = Modifier.width(52.dp))
        Text(value, color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
