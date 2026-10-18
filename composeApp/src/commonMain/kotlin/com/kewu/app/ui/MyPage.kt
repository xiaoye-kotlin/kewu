package com.kewu.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.KewuGreen
import kotlinx.coroutines.delay
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop

// ───────────────────────── 我的页（tab 3） ─────────────────────────

/**
 * 「我的」页：顶部个人资料（头像 + 名字 + **学号** + 学校/班级），下方校园功能列表，最底退出登录。
 * 版式沿用 Mishka / 课堂页语言：squircle 卡、12dp 间距、卡内 16dp；资料头用 coui 毛玻璃，
 * 背景层按规定走 [MaterialTheme] 默认 colorScheme（不自造背景色）。
 *
 * 子界面（编辑资料 / 录音管理）用与课表页一致的「仅 slide、无 fade」共享轴切换 —— 避免给玻璃子树
 * 套 alpha 离屏层导致 coui 采样首帧 SIGSEGV。
 *
 * @param onLogout 「退出登录」→ 回启动屏（状态提升到 [App]，跨 tab 不丢）
 * @param onNavHide 滚动方向回调（向下收起底部导航栏）
 */
@Composable
fun MyPage(onLogout: () -> Unit, onNavHide: (Boolean) -> Unit) {
    var profile by remember { mutableStateOf(defaultProfile) }
    var screen by remember { mutableStateOf(0) } // 0=主页 1=编辑资料 2=录音管理

    val pageBackdrop = rememberGlassBackdrop()
    val scrollState = rememberScrollState()

    Box(Modifier.fillMaxSize()) {
        // 背景层：M3 语义色，是玻璃的采样源（采集层与玻璃平级 ✓）
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(pageBackdrop)
                .background(MaterialTheme.colorScheme.background),
        )
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                if (targetState > initialState) {
                    slideInHorizontally(tween<IntOffset>(durationMillis = 320, easing = FastOutSlowInEasing)) { w -> w } togetherWith
                        slideOutHorizontally(tween<IntOffset>(durationMillis = 320, easing = FastOutSlowInEasing)) { w -> -w / 5 }
                } else {
                    slideInHorizontally(tween<IntOffset>(durationMillis = 320, easing = FastOutSlowInEasing)) { w -> -w / 5 } togetherWith
                        slideOutHorizontally(tween<IntOffset>(durationMillis = 320, easing = FastOutSlowInEasing)) { w -> w }
                }
            },
            modifier = Modifier.fillMaxSize(),
            label = "myPageNav",
        ) { s ->
            when (s) {
                0 -> MyHome(profile, pageBackdrop, scrollState, onNavHide,
                    onEdit = { screen = 1 },
                    onRecording = { screen = 2 },
                    onLogout = onLogout,
                )
                1 -> EditProfilePage(
                    profile, pageBackdrop,
                    onBack = { screen = 0 },
                    onSave = { profile = it; screen = 0 },
                )
                2 -> RecordingManagePage(pageBackdrop, onBack = { screen = 0 })
            }
        }
    }
}

// ───────────────────────── 主页：资料头 + 功能列表 ─────────────────────────

/**
 * 仿 MIUI / coui 的错峰进场：元素从下方上浮归位，按 [index] 级联。
 * - 玻璃卡（[glass]=true）：只做 placement 位移，**不**叠 alpha/graphicsLayer（coui 采样者被包裹
 *   有首帧 SIGSEGV 风险）；
 * - 普通元素：位移 + alpha 淡入，观感是「内容逐层落位」。
 * 首次组合触发（切到「我的」tab 时 MyHome 重新挂载即播放）。
 */
@Composable
private fun Modifier.myEnter(index: Int, glass: Boolean = false): Modifier {
    var target by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) { delay((index * 55L).coerceAtMost(520L)); target = 1f }
    val p by animateFloatAsState(
        // 低阻尼弹簧 → 落位时轻微过冲再回弹（用户要求的「有回弹」手感）
        targetValue = target,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 380f),
        label = "myEnter$index",
    )
    return if (glass) {
        this.offset { IntOffset(0, ((1f - p) * 26f).dp.roundToPx()) }
    } else {
        this.offset { IntOffset(0, ((1f - p) * 26f).dp.roundToPx()) }.alpha(p.coerceIn(0f, 1f))
    }
}

@Composable
private fun MyHome(
    profile: UserProfile,
    backdrop: LayerBackdrop,
    scrollState: androidx.compose.foundation.ScrollState,
    onNavHide: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRecording: () -> Unit,
    onLogout: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(scrollState) {
        var last = 0
        snapshotFlow { scrollState.value }.collect { cur ->
            if (cur <= 0) onNavHide(false)
            else {
                val d = cur - last
                if (d > 6) onNavHide(true) else if (d < -6) onNavHide(false)
            }
            last = cur
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(bottom = 96.dp),
    ) {
        Spacer(Modifier.height(12.dp))

        // 资料头：液态玻璃大卡，头像 + 名字 + 学号 + 学校/班级 + 编辑入口
        KewuGlassCard(
            backdrop = backdrop,
            shape = RoundedCornerShape(20.dp),
            blurRadius = 22.dp,
            tintAlpha = 0.30f,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp)
                .myEnter(0, glass = true),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 大头像：初始字 + 主色容器
                Box(
                    Modifier
                        .size(62.dp)
                        .clip(CircleShape)
                        .background(cs.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(profile.name.first().toString(), color = cs.onPrimaryContainer,
                        fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile.name, color = cs.onSurface, fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    // ★ 学号：用胶囊标签强提示（必显字段，文档明确要求）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(cs.secondaryContainer)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text("学号", color = cs.onSecondaryContainer, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.width(7.dp))
                        Text(profile.studentId, color = cs.onSurface, fontSize = 14.sp,
                            fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp, maxLines = 1)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("${profile.school} · ${profile.className}", color = cs.onSurfaceVariant,
                        fontSize = 13.sp, maxLines = 1)
                }
                // 编辑入口：铅笔图标（轻提示「资料可改」）
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(cs.surfaceVariant)
                        .clickable { onEdit() },
                    contentAlignment = Alignment.Center,
                ) { EditGlyph(cs.onSurfaceVariant, 18.dp) }
            }
        }

        // 分组标题
        GroupLabel("校园功能", Modifier.myEnter(1))
        // 功能列表（squircle 行，可点进子页 / 留作占位）
        FuncRow(0, "课程绑定", "绑定课表，按时自动录音", backdrop, onClick = onEdit /* 占位：接课表绑定 */, modifier = Modifier.myEnter(2))
        FuncRow(1, "录音管理", "查看本地录音、手动重传", backdrop, onClick = onRecording, modifier = Modifier.myEnter(3))
        FuncRow(2, "通知设置", "签到提醒、纪要推送", backdrop, onClick = {}, modifier = Modifier.myEnter(4))
        FuncRow(3, "关于我们", "课悟 · 基于华为云的多端协同 AI 课堂", backdrop, onClick = {}, modifier = Modifier.myEnter(5))

        Spacer(Modifier.height(14.dp))
        GroupLabel("账号", Modifier.myEnter(6))
        // 退出登录：玻璃按钮 + 错误色文字（不造背景色，用 error 语义）
        KewuGlassCard(
            backdrop = backdrop,
            shape = RoundedCornerShape(16.dp),
            blurRadius = 18.dp,
            tintAlpha = 0.26f,
            onClick = onLogout,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .myEnter(7, glass = true),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 15.dp, horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfileGlyph(4, cs.error, 22.dp)
                Spacer(Modifier.width(14.dp))
                Text("退出登录", color = cs.error, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

/** Mishka 风格功能行：图标 + 标题 + 副标题 + 右侧箭头（›）。点击整行。 */
@Composable
private fun FuncRow(
    glyph: Int,
    title: String,
    subtitle: String,
    backdrop: LayerBackdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 10.dp)
            .squircleCard(16.dp, cs.surfaceVariant)
            .clickable { onClick() }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfileGlyph(glyph, cs.onSurface, 22.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = cs.onSurfaceVariant, fontSize = 12.5.sp, maxLines = 1)
            }
            Canvas(Modifier.size(13.dp)) {
                val sw = size.width * 0.14f
                val p = Path().apply {
                    moveTo(size.width * 0.35f, size.height * 0.2f)
                    lineTo(size.width * 0.65f, size.height * 0.5f)
                    lineTo(size.width * 0.35f, size.height * 0.8f)
                }
                drawPath(p, cs.onSurfaceVariant, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

// ───────────────────────── 编辑资料页 ─────────────────────────

/**
 * 修改个人信息：头像 + 名字 + 学号 + 学校/班级 + 手机 + 邮箱，逐行可编辑。
 * 顶部返回（←）+ 标题「编辑资料」+ 右侧「保存」玻璃按钮。保存后写回 [MyPage] 的状态。
 */
@Composable
private fun EditProfilePage(
    profile: UserProfile,
    backdrop: LayerBackdrop,
    onBack: () -> Unit,
    onSave: (UserProfile) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var name by remember { mutableStateOf(profile.name) }
    var studentId by remember { mutableStateOf(profile.studentId) }
    var school by remember { mutableStateOf(profile.school) }
    var className by remember { mutableStateOf(profile.className) }
    var phone by remember { mutableStateOf(profile.phone) }
    var email by remember { mutableStateOf(profile.email) }
    val scrollState = rememberScrollState()

    Column(Modifier.fillMaxSize()) {
        // 顶栏：返回 + 标题 + 保存
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(cs.surfaceVariant)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) { BackGlyph(cs.onSurface, 18.dp) }
            Spacer(Modifier.width(10.dp))
            Text("编辑资料", color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            KewuGlassCard(
                backdrop = backdrop,
                shape = RoundedCornerShape(14.dp),
                blurRadius = 16.dp,
                tintAlpha = 0.3f,
                onClick = {
                    onSave(
                        profile.copy(
                            name = name.trim().ifEmpty { profile.name },
                            studentId = studentId.trim(),
                            school = school.trim(),
                            className = className.trim(),
                            phone = phone.trim(),
                            email = email.trim(),
                        ),
                    )
                },
            ) {
                Text("保存", color = cs.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp))
            }
        }

        Column(
            Modifier.fillMaxWidth().verticalScroll(scrollState).padding(bottom = 40.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            // 头像（编辑页只读预览，真实可换图留接口）
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(cs.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(name.firstOrNull()?.toString() ?: "我", color = cs.onPrimaryContainer,
                        fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(16.dp))
            EditField("姓名", name) { name = it }
            // ★ 学号单独成行并强提示，且输入为数字
            EditField("学号", studentId, keyboardNum = true) { studentId = it }
            EditField("学校", school) { school = it }
            EditField("班级", className) { className = it }
            EditField("手机号", phone) { phone = it }
            EditField("邮箱", email) { email = it }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 单行编辑字段：标签 + 圆角输入框（M3 surfaceVariant 底）。 */
@Composable
private fun EditField(
    label: String,
    value: String,
    keyboardNum: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp),
    ) {
        Text(label, color = cs.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(cs.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            // 用基础 TextField 行为：这里用 Text 占位 + 真实输入交给 BasicTextField，
            // 保持不引入额外依赖。EditorStyle 用 M3 语义色。
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (keyboardNum) androidx.compose.ui.text.input.KeyboardType.Number else androidx.compose.ui.text.input.KeyboardType.Text,
                ),
                textStyle = androidx.compose.ui.text.TextStyle(color = cs.onSurface, fontSize = 15.sp),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (value.isEmpty()) {
                            Text(label, color = cs.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 15.sp)
                        }
                        inner()
                    }
                },
            )
        }
    }
}

// ───────────────────────── 录音管理页 ─────────────────────────

private data class RecordingItem(
    val course: String,
    val date: String,
    val sizeKb: Int,
    val status: String, // 已上传 / 上传中 / 失败
    val devices: Int,
)

private fun mockRecordings(): List<RecordingItem> = listOf(
    RecordingItem("高等数学", "今天 14:00", 38_400, "上传中", 3),
    RecordingItem("大学英语", "今天 10:00", 31_200, "已上传", 3),
    RecordingItem("线性代数", "今天 08:00", 35_800, "已上传", 3),
    RecordingItem("程序设计", "昨天 15:30", 12_600, "失败", 1),
    RecordingItem("大学物理", "昨天 10:00", 29_400, "已上传", 2),
)

/**
 * 录音管理详情（文档 9.4）：列表显示每节课的录音文件、大小、上传状态；
 * 失败项提供「重试」手动重传。玻璃头 + squircle 行。
 */
@Composable
private fun RecordingManagePage(backdrop: LayerBackdrop, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var items by remember { mutableStateOf(mockRecordings()) }
    val scrollState = rememberScrollState()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(cs.surfaceVariant)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) { BackGlyph(cs.onSurface, 18.dp) }
            Spacer(Modifier.width(10.dp))
            Text("录音管理", color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(
            Modifier.fillMaxWidth().verticalScroll(scrollState).padding(bottom = 40.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            // 概览玻璃头：总录音数 + 待重传数
            val failed = items.count { it.status == "失败" }
            KewuGlassCard(
                backdrop = backdrop,
                shape = RoundedCornerShape(16.dp),
                blurRadius = 20.dp,
                tintAlpha = 0.30f,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("本地录音", color = cs.onSurfaceVariant, fontSize = 13.sp)
                        Spacer(Modifier.height(2.dp))
                        Text("${items.size} 份", color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("待重传", color = cs.onSurfaceVariant, fontSize = 13.sp)
                        Spacer(Modifier.height(2.dp))
                        Text("$failed 份", color = if (failed > 0) cs.error else KewuGreen,
                            fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            items.forEachIndexed { i, it ->
                RecordingRow(it) { items = items.toMutableList().also { l -> l[i] = it.copy(status = "上传中") } }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun RecordingRow(item: RecordingItem, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (item.status) {
        "已上传" -> cs.primaryContainer to cs.onPrimaryContainer
        "上传中" -> cs.secondaryContainer to cs.onSecondaryContainer
        else -> cs.errorContainer to cs.onErrorContainer
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 10.dp)
            .squircleCard(16.dp, cs.surfaceVariant)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.course, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Spacer(Modifier.height(3.dp))
                Text("${item.date} · ${item.sizeKb / 1024} MB · ${item.devices} 台设备",
                    color = cs.onSurfaceVariant, fontSize = 12.5.sp, maxLines = 1)
                Spacer(Modifier.height(7.dp))
                StatusTag(item.status, bg, fg)
            }
            if (item.status == "失败") {
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(cs.error)
                        .clickable { onRetry() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text("重试", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ───────────────────────── 小图标：返回 / 编辑(铅笔) ─────────────────────────

@Composable
private fun BackGlyph(color: Color, sizeDp: Dp) {
    Canvas(Modifier.size(sizeDp)) {
        val s = size.width
        val sw = s * 0.13f
        val p = Path().apply {
            moveTo(s * 0.62f, s * 0.2f)
            lineTo(s * 0.35f, s * 0.5f)
            lineTo(s * 0.62f, s * 0.8f)
        }
        drawPath(p, color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun EditGlyph(color: Color, sizeDp: Dp) {
    Canvas(Modifier.size(sizeDp)) {
        val s = size.width
        val sw = s * 0.11f
        // 铅笔：斜笔杆 + 尖
        val body = Path().apply {
            moveTo(s * 0.62f, s * 0.20f)
            lineTo(s * 0.82f, s * 0.40f)
            lineTo(s * 0.40f, s * 0.82f)
            lineTo(s * 0.22f, s * 0.82f)
            lineTo(s * 0.22f, s * 0.64f)
            close()
        }
        drawPath(body, color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(color, Offset(s * 0.40f, s * 0.82f),
            Offset(s * 0.58f, s * 0.82f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}
