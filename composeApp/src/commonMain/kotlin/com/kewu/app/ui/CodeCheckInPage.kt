package com.kewu.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.AttendanceSession
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuBlue
import com.kewu.app.KewuGreen
import com.kewu.app.haptic
import com.kewu.app.hapticClickable
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 学生端**暗号签到**独立页（扫码失败的兜底 / 相机不可用时的主路径）。
 *
 * 设计风格（按用户要求）：
 * - **coui = 整体版式**：页骨架 / 信息卡 / 问题卡 / 按钮都是 coui 语言（M3 配色、squircle、干净留白）。
 * - **液态玻璃 = 输入框**：暗号输入 / 随堂答案输入用 [KewuGlassCard] 玻璃容器（背景层顶部有蓝绿环境光晕，
 *   玻璃的模糊折射才看得出来）；成功弹层也用玻璃。
 *
 * 支持 QUIZ 模式：该签到带随堂问题时必须作答，连同暗号一起提交 `attendanceCheckIn`，
 * 否则后端会拒绝（「请回答随堂问题」）。
 */
@Composable
fun CodeCheckInPage(
    sessionId: String,
    initialCode: String = "",
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val pageBackdrop = rememberGlassBackdrop()
    val scope = rememberCoroutineScope()

    var session by remember { mutableStateOf<AttendanceSession?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf(initialCode) }
    var answer by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var success by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sessionId) {
        session = KewuApi.getAttendance(sessionId)
        loadFailed = session == null
    }

    // 成功后短暂展示「签到成功」再返回
    LaunchedEffect(success) {
        if (success) {
            delay(1500)
            onDone()
        }
    }

    BackHandler { if (!success) onBack() }

    fun submit() {
        val s = session ?: return
        if (submitting || success) return
        errorMsg = null
        if (code.isBlank()) {
            errorMsg = "请输入签到暗号"
            return
        }
        if (s.quiz != null && answer.isBlank()) {
            errorMsg = "请先回答随堂问题"
            return
        }
        haptic(HapticKind.Medium)
        submitting = true
        scope.launch {
            val err = KewuApi.attendanceCheckIn(
                id = sessionId,
                studentId = KewuApi.username ?: "",
                studentName = KewuApi.currentUser?.user?.nickname ?: (KewuApi.username ?: ""),
                code = code.trim(),
                answer = s.quiz?.let { answer.trim().ifBlank { null } },
                latitude = 0.0, longitude = 0.0,
            )
            submitting = false
            if (err == null) {
                haptic(HapticKind.Light)
                success = true
            } else {
                errorMsg = err
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 背景层：玻璃采样源 + 顶部蓝 / 底部绿环境光晕（液态玻璃输入框靠它出效果）
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(pageBackdrop)
                .background(cs.background),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(KewuBlue.copy(alpha = 0.12f), Color.Transparent),
                        ),
                    ),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, KewuGreen.copy(alpha = 0.08f)),
                        ),
                    ),
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 96.dp),
        ) {
            HubTopBar("暗号签到", onBack)
            Spacer(Modifier.height(12.dp))

            when {
                loadFailed -> Box(
                    Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("签到信息加载失败，请返回后重试", color = cs.onSurfaceVariant, fontSize = 14.sp)
                }
                session == null -> Box(
                    Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = cs.primary, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                }
                else -> {
                    val s = session!!

                    // ── 签到信息（coui 素卡） ──
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Text(s.title, color = cs.onSurface, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${s.publisher} 老师 · ${if (s.state == "OPEN") "进行中" else "已结束"}",
                            color = cs.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.height(20.dp))

                    // ── 暗号输入（液态玻璃） ──
                    GlassCodeInput(
                        backdrop = pageBackdrop,
                        label = "签到暗号",
                        value = code,
                        onValueChange = { v -> code = v.filter { ch -> ch.isDigit() }.take(6) },
                        placeholder = "教师端二维码下方的 6 位数字",
                        big = true,
                    )
                    Spacer(Modifier.height(18.dp))

                    // ── 随堂问题（QUIZ 模式必答） ──
                    val q = s.quiz
                    if (q != null) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            Text(
                                "随堂问题",
                                color = cs.onSurfaceVariant,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(6.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(cs.surfaceVariant)
                                    .padding(14.dp),
                            ) {
                                Column {
                                    Text(q.question, color = cs.onSurface, fontSize = 14.sp, lineHeight = 20.sp)
                                    if (q.hint.isNotBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text("提示：${q.hint}", color = cs.onSurfaceVariant, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        GlassCodeInput(
                            backdrop = pageBackdrop,
                            label = "你的回答",
                            value = answer,
                            onValueChange = { answer = it },
                            placeholder = "填写你的回答",
                            big = false,
                        )
                        Spacer(Modifier.height(18.dp))
                    }

                    // ── 错误提示 ──
                    errorMsg?.let { msg ->
                        Text(
                            msg,
                            color = cs.error,
                            fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    // ── 提交按钮（coui 实心主按钮） ──
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (submitting) cs.primary.copy(alpha = 0.6f) else cs.primary)
                            .hapticClickable(enabled = !submitting && !success) { submit() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (submitting) "签到中…" else "立即签到",
                            color = cs.onPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 15.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "暗号 30 秒内有效，过期请看教师端最新二维码",
                        color = cs.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // ── 成功玻璃弹层 ──
        if (success) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                KewuGlassCard(backdrop = pageBackdrop, modifier = Modifier.padding(horizontal = 48.dp)) {
                    Column(
                        Modifier.padding(26.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CodeSuccessCheck()
                        Spacer(Modifier.height(12.dp))
                        Text("签到成功", color = KewuGreen, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** 液态玻璃输入框：玻璃容器（带高光折射）+ BasicTextField。 */
@Composable
private fun GlassCodeInput(
    backdrop: LayerBackdrop,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    big: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            label,
            color = cs.onSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        KewuGlassCard(
            backdrop = backdrop,
            shape = RoundedCornerShape(16.dp),
            blurRadius = 26.dp,
            tintAlpha = 0.2f,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = if (big) 14.dp else 12.dp),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = cs.onSurface,
                        fontSize = if (big) 24.sp else 15.sp,
                        fontWeight = if (big) FontWeight.Bold else FontWeight.Normal,
                        letterSpacing = if (big) 6.sp else 0.sp,
                        textAlign = if (big) TextAlign.Center else TextAlign.Start,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            if (value.isEmpty()) {
                                Text(
                                    placeholder,
                                    color = cs.onSurfaceVariant.copy(alpha = 0.6f),
                                    fontSize = if (big) 16.sp else 15.sp,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
        }
    }
}

/** 成功对勾：绿圈 + 圆角对勾路径。 */
@Composable
private fun CodeSuccessCheck() {
    Canvas(Modifier.size(64.dp)) {
        val r = size.minDimension / 2
        val c = center
        drawCircle(KewuGreen.copy(alpha = 0.14f), radius = r)
        drawCircle(KewuGreen, radius = r * 0.92f, style = Stroke(width = 3.dp.toPx()))
        val path = Path().apply {
            moveTo(c.x - r * 0.42f, c.y + r * 0.04f)
            lineTo(c.x - r * 0.08f, c.y + r * 0.38f)
            lineTo(c.x + r * 0.48f, c.y - r * 0.34f)
        }
        drawPath(path, color = KewuGreen, style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
