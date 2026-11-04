package com.kewu.app.ui

import com.kewu.app.todayCn
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.ClassNoteTarget
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuBlue
import com.kewu.app.KewuGreen
import com.kewu.app.AiNav
import com.kewu.app.Notifications
import com.kewu.app.haptic
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.overlay.OverlayDialog
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlin.time.Clock

/**
 * 通知中心 UI（2026-10-26）。
 *
 * - [NotificationBellButton]：首页（学生端/教师端）右上角的 coui 铃铛按钮 + 未读红点。
 * - [NotificationSheet]：点击铃铛弹出的通知列表（coui OverlayDialog），学生 / 教师内容不同。
 *
 * ★ 与「助教」tab 的 AI 消息红点完全无关：这里只服务系统通知铃铛。
 */

private val NOTIF_RED = Color(0xFFFF3B30)

// ───────────────────────── 铃铛按钮 ─────────────────────────

@Composable
fun NotificationBellButton(
    unread: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = COUITheme.colorScheme
    Box(modifier, contentAlignment = Alignment.Center) {
        IconButton(
            onClick = { haptic(HapticKind.Tick); onClick() },
            backgroundColor = cs.secondaryContainer,
            cornerRadius = 18.dp,
            minHeight = 36.dp,
            minWidth = 36.dp,
        ) {
            Canvas(Modifier.size(22.dp)) { drawBell(cs.onSurface, size) }
        }
        if (unread > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .size(if (unread > 9) 19.dp else 17.dp)
                    .clip(CircleShape)
                    .background(NOTIF_RED),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (unread > 99) "99+" else unread.toString(),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** 自绘铃铛（与学生端导航栏自绘图标同一语言，避免依赖未知 coui 图标资源）。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBell(color: Color, size: Size) {
    val s = size.minDimension
    val stroke = Stroke(width = s * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    // 顶环
    drawCircle(color, style = stroke, radius = s * 0.07f, center = Offset(s * 0.5f, s * 0.17f))
    // 铃身（左缘下行 → 底部外扩 → 右缘回收）
    val body = Path().apply {
        moveTo(s * 0.30f, s * 0.24f)
        quadraticBezierTo(s * 0.30f, s * 0.50f, s * 0.22f, s * 0.66f)
        lineTo(s * 0.78f, s * 0.66f)
        quadraticBezierTo(s * 0.70f, s * 0.50f, s * 0.70f, s * 0.24f)
    }
    drawPath(body, color, style = stroke)
    // 底口横线
    drawLine(color, Offset(s * 0.18f, s * 0.70f), Offset(s * 0.82f, s * 0.70f),
        strokeWidth = s * 0.06f, cap = StrokeCap.Round)
    // 铃舌
    drawCircle(color, radius = s * 0.06f, center = Offset(s * 0.5f, s * 0.80f))
}

// ───────────────────────── 通知列表弹窗 ─────────────────────────

@Composable
fun NotificationSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    onOpenClassNote: ((ClassNoteTarget) -> Unit)? = null,
) {
    val cs = COUITheme.colorScheme
    // 只在打开时读一次（旧版每次重组都读 kv + 解析 JSON，弹窗没打开也在读）
    val list = remember(show) { Notifications.load() } // 打开 / 关闭时各读一次，退场动画期间列表不清空
    // 2026-11-03：一打开就全部已读——铃铛红点立刻消失；弹窗里仍按打开前的快照标出哪些是新的
    LaunchedEffect(show) { if (show) Notifications.markAllRead() }
    OverlayDialog(
        show = show,
        title = "通知",
        summary = null,
        onDismissRequest = onDismiss,
        onDismissFinished = { },
    ) {
        if (list.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().padding(vertical = 36.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("暂无通知", color = cs.onSurfaceSecondary, fontSize = 14.sp)
            }
        } else {
            // 2026-11-03：固定半屏高 + 懒加载列表（纪要通知正文很长时旧版会把弹窗撑满整屏）
            val density = LocalDensity.current
            val halfScreen = with(density) { (LocalWindowInfo.current.containerSize.height / 2).toDp() }
            LazyColumn(
                Modifier.fillMaxWidth().height(halfScreen.coerceAtLeast(240.dp)),
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list.distinctBy { it.id }, key = { it.id }) { n -> NotificationRow(n, onOpenClassNote, onDismiss) }
            }
        }
    }
}

@Composable
private fun NotificationRow(
    n: Notifications.Info,
    onOpenClassNote: ((ClassNoteTarget) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    val (label, tint) = typeMeta(n.type)
    // 录音转写通知可下钻到单节课堂纪要详情
    val noteTarget = remember(n.id) {
        if (n.type == Notifications.TYPE_RECORDING_DONE && onOpenClassNote != null)
            KewuApi.classNoteTargetFromNotificationId(n.id)
        else null
    }
    // B 站视频总结完成：回到该视频（播放页里可直接问小悟）
    val biliVideo = remember(n.id) {
        if (n.type == Notifications.TYPE_BILI_NOTE) com.kewu.app.BiliNoteWatcher.bvidOf(n.id)?.let { com.kewu.app.BiliNoteWatcher.video(it) } else null
    }
    val open: (() -> Unit)? = noteTarget?.let { t -> { haptic(HapticKind.Tick); onOpenClassNote?.invoke(t) } }
        ?: biliVideo?.let { v -> { haptic(HapticKind.Tick); onDismiss(); VideoPlayerHub.current = v } }
        // ★ H2：新作业通知点开直接切到「任务」tab（2）
        ?: if (n.type == Notifications.TYPE_NEW_ASSIGNMENT) {
            { haptic(HapticKind.Tick); onDismiss(); AiNav.goStudentTab(2) }
        } else null
    val action = when {
        noteTarget != null -> "查看纪要"
        biliVideo != null -> "回到视频"
        n.type == Notifications.TYPE_NEW_ASSIGNMENT -> "去任务页"
        else -> null
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        colors = kewuCardColors(),
        onClick = open,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            // 未读小圆点
            if (!n.read) {
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(NOTIF_RED)
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 类型胶囊
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(tint.copy(alpha = 0.14f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(formatNotifTime(n.ts), color = cs.onSurfaceSecondary, fontSize = 11.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(n.title, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                // 纪要全文去详情页看，这里只给一句话
                val body = if (n.type == Notifications.TYPE_RECORDING_DONE) "课堂纪要已生成" else n.body
                Text(body, color = cs.onSurfaceSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (open != null && action != null) {
                    Spacer(Modifier.height(10.dp))
                    KewuSecondaryButton(action, open, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** 类型 → 文案 + 强调色（学生 / 教师共用同一映射，按 type 区分内容）。 */
private fun typeMeta(type: String): Pair<String, Color> = when (type) {
    Notifications.TYPE_NEW_ASSIGNMENT -> "新作业" to KewuBlue
    Notifications.TYPE_RECORDING_DONE -> "录音转写" to KewuGreen
    Notifications.TYPE_GRADE_PUBLISHED -> "作业批改" to Color(0xFFFF9500)
    Notifications.TYPE_BILI_NOTE -> "视频总结" to Color(0xFFFB7299)
    Notifications.TYPE_COURSE_REMINDER -> "上课提醒" to KewuBlue
    Notifications.TYPE_NEW_SUBMISSION -> "新提交" to KewuBlue
    Notifications.TYPE_STUDENT_QUESTION -> "学生提问" to KewuGreen
    Notifications.TYPE_ATTENDANCE_DONE -> "签到" to Color(0xFFFF9500)
    else -> "系统" to Color(0xFF8E8E93)
}

/** 相对时间（UTC+8）：刚刚 / x 分钟前 / x 小时前 / yyyy-MM-dd。 */
private fun formatNotifTime(ts: Long): String {
    val now = Clock.System.now().toEpochMilliseconds()
    val diff = now - ts
    val tz8 = 8L * 3_600_000L
    val dayStartNow = (now + tz8) / 86_400_000L
    val dayStartTs = (ts + tz8) / 86_400_000L
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 && dayStartNow == dayStartTs -> "${diff / 3_600_000} 小时前"
        dayStartNow - dayStartTs == 1L -> "昨天"
        else -> {
            // 2026-10-26：commonMain 不能用 java.time（iOS 编不过），用东八区日期工具
            todayCn(ts)
        }
    }
}
