package com.kewu.app.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.KewuApi
import com.kewu.app.KewuBlue
import com.kewu.app.KewuGreen
import com.kewu.app.RecordingDevice
import com.kewu.app.platformDeviceId
import com.kewu.app.ui.liquidglass.KewuGlassCard
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 课堂录音雷达（2026-10-26）：学生在首页点「课堂录音」后，课程卡区域换成这张雷达——
 * 圆心是自己，同一课堂会话里**正在录音**的其他设备是周围的光点（有几台就几个点）。
 *
 * - 外壳：液态玻璃卡（采样首页灰底）；卡内文字 / 统计行全 coui。
 * - 雷达本体：一块 Canvas。扫描束旋转、光点呼吸、扫过时点亮全部在 **draw 阶段**读动画值
 *   （不经 graphicsLayer / alpha 修饰符包裹玻璃，遵守采样约束）。
 * - 点位：按 deviceId 哈希出稳定的角度和半径（0.38–0.88），随机分散在圆心四周，且不会每次刷新乱跳；
 *   新出现的设备从圆心「弹出」到自己的位置。
 * - 数据：每 5 秒拉一次 GET /api/teacher/recording/devices（后端只返回 45 秒内有心跳的录音设备）。
 */
@Composable
fun RecordingRadar(
    backdrop: LayerBackdrop?,
    sessionId: String,
    modifier: Modifier = Modifier,
) {
    val cs = COUITheme.colorScheme
    val myId = remember { platformDeviceId() }
    var devices by remember { mutableStateOf<List<RecordingDevice>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    // 每台设备首次出现的时间（ms），用于「从圆心弹出」入场
    val bornAt = remember { mutableStateMapOf<String, Long>() }

    LaunchedEffect(sessionId) {
        while (true) {
            KewuApi.getRecordingDevices(sessionId)?.let { list ->
                val others = list.filter { it.deviceId != myId && it.status.equals("recording", ignoreCase = true) }
                val now = nowMs()
                others.forEach { d -> if (d.deviceId !in bornAt) bornAt[d.deviceId] = now }
                devices = others
            }
            loaded = true
            delay(2_000) // 2 秒一轮：新加入 / 停止录音的设备最多 2 秒内反映到雷达上
        }
    }

    val inf = rememberInfiniteTransition(label = "radar")
    val sweep = inf.animateFloat(0f, 360f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "sweep")
    val pulse = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "pulse")
    val breathe = inf.animateFloat(0.6f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "breathe")

    RadarShell(backdrop, modifier) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFE5484D)))
                Spacer(Modifier.width(8.dp))
                Text("课堂录音雷达", color = cs.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                // 我的录音计时（读录音单例，每秒更新）
                val rec = com.kewu.app.RecordingManager.status.collectAsState().value
                val mine = (rec as? com.kewu.app.RecordingStatus.Recording)?.elapsedSec ?: 0
                Text(fmtRecClock(mine), color = Color(0xFFE5484D), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    !loaded -> "正在寻找同一课堂里正在录音的设备"
                    devices.isEmpty() -> "目前只有你在录音，邀请同学一起录，音质会更好"
                    else -> "附近还有 ${devices.size} 台设备在录音，下课后自动择优合成"
                },
                color = cs.onSurfaceSecondary, fontSize = 13.sp,
            )
            Spacer(Modifier.height(14.dp))
            RadarCanvas(devices, bornAt, sweep, pulse, breathe, Modifier.fillMaxWidth().aspectRatio(1f))
            Spacer(Modifier.height(14.dp))
            // ★ devices 已过滤掉本机：自己是老师且正在录时，「老师」也要算在录（旧版只看别的设备 → 老师自己录永远显示未录）
            val meRecording = com.kewu.app.RecordingManager.status.collectAsState().value is com.kewu.app.RecordingStatus.Recording
            val iAmTeacher = com.kewu.app.currentRole() == com.kewu.app.ROLE_TEACHER
            val teacherOn = (iAmTeacher && meRecording) || devices.any { it.role.equals("teacher", true) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RadarStat("我", if (meRecording) "录音中" else "未录", KewuBlue)
                RadarStat("同学", "${devices.count { !it.role.equals("teacher", true) && !it.type.equals("screen", true) }} 台", KewuGreen)
                RadarStat("老师", if (teacherOn) "在录" else "未录", TeacherYellow)
            }
        }
    }
}

/** 外壳：首页用液态玻璃卡（采样页面灰底）；嵌在教师上课面板里（本身已是卡片）时用 coui 卡，避免玻璃套玻璃。 */
@Composable
private fun RadarShell(backdrop: LayerBackdrop?, modifier: Modifier, content: @Composable () -> Unit) {
    if (backdrop != null) {
        KewuGlassCard(
            backdrop = backdrop, shape = RoundedCornerShape(24.dp), blurRadius = 22.dp, tintAlpha = 0.30f,
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        ) { content() }
    } else {
        Box(
            modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(COUITheme.colorScheme.surface.copy(alpha = 0.6f)),
        ) { content() }
    }
}

/** 老师的雷达点颜色（用户指定黄色）。 */
private val TeacherYellow = Color(0xFFF5B800)

@Composable
private fun RadarCanvas(
    devices: List<RecordingDevice>,
    bornAt: Map<String, Long>,
    sweep: State<Float>,
    pulse: State<Float>,
    breathe: State<Float>,
    modifier: Modifier,
) {
    val cs = COUITheme.colorScheme
    val ring = cs.onSurface.copy(alpha = 0.10f)
    val grid = cs.onSurface.copy(alpha = 0.05f)
    val labelColor = cs.onSurface
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f * 0.96f
        val sweepDeg = sweep.value

        // 底盘：中心微亮的径向渐变
        drawCircle(
            Brush.radialGradient(listOf(KewuBlue.copy(alpha = 0.10f), KewuBlue.copy(alpha = 0.02f), Color.Transparent), c, r),
            r, c,
        )
        // 同心环 + 十字刻度
        for (i in 1..4) drawCircle(ring, r * i / 4f, c, style = Stroke(width = 1.dp.toPx()))
        drawLine(grid, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
        drawLine(grid, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())

        // 扫描束：扇形渐变尾迹 + 前沿亮线
        rotate(sweepDeg, c) {
            drawArc(
                Brush.sweepGradient(
                    0f to Color.Transparent, 0.80f to Color.Transparent,
                    0.97f to KewuGreen.copy(alpha = 0.22f), 1f to KewuGreen.copy(alpha = 0.32f),
                    center = c,
                ),
                startAngle = 0f, sweepAngle = 360f, useCenter = true,
                topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
            )
            drawLine(KewuGreen.copy(alpha = 0.65f), c, Offset(c.x + r, c.y), 1.5.dp.toPx())
        }

        // 外扩波纹（从圆心向外，表示「我在录音」）
        val p = pulse.value
        drawCircle(KewuBlue.copy(alpha = (1f - p) * 0.35f), r * (0.12f + 0.85f * p), c, style = Stroke(width = 2.dp.toPx()))

        // 其他设备光点
        val now = nowMs()
        devices.forEach { d ->
            val (angleDeg, dist) = stablePolar(d.deviceId)
            val born = bornAt[d.deviceId] ?: now
            val appear = ((now - born) / 600f).coerceIn(0f, 1f)
            val ease = 1f - (1f - appear) * (1f - appear) * (1f - appear)
            val rad = angleDeg / 180f * PI.toFloat()
            val pos = Offset(c.x + cos(rad) * r * dist * ease, c.y + sin(rad) * r * dist * ease)
            // 扫描束刚扫过时点亮
            val behind = ((sweepDeg - angleDeg) % 360f + 360f) % 360f
            val lit = (1f - behind / 120f).coerceIn(0f, 1f)
            val teacher = d.role.equals("teacher", true)
            val color = when {
                teacher -> TeacherYellow
                d.type.equals("screen", true) -> Color(0xFF7A5AF8)
                else -> KewuGreen
            }
            drawDevice(pos, color, lit, breathe.value, if (teacher) 1.3f else 1f)
            // 名字标签：点下方居中，「罗某某同学」「张某某老师」+ 已录时长（2026-10-26 用户要求）
            val who = d.name.trim().take(8)
            val label = (if (teacher) (if (who.isBlank()) "老师" else who + "老师") else (if (who.isBlank()) "同学" else who + "同学")) +
                (if (d.elapsedSec > 0) " · " + fmtRecClock(d.elapsedSec) else "")
            val layout = measurer.measure(label, labelStyle.copy(color = labelColor.copy(alpha = 0.55f + 0.45f * ease)))
            val tl = Offset(
                (pos.x - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width),
                (pos.y + 13.dp.toPx()).coerceAtMost(size.height - layout.size.height),
            )
            drawText(layout, topLeft = tl)
        }

        // 圆心：我
        drawCircle(KewuBlue.copy(alpha = 0.18f), 18.dp.toPx() * breathe.value, c)
        drawCircle(Color.White, 9.dp.toPx(), c)
        drawCircle(KewuBlue, 7.dp.toPx(), c)
    }
}

private fun DrawScope.drawDevice(pos: Offset, color: Color, lit: Float, breathe: Float, scale: Float = 1f) {
    drawCircle(color.copy(alpha = 0.10f + 0.20f * lit), (10f + 6f * breathe).dp.toPx() * scale, pos)
    drawCircle(Color.White, 6.dp.toPx() * scale, pos)
    drawCircle(color.copy(alpha = 0.75f + 0.25f * lit), 4.5.dp.toPx() * scale, pos)
}

/** deviceId → 稳定的极坐标（角度 0–360，半径占比 0.38–0.88）：随机分散、刷新不乱跳。 */
private fun stablePolar(id: String): Pair<Float, Float> {
    var h = 1125899906842597L
    id.forEach { h = 31 * h + it.code }
    val a = ((h ushr 8) % 3600L).toFloat() / 10f
    val d = 0.38f + ((h ushr 24) % 500L).toFloat() / 1000f
    return (if (a < 0) a + 360f else a) to d
}

@Composable
private fun RowScope.RadarStat(label: String, value: String, color: Color) {
    val cs = COUITheme.colorScheme
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(cs.surface.copy(alpha = 0.55f))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(5.dp))
            Text(label, color = cs.onSurfaceSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.height(3.dp))
        Text(value, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

/** 秒 → "mm:ss"（超过一小时 "h:mm:ss"）。 */
internal fun fmtRecClock(sec: Int): String {
    fun p2(v: Int) = if (v < 10) "0$v" else "$v"
    return if (sec >= 3600) "${sec / 3600}:${p2(sec % 3600 / 60)}:${p2(sec % 60)}" else "${p2(sec / 60)}:${p2(sec % 60)}"
}
