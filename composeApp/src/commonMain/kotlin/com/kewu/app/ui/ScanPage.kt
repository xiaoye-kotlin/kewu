package com.kewu.app.ui

import com.kewu.app.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuGreen
import com.kewu.app.RecordingManager
import com.kewu.app.haptic
import com.kewu.app.platformActivity
import com.kewu.app.requestCameraPermission
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Back
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 相机扫码视图（平台 actual）。
 * - Android：CameraX 预览 + ML Kit 条码识别（QrScanner.android.kt）
 * - Desktop / iOS：占位灰层（该平台不支持扫码，可改走暗号签到）
 *
 * @param enabled false 时暂停识别（如已出结果 / 非 Scanning 态），恢复 true 时重新 armed。
 */
@Composable
expect fun QrScannerView(onScanned: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true)

/** 中间取景框边长（扫码框 + 四周压暗都用它定位，保持同一视觉中心）。 */
private val FrameSize = 264.dp

/**
 * 解析教师端二维码内容 `kewusign://attend?sid=t101&code=830697` → (sid, code)。
 * 非课悟签到二维码返回 null（扫到别的码要给提示而不是崩溃）。
 */
internal fun parseKewuSignUri(raw: String): Pair<String, String>? {
    val s = raw.trim()
    if (!s.startsWith("kewusign://attend")) return null
    val q = s.substringAfter('?', "")
    var sid: String? = null
    var code = ""
    for (part in q.split('&')) {
        if (part.isBlank()) continue
        val k = part.substringBefore('=')
        val v = part.substringAfter('=', "")
        when (k) {
            "sid" -> sid = v
            "code" -> code = v
        }
    }
    val id = sid ?: return null
    return id to code
}

private sealed interface ScanState {
    data object Scanning : ScanState
    data object Submitting : ScanState
    data class Success(val sessionId: String) : ScanState
    data class Error(val message: String) : ScanState
}

/**
 * 学生端**扫码签到**页：相机全屏取景 + coui 液态玻璃遮罩（顶栏 / 取景框 / 提示卡 / 结果卡）。
 *
 * 流程：签到卡片点进来 → 申请相机权限 → 对准教师端展示的二维码 →
 * 解析 `kewusign://attend?sid&code` → [KewuApi.attendanceCheckIn] → 成功短暂展示后 [onDone] 返回首页。
 * 失败（如 QUIZ 模式要求回答随堂问题）→「输入暗号签到」带暗号跳 [CodeCheckInPage] 继续完成。
 *
 * coui 安全：本页自建 pageBackdrop（与 AttendanceDetailScreen 同模式），
 * 玻璃卡全部是采样者，绝不裹 alpha/graphicsLayer；相机 PreviewView 不是采样者，任意层都可放。
 *
 * 2026-10-23 coui 化：玻璃件（顶栏胶囊 / 取景环 / 提示卡 / 结果卡）全部保留，
 * 玻璃里的文字 / 返回图标 / 转圈 / 文字按钮换 coui 原生件，配色读 [COUITheme]。
 */
@Composable
fun ScanPage(
    fallbackSessionId: String?,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onOpenCodeCheckIn: (sessionId: String, code: String) -> Unit,
) {
    val cs = COUITheme.colorScheme
    val pageBackdrop = rememberGlassBackdrop()
    val scope = rememberCoroutineScope()

    // null = 申请中；true/false = 已授权 / 被拒
    var camGranted by remember { mutableStateOf<Boolean?>(null) }
    var state by remember { mutableStateOf<ScanState>(ScanState.Scanning) }
    // 最近一次扫到的会话 / 暗号，失败跳暗号签到页时回带
    var lastSessionId by remember { mutableStateOf<String?>(null) }
    var lastCode by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val act = platformActivity()
        if (act == null) camGranted = false else requestCameraPermission(act) { camGranted = it }
    }

    // 签到成功：展示约 1.5s 后返回首页
    LaunchedEffect(state) {
        val s = state
        if (s is ScanState.Success) {
            delay(1500)
            onDone()
        }
    }

    BackHandler { onBack() }

    fun submit(raw: String) {
        if (state != ScanState.Scanning) return
        haptic(HapticKind.Medium)
        val parsed = parseKewuSignUri(raw)
        if (parsed == null) {
            state = ScanState.Error("无法识别的二维码，请对准教师端展示的课悟签到码")
            return
        }
        state = ScanState.Submitting
        val (sid, code) = parsed
        lastSessionId = sid
        lastCode = code
        scope.launch {
            // 二维码里只有 sid：先轻查一次会话拿 courseId（接口会顺带写 8081 简易签到缓存）；
            // 查不到 / 超时就不传，不阻塞签到主流程。
            val courseId = withTimeoutOrNull(2_000L) { KewuApi.getAttendance(sid) }?.courseId?.ifBlank { null }
            val err = KewuApi.attendanceCheckIn(
                id = sid,
                studentId = KewuApi.username ?: "",
                studentName = KewuApi.nameForServer(),
                code = code.ifBlank { null },
                latitude = 0.0, longitude = 0.0,
                courseId = courseId,
            )
            state = if (err == null) ScanState.Success(sid) else ScanState.Error(err)
            // 2026-11-04（任务 #14）：签到成功自动开启课堂录音（幂等，没权限静默跳过）
            if (err == null) courseId?.let { RecordingManager.autoStartAfterCheckIn(it) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 背景层：玻璃采样源（与相机同级，玻璃只模糊这层，不采样相机画面——又稳又省电）
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(pageBackdrop)
                .background(cs.surface),
        )

        // 相机预览（授权后才挂；被拒 / 非 Android 平台是黑底）
        if (camGranted == true) {
            QrScannerView(
                onScanned = { raw -> submit(raw) },
                modifier = Modifier.matchParentSize(),
                enabled = state is ScanState.Scanning,
            )
        } else {
            Box(Modifier.matchParentSize().background(Color.Black))
        }

        // 四周压暗（4 块 scrim 天然留出中间亮区，不做 punch-hole 混合，避开 offscreen 层）
        ScanScrim(Modifier.matchParentSize())

        // 取景框 + 扫描线动画（屏幕中心）
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ScanFrame(pageBackdrop, Modifier.size(FrameSize))
        }

        // 顶栏：微信式 —— 暗遮罩上直接白字标题，左侧玻璃圆钮返回（不再用标题胶囊）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KewuGlassCard(
                backdrop = pageBackdrop,
                shape = CircleShape,
                blurRadius = 18.dp,
                tintAlpha = 0.32f,
                modifier = Modifier.size(42.dp),
                onClick = onBack,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(COUIIcons.Back, contentDescription = "返回", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("扫码签到", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    when (state) {
                        is ScanState.Submitting -> "正在签到…"
                        is ScanState.Success -> "签到成功"
                        else -> "将二维码放入框内，即可自动扫描"
                    },
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 12.sp,
                )
            }
        }

        // 底部提示 + 暗号签到兜底入口：微信式 —— 遮罩上直接白字小提示，暗号钮为白描边玻璃胶囊
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // 底部导航栏（托盘+标签行）约 116dp，抬高提示避免被压住
                .padding(horizontal = 24.dp)
                .padding(bottom = 132.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state is ScanState.Scanning) {
                Text(
                    "对准教师端展示的课悟签到码",
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
                val codeSid = fallbackSessionId
                if (codeSid != null) {
                    Spacer(Modifier.height(16.dp))
                    // ★ X11（2026-11-05）：改用 COUi 满宽主按钮，更统一干净
                    KewuPrimaryButton(
                        "输入暗号签到",
                        onClick = { onOpenCodeCheckIn(codeSid, "") },
                        modifier = Modifier.fillMaxWidth(0.7f),
                    )
                }
            }
        }

        // 中部结果卡：提交中 / 成功 / 失败
        when (val s = state) {
            is ScanState.Submitting -> ScanStatusCard(pageBackdrop) {
                InfiniteProgressIndicator(color = KewuGreen, size = 36.dp, strokeWidth = 3.dp)
                Spacer(Modifier.height(12.dp))
                Text("正在签到…", color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            is ScanState.Success -> ScanStatusCard(pageBackdrop) {
                SuccessCheck()
                Spacer(Modifier.height(12.dp))
                Text("签到成功", color = KewuGreen, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("即将返回首页", color = cs.onSurfaceSecondary, fontSize = 13.sp)
            }
            is ScanState.Error -> ScanStatusCard(pageBackdrop) {
                Text("签到失败", color = cs.error, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(s.message, color = cs.onSurfaceSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Row {
                    ScanTextAction("重试", primary = true) { state = ScanState.Scanning }
                    val codeSid = lastSessionId ?: fallbackSessionId
                    if (codeSid != null) {
                        Spacer(Modifier.width(12.dp))
                        ScanTextAction("输入暗号签到", primary = false) {
                            onOpenCodeCheckIn(codeSid, lastCode)
                        }
                    }
                }
            }
            else -> {}
        }

        // 权限被拒 / 申请中的兜底卡
        if (camGranted != true) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                KewuGlassCard(backdrop = pageBackdrop, modifier = Modifier.padding(horizontal = 40.dp)) {
                    Column(
                        Modifier.padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            if (camGranted == false) "需要相机权限才能扫码签到" else "正在申请相机权限…",
                            color = cs.onSurface,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                        if (camGranted == false) {
                            Spacer(Modifier.height(14.dp))
                            Row {
                                ScanTextAction("授权相机", primary = true) {
                                    val act = platformActivity()
                                    if (act == null) camGranted = false
                                    else requestCameraPermission(act) { camGranted = it }
                                }
                                val codeSid = lastSessionId ?: fallbackSessionId
                                if (codeSid != null) {
                                    Spacer(Modifier.width(12.dp))
                                    ScanTextAction("输入暗号签到", primary = false) {
                                        onOpenCodeCheckIn(codeSid, lastCode)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 结果卡容器：居中玻璃卡。 */
@Composable
private fun ScanStatusCard(backdrop: io.github.suqi8.coui.kmp.blur.LayerBackdrop, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        KewuGlassCard(backdrop = backdrop, modifier = Modifier.padding(horizontal = 48.dp)) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                content()
            }
        }
    }
}

/**
 * 玻璃卡里的操作按钮：coui 小号 [TextButton]（2026-10-23 由「纯文字 + clickable」改来）。
 * [primary] = 主色实心（重试 / 授权相机）；否则 coui 灰底次按钮（输入暗号签到）。
 */
@Composable
private fun ScanTextAction(label: String, primary: Boolean, onClick: () -> Unit) {
    TextButton(
        text = label,
        onClick = { haptic(HapticKind.Tick); onClick() },
        colors = if (primary) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
        minHeight = ButtonDefaults.MinHeightSmall,
        minWidth = ButtonDefaults.MinWidthSmall,
        insideMargin = ButtonDefaults.InsideMarginSmall,
    )
}

/** 成功对勾：绿圈 + 圆角对勾路径。 */
@Composable
private fun SuccessCheck() {
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

/**
 * 取景框（2026-11-04 P5 微信式重构，参考 Zhaoss/WeiXinRecordedDemo）：coui `KewuGlassCard` 做一圈玻璃环
 * （透过页面背景层、带折射与高光），**中心用 BlendMode.Clear 掏空**，让身后的相机
 * 预览透出来；玻璃之上画**白色细角括号**（微信扫码语言，克制不抢戏）+ 品牌绿扫描线。
 *
 * ★ 为什么是「掏空」而不是让玻璃直接显示相机：coui 的 backdrop 只能采样 Compose
 * 图层，相机是 SurfaceView/TextureView（在视图层级里是镂空的），采不进来；满铺玻璃会得到一片
 * 乳白，把取景框挡死。所以玻璃只做边框那一圈，中心必须透出相机。
 */
@Composable
/**
 * ★ X11（2026-11-05）：用 COUi 风格重做扫码取景框——
 * 圆角玻璃底（极淡）+ 品牌绿描边 + 加粗圆头四角括号（带内缩）+ 柔光扫描线，整体更干净。
 */
private fun ScanFrame(backdrop: LayerBackdrop, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "scan")
    val lineY by transition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
        label = "line",
    )
    val shape = RoundedCornerShape(24.dp)
    // 外层：品牌绿细描边定形
    Box(modifier.border(1.5.dp, KewuGreen.copy(alpha = 0.55f), shape)) {
        KewuGlassCard(
            backdrop = backdrop,
            shape = shape,
            blurRadius = 18.dp,
            tintAlpha = 0.12f, // 压得很淡：扫码要能看清框内画面，只提供"玻璃感"边界
            magnify = 1f, // 取景器不做透镜放大，否则框内二维码被拉畸变不利于识别
            modifier = Modifier.fillMaxSize(),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                // ① 中心掏空（玻璃环宽 8dp）： BlendMode.Clear 把玻璃卡中部擦透明，露出身后的相机预览
                val ring = 8.dp.toPx()
                drawRect(
                    color = Color.Transparent,
                    topLeft = Offset(ring, ring),
                    size = Size(w - ring * 2f, h - ring * 2f),
                    blendMode = BlendMode.Clear,
                )
                // ② 品牌绿四角括号：加粗、圆头、带内缩，更干净现代
                val inset = 10.dp.toPx()
                val cl = 46.dp.toPx()
                val t = 5.dp.toPx()
                val green = KewuGreen.copy(alpha = 0.95f)
                fun line(a: Offset, b: Offset) = drawLine(green, a, b, strokeWidth = t, cap = StrokeCap.Round)
                // 左上
                line(Offset(inset, inset + cl), Offset(inset, inset)); line(Offset(inset, inset), Offset(inset + cl, inset))
                // 右上
                line(Offset(w - inset - cl, inset), Offset(w - inset, inset)); line(Offset(w - inset, inset), Offset(w - inset, inset + cl))
                // 左下
                line(Offset(inset, h - inset - cl), Offset(inset, h - inset)); line(Offset(inset, h - inset), Offset(inset + cl, h - inset))
                // 右下
                line(Offset(w - inset - cl, h - inset), Offset(w - inset, h - inset)); line(Offset(w - inset, h - inset), Offset(w - inset, h - inset - cl))
                // ③ 扫描线：柔光带 + 主线，往返巡航
                val y = h * lineY
                val x1 = 22.dp.toPx()
                val x2 = w - 22.dp.toPx()
                drawLine(KewuGreen.copy(alpha = 0.16f), Offset(x1, y), Offset(x2, y), strokeWidth = 14.dp.toPx(), cap = StrokeCap.Round)
                drawLine(KewuGreen.copy(alpha = 0.95f), Offset(x1, y), Offset(x2, y), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}

/** 取景框四周的压暗层（上与下整块，左右两条，围出中间亮区；微信式略深，视觉聚焦框内）。 */
@Composable
private fun ScanScrim(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val f = FrameSize.toPx()
        val l = (w - f) / 2
        val tp = (h - f) / 2
        val dim = Color.Black.copy(alpha = 0.56f)
        drawRect(dim, Offset(0f, 0f), Size(w, tp))
        drawRect(dim, Offset(0f, tp + f), Size(w, h - tp - f))
        drawRect(dim, Offset(0f, tp), Size(l, f))
        drawRect(dim, Offset(l + f, tp), Size(w - l - f, f))
    }
}
