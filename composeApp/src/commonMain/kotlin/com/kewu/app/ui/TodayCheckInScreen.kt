package com.kewu.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.AttendanceSession
import com.kewu.app.AttendanceStats
import com.kewu.app.CourseInfo
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuBlue
import com.kewu.app.KewuGreen
import com.kewu.app.haptic
import com.kewu.app.todayCn
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 今日签到详情（2026-10-26）：教师首页「今日签到率」卡点进来（旧版误跳到助教页）。
 *
 * - 顶部液态玻璃总览卡：今日总签到率环形进度（draw 阶段动画，不套 graphicsLayer）+ 实到 / 应到 / 迟到 / 缺勤；
 * - 下方 coui 卡片列出今天发起的每一场签到（课程、时间、状态、签到率条、人数），点进看该场签到详情。
 * 数据：老师的每门课 GET /api/attendance?courseId= 过滤今天创建的，再并行取每场 stats。
 */
@Composable
internal fun TodayCheckInScreen(
    courses: List<CourseInfo>?,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val cs = COUITheme.colorScheme
    val pageBackdrop = rememberGlassBackdrop()
    var rows by remember { mutableStateOf<List<Pair<AttendanceSession, AttendanceStats?>>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableStateOf(0) }

    LaunchedEffect(courses, reloadTick) {
        failed = false
        val list = courses ?: run {
            KewuApi.me()
            KewuApi.myCourses()
        }
        if (list == null) { failed = true; rows = emptyList(); return@LaunchedEffect }
        val today = todayCn()
        val result = coroutineScope {
            list.map { c -> async { KewuApi.getAttendances(c.id).orEmpty().filter { it.createdAt.startsWith(today) } } }
                .awaitAll().flatten()
                .sortedByDescending { it.createdAt }
                .map { s -> async { s to KewuApi.getAttendanceStats(s.id) } }
                .awaitAll()
        }
        rows = result
    }

    val courseNames = courses.orEmpty().associate { it.id to it.name }
    val all = rows.orEmpty()
    val expected = all.sumOf { it.second?.expected ?: 0 }
    val checked = all.sumOf { it.second?.checked ?: 0 }
    val late = all.sumOf { it.second?.late ?: 0 }
    val absent = all.sumOf { it.second?.absent ?: 0 }
    val rate = if (expected > 0) checked.toFloat() / expected else 0f

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().layerBackdrop(pageBackdrop).background(cs.surface))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 120.dp)) {
            KewuTopBar("今日签到", onBack = onBack)
            Spacer(Modifier.height(4.dp))

            // ── 总览（液态玻璃） ──
            KewuGlassCard(
                backdrop = pageBackdrop, shape = RoundedCornerShape(24.dp), blurRadius = 22.dp, tintAlpha = 0.30f,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    RateRing(if (rows == null) 0f else rate, Modifier.size(112.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (rows == null || expected == 0) "—" else "${(rate * 100).toInt()}%",
                                color = cs.onSurface, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                            Text("签到率", color = cs.onSurfaceSecondary, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(todayCn(), color = cs.onSurfaceSecondary, fontSize = 12.5.sp)
                        Spacer(Modifier.height(2.dp))
                        Text("今天共 ${all.size} 场签到", color = cs.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MiniNum("实到", "$checked", KewuGreen)
                            MiniNum("应到", "$expected", KewuBlue)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MiniNum("迟到", "$late", Color(0xFFF5A623))
                            MiniNum("缺勤", "$absent", Color(0xFFE5484D))
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            KewuSectionTitle("各场签到")
            when {
                rows == null -> KewuLoading(text = "正在汇总今天的签到…")
                failed -> {
                    KewuEmpty("加载失败，请检查网络")
                    KewuSecondaryButton("重新加载", { reloadTick++ }, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                }
                all.isEmpty() -> KewuEmpty("今天还没有发起签到，回到首页点「开始上课」即可发起")
                else -> all.forEachIndexed { i, (s, st) ->
                    val p = rememberEnterProgress(i, stepMs = 50L)
                    SessionRow(s, st, courseNames[s.courseId] ?: s.courseId, Modifier.riseEnter(p, 24f)) {
                        haptic(HapticKind.Tick); onOpenSession(s.id)
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(s: AttendanceSession, st: AttendanceStats?, course: String, modifier: Modifier, onClick: () -> Unit) {
    val cs = COUITheme.colorScheme
    val open = s.state.equals("OPEN", ignoreCase = true)
    val rate = st?.checkRate?.toFloat()?.coerceIn(0f, 1f) ?: 0f
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
        cornerRadius = 18.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        colors = kewuCardColors(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.title.ifBlank { "$course 签到" }, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text("$course · ${s.createdAt.takeLast(5)} 发起", color = cs.onSurfaceSecondary, fontSize = 12.5.sp)
            }
            KewuTag(
                if (open) "进行中" else "已结束",
                (if (open) KewuGreen else cs.onSurfaceSecondary).copy(alpha = 0.14f),
                if (open) KewuGreen else cs.onSurfaceSecondary,
            )
        }
        Spacer(Modifier.height(10.dp))
        // 签到率条（draw 阶段宽度）
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.onSurface.copy(alpha = 0.08f))) {
            Box(Modifier.fillMaxWidth(rate.coerceAtLeast(0.02f)).fillMaxHeight().clip(RoundedCornerShape(3.dp))
                .background(if (rate >= 0.8f) KewuGreen else if (rate >= 0.5f) KewuBlue else Color(0xFFE5484D)))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            st?.let { "签到率 ${(it.checkRate * 100).toInt()}% · 实到 ${it.checked}/${it.expected} · 迟到 ${it.late} · 缺勤 ${it.absent}" }
                ?: "统计加载失败",
            color = cs.onSurfaceSecondary, fontSize = 12.5.sp,
        )
    }
}

@Composable
private fun RowScope.MiniNum(label: String, value: String, color: Color) {
    val cs = COUITheme.colorScheme
    Row(
        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(cs.surface.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, color = cs.onSurfaceSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** 环形进度：进入时从 0 平滑转到目标值（动画值只在 Canvas draw 里读）。 */
@Composable
private fun RateRing(target: Float, modifier: Modifier, center: @Composable () -> Unit) {
    val cs = COUITheme.colorScheme
    val anim = remember { Animatable(0f) }
    LaunchedEffect(target) { anim.animateTo(target.coerceIn(0f, 1f), tween(900, easing = FastOutSlowInEasing)) }
    Box(modifier, contentAlignment = Alignment.Center) {
        val track = cs.onSurface.copy(alpha = 0.08f)
        Canvas(Modifier.fillMaxSize()) {
            val w = 10.dp.toPx()
            val inset = w / 2
            val sz = Size(size.width - w, size.height - w)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), sz, style = Stroke(w, cap = StrokeCap.Round))
            val v = anim.value
            val color = if (v >= 0.8f) KewuGreen else if (v >= 0.5f) KewuBlue else Color(0xFFE5484D)
            if (v > 0f) drawArc(color, -90f, 360f * v, false, Offset(inset, inset), sz, style = Stroke(w, cap = StrokeCap.Round))
        }
        center()
    }
}
