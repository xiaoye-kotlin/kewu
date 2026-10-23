package com.kewu.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.AttendanceCode
import com.kewu.app.AttendanceRealtime
import com.kewu.app.AttendanceRisk
import com.kewu.app.AttendanceSession
import com.kewu.app.AttendanceStats
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuBlue
import com.kewu.app.KewuGreen
import com.kewu.app.haptic
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.ProgressIndicatorDefaults
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Mic
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ───────────────────────── 签到 Hub ─────────────────────────

/**
 * 签到 · 详情页（学生 + 老师通用）。
 *
 * 接入的 API（全部来自 [KewuApi]）：
 * - `getAttendance` 签到会话元信息（含防代签问答 quiz）
 * - `getAttendanceCode` 动态码（含 qrContent / 剩余秒数，可刷新）
 * - `attendanceCheckIn` 签到（支持动态码 / 问答答案；带 courseId 时顺带同步 8081 录音端到场状态）
 * - `getMyAttendance` 我的签到记录
 * - `getAttendanceRecords` 全部签到记录
 * - `getAttendanceStats` 签到统计
 * - `getAttendanceRisk` 风险记录
 * - `getAttendanceRealtime` 实时签到概览
 * - `getCheckins(courseId)` 8081 录音端已同步的到场名单（教师统计区显示人数，失败不显示）
 * - `closeAttendance` 老师结束签到
 *
 * 动态码用自绘二维码渲染（见 [QrCodeView]）：取 `qrContent`（缺省退回 `code`）生成可扫二维码，
 * 30s 刷新一次防代签；下方同时保留大字暗号与倒计时，方便 CODE 模式手填。
 *
 * 配色走 [COUITheme] 语义色；头部保留课悟液态玻璃，其余分段卡一律 coui [Card]（2026-10-23 全局 coui 化）。
 */
@Composable
fun AttendanceDetailScreen(
    attendanceId: String,
    studentId: String,
    studentName: String,
    onBack: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    val pageBackdrop = rememberGlassBackdrop()
    val scrollState = rememberScrollState()

    var session by remember { mutableStateOf<AttendanceSession?>(null) }
    var code by remember { mutableStateOf<AttendanceCode?>(null) }
    var myRecord by remember { mutableStateOf<com.kewu.app.AttendanceRecord?>(null) }
    var records by remember { mutableStateOf<List<com.kewu.app.AttendanceRecord>?>(null) }
    var stats by remember { mutableStateOf<AttendanceStats?>(null) }
    var risk by remember { mutableStateOf<List<AttendanceRisk>?>(null) }
    var realtime by remember { mutableStateOf<AttendanceRealtime?>(null) }
    var syncedCount by remember { mutableStateOf<Int?>(null) } // 8081 录音端已同步到场人数；null = 未取到 / 失败
    var loading by remember { mutableStateOf(true) }
    var checking by remember { mutableStateOf(false) }
    var checkMsg by remember { mutableStateOf<String?>(null) }
    var codeInput by remember { mutableStateOf("") }
    var answerInput by remember { mutableStateOf("") }
    var remaining by remember { mutableStateOf(0) }

    // 身份区分：后端暂无角色字段（接口文档：现阶段用参数区分身份），
    // 用「签到发布者 == 当前用户」判定教师视角（UI 门控与录音端人数拉取共用这一判据）。
    fun teacherOf(s: AttendanceSession?): Boolean {
        val meName = KewuApi.currentUser?.user?.nickname ?: KewuApi.username
        return s != null && s.publisher.isNotBlank() && meName != null && s.publisher == meName
    }

    // 录音端到场人数：只教师视角拉（学生用不到），课程 id 缺失或接口失败 → null 不显示
    suspend fun refreshSynced() {
        val s = session
        syncedCount = if (teacherOf(s) && s!!.courseId.isNotBlank()) KewuApi.getCheckins(s.courseId)?.size else null
    }

    fun reload() {
        loading = true
        scope.launch {
            session = KewuApi.getAttendance(attendanceId)
            code = KewuApi.getAttendanceCode(attendanceId)
            remaining = code?.remainingSeconds ?: 0
            myRecord = if (studentId.isNotEmpty()) KewuApi.getMyAttendance(attendanceId, studentId) else null
            records = KewuApi.getAttendanceRecords(attendanceId)
            stats = KewuApi.getAttendanceStats(attendanceId)
            risk = KewuApi.getAttendanceRisk(attendanceId)
            realtime = KewuApi.getAttendanceRealtime(attendanceId)
            refreshSynced()
            loading = false
        }
    }
    LaunchedEffect(attendanceId) { reload() }

    // 动态码倒计时（本地 tick；归零后自动刷新一次，录音端到场人数随这一轮一起刷新）
    LaunchedEffect(code?.sessionId) {
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
            if (remaining <= 0) {
                scope.launch {
                    code = KewuApi.getAttendanceCode(attendanceId)?.also { remaining = it.remainingSeconds }
                    refreshSynced()
                }
            }
        }
    }

    fun doCheckIn(useCode: Boolean) {
        checking = true
        checkMsg = null
        scope.launch {
            val err = KewuApi.attendanceCheckIn(
                id = attendanceId,
                studentId = studentId,
                studentName = studentName,
                code = if (useCode) codeInput.ifBlank { null } else null,
                answer = session?.quiz?.let { if (answerInput.isNotBlank()) answerInput else null },
                latitude = 0.0, longitude = 0.0,
                // 带上课程 id：签到成功后顺带写 8081 录音端到场名单（教师端「录音端已同步」据此计数）
                courseId = session?.courseId,
            )
            checking = false
            checkMsg = if (err == null) "签到成功" else err
            if (err == null) reload()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(pageBackdrop)
                .background(cs.surface),
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 96.dp),
        ) {
            HubTopBar(session?.title ?: "签到详情", onBack)
            Spacer(Modifier.height(4.dp))

            if (loading) {
                KewuLoading()
            } else {
                session?.let { s ->
                    // 头部玻璃卡
                    KewuGlassCard(
                        backdrop = pageBackdrop, shape = RoundedCornerShape(20.dp),
                        blurRadius = 22.dp, tintAlpha = 0.28f,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Text(s.title, color = cs.onSurface, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            MetaLine("方式", s.mode)
                            MetaLine("状态", s.state)
                            MetaLine("发布人", s.publisher.ifBlank { "—" })
                            MetaLine("截止", s.expireAt.ifBlank { "—" })
                            MetaLine("迟到阈值", "${s.lateAfterSeconds}s")
                        }
                    }
                    Spacer(Modifier.height(14.dp))

                    // 教师看二维码展示 / 统计 / 监控 / 结束签到，
                    // 学生只看自己的签到状态与签到动作，**看不到二维码界面**。
                    val isTeacher = teacherOf(s)

                    if (!isTeacher) {
                        // 学生：我的签到状态 / 签到动作
                        HubSectionLabel("我的签到")
                        MyCheckInCard(
                            record = myRecord,
                            quiz = s.quiz,
                            checking = checking,
                            checkMsg = checkMsg,
                            codeInput = codeInput, onCodeChange = { codeInput = it },
                            answerInput = answerInput, onAnswerChange = { answerInput = it },
                            onCheckInWithCode = { doCheckIn(true) },
                            onCheckInPlain = { doCheckIn(false) },
                        )
                        Spacer(Modifier.height(14.dp))
                    }

                    if (isTeacher) {
                        // 教师端：二维码展示（学生端不显示）
                        HubSectionLabel("二维码签到")
                        DynamicCodeCard(
                            code = code, remaining = remaining,
                            onRefresh = {
                                scope.launch {
                                    code = KewuApi.getAttendanceCode(attendanceId)?.also { remaining = it.remainingSeconds }
                                    refreshSynced()
                                }
                            },
                        )
                        Spacer(Modifier.height(14.dp))

                        // 统计
                        HubSectionLabel("签到统计")
                        StatsCard(stats = stats, syncedCount = syncedCount)
                        Spacer(Modifier.height(14.dp))

                        // 实时概览
                        HubSectionLabel("实时概览")
                        RealtimeCard(realtime = realtime)
                        Spacer(Modifier.height(14.dp))

                        // 风险记录
                        HubSectionLabel("风险记录")
                        RiskCard(risk = risk)
                        Spacer(Modifier.height(14.dp))

                        // 全部记录
                        HubSectionLabel("签到记录")
                        RecordsCard(records = records, myId = studentId)
                        Spacer(Modifier.height(14.dp))

                        // 老师：结束签到
                        TeacherCloseCard(publisher = s.publisher) {
                            scope.launch {
                                KewuApi.closeAttendance(attendanceId)
                                reload()
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                } ?: EmptyHintRow("未找到该签到")
            }
        }
    }
}

/**
 * 签到页段内白卡：coui [Card]（16dp 圆角 / 卡内 16dp），左右 12dp。
 * [onClick] 非空时整卡可点（coui 按压 Tint）。
 */
@Composable
private fun AttCard(
    onClick: (() -> Unit)? = null,
    horizontalCenter: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val m = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
    val body: @Composable ColumnScope.() -> Unit = {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = if (horizontalCenter) Alignment.CenterHorizontally else Alignment.Start,
            content = content,
        )
    }
    if (onClick != null) {
        Card(
            modifier = m, cornerRadius = 16.dp, insideMargin = PaddingValues(16.dp),
            colors = kewuCardColors(), onClick = { haptic(HapticKind.Tick); onClick() }, content = body,
        )
    } else {
        Card(modifier = m, cornerRadius = 16.dp, insideMargin = PaddingValues(16.dp), colors = kewuCardColors(), content = body)
    }
}

@Composable
private fun MetaLine(label: String, value: String) {
    val cs = COUITheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = cs.onSurfaceSecondary, fontSize = 13.sp, modifier = Modifier.width(72.dp))
        Text(value, color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** 整块空态（coui 白卡 + 次级文字，经 [KewuEmpty]）。 */
@Composable
private fun EmptyHintRow(text: String) {
    KewuEmpty(text)
}

@Composable
private fun MyCheckInCard(
    record: com.kewu.app.AttendanceRecord?,
    quiz: com.kewu.app.AttendanceQuiz?,
    checking: Boolean,
    checkMsg: String?,
    codeInput: String,
    onCodeChange: (String) -> Unit,
    answerInput: String,
    onAnswerChange: (String) -> Unit,
    onCheckInWithCode: () -> Unit,
    onCheckInPlain: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    AttCard {
        if (record != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已签到", color = KewuGreen, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(record.status, color = cs.onSurfaceSecondary, fontSize = 13.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text("方式 ${record.method} · 用时 ${record.secondsAfterStart}s", color = cs.onSurfaceSecondary, fontSize = 13.sp)
            if (record.riskScore > 0) {
                Spacer(Modifier.height(4.dp))
                Text("风险分 ${record.riskScore}：${record.riskReasons.joinToString("；")}", color = cs.error, fontSize = 12.sp)
            }
        } else {
            Text("尚未签到", color = cs.onSurfaceSecondary, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            TextField(
                value = codeInput, onValueChange = onCodeChange,
                modifier = Modifier.fillMaxWidth(), backgroundMode = TextFieldMode.Rectangle,
                singleLine = true, label = "动态码（可选）",
            )
            quiz?.let { q ->
                Spacer(Modifier.height(8.dp))
                Text("防代签问答：${q.question}", color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                q.hint.takeIf { it.isNotBlank() }?.let { Text("提示：$it", color = cs.onSurfaceSecondary, fontSize = 12.sp) }
                Spacer(Modifier.height(6.dp))
                TextField(
                    value = answerInput, onValueChange = onAnswerChange,
                    modifier = Modifier.fillMaxWidth(), backgroundMode = TextFieldMode.Rectangle,
                    singleLine = true, label = "问答答案",
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KewuPrimaryButton(
                    if (checking) "签到中…" else "码签到", onCheckInWithCode,
                    Modifier.weight(1f), enabled = !checking,
                )
                KewuSecondaryButton("一键签到", onCheckInPlain, Modifier.weight(1f), enabled = !checking)
            }
            checkMsg?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = if (it.contains("成功")) KewuGreen else cs.error, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun DynamicCodeCard(code: AttendanceCode?, remaining: Int, onRefresh: () -> Unit) {
    val cs = COUITheme.colorScheme
    AttCard(onClick = onRefresh, horizontalCenter = true) {
        // 自绘二维码（白底保证对比度、可扫），编码 qrContent（缺省退回 code）—— 绘制保留
        val qrContent = code?.qrContent?.takeIf { it.isNotBlank() } ?: code?.code?.takeIf { it.isNotBlank() }
        if (qrContent != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .padding(14.dp),
            ) {
                QrCodeView(text = qrContent, modifier = Modifier.size(190.dp), dark = Color.Black, light = Color.White)
            }
            Spacer(Modifier.height(12.dp))
        }
        Text("暗号", color = cs.onSurfaceSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            code?.code ?: "—",
            color = KewuBlue, fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text("${remaining}s 后刷新 · 点击卡片刷新", color = cs.onSurfaceSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun StatsCard(stats: AttendanceStats?, syncedCount: Int?) {
    val cs = COUITheme.colorScheme
    AttCard {
        if (stats == null) {
            EmptyHint("暂无统计")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("应到", "${stats.expected}", cs.secondaryContainer, cs.onSurface)
                MiniStat("实到", "${stats.checked}", cs.tertiaryContainer, cs.onTertiaryContainer)
                MiniStat("迟到", "${stats.late}", cs.secondaryContainer, cs.onSecondaryVariant)
                MiniStat("缺勤", "${stats.absent}", if (stats.absent > 0) cs.errorContainer else cs.secondaryContainer,
                    if (stats.absent > 0) cs.onErrorContainer else cs.onSurface)
            }
            Spacer(Modifier.height(12.dp))
            RateBar("签到率", stats.checkRate.toFloat())
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("签到率", "${(stats.checkRate * 100).toInt()}%", cs.secondaryContainer, cs.onSurface)
                MiniStat("平均用时", "${stats.avgSeconds.toInt()}s", cs.secondaryContainer, cs.onSurface)
            }
        }
        // 8081 录音端同步人数：与主统计无关的另一路数据，统计为空时也照样显示；取不到就整行不出现
        syncedCount?.let { n ->
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(COUIIcons.Mic, contentDescription = null, tint = cs.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("录音端已同步到场 $n 人", color = cs.onSurfaceSecondary, fontSize = 13.sp)
            }
        }
    }
}

/** 比率条：标签 + coui [LinearProgressIndicator] + 百分比。 */
@Composable
private fun RateBar(label: String, rate: Float) {
    val cs = COUITheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = cs.onSurfaceSecondary, fontSize = 12.sp, modifier = Modifier.width(48.dp))
        LinearProgressIndicator(
            progress = rate.coerceIn(0f, 1f),
            modifier = Modifier.weight(1f),
            colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = KewuGreen),
            height = 8.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text("${(rate * 100).toInt()}%", color = cs.onSurface, fontSize = 12.sp, modifier = Modifier.width(36.dp))
    }
}

@Composable
private fun RealtimeCard(realtime: AttendanceRealtime?) {
    val cs = COUITheme.colorScheme
    AttCard {
        if (realtime == null) { EmptyHint("暂无实时数据"); return@AttCard }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniStat("已签", "${realtime.checked}", cs.tertiaryContainer, cs.onTertiaryContainer)
            MiniStat("应到", "${realtime.expected}", cs.secondaryContainer, cs.onSurface)
            MiniStat("签到率", "${(realtime.checkRate * 100).toInt()}%", cs.secondaryContainer, cs.onSurface)
        }
        Spacer(Modifier.height(10.dp))
        RateBar("进度", realtime.checkRate.toFloat())
        Spacer(Modifier.height(8.dp))
        Text("已用时 ${realtime.elapsedSeconds}s", color = cs.onSurfaceSecondary, fontSize = 12.sp)
        if (realtime.latest.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            realtime.latest.take(5).forEach { it2 ->
                Text("· ${it2.studentName}（${it2.secondsAfterStart}s）", color = cs.onSurfaceSecondary, fontSize = 12.5.sp)
            }
        }
    }
}

@Composable
private fun RiskCard(risk: List<AttendanceRisk>?) {
    val cs = COUITheme.colorScheme
    AttCard {
        if (risk.isNullOrEmpty()) { EmptyHint("无风险记录"); return@AttCard }
        risk.forEach { r ->
            Text("${r.studentName} · 风险分 ${r.riskScore}", color = cs.error, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (r.reasons.isNotEmpty()) Text(r.reasons.joinToString("；"), color = cs.onSurfaceSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun RecordsCard(records: List<com.kewu.app.AttendanceRecord>?, myId: String) {
    val cs = COUITheme.colorScheme
    AttCard {
        if (records.isNullOrEmpty()) { EmptyHint("暂无记录"); return@AttCard }
        records.forEach { r ->
            val me = r.studentId == myId
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("${r.studentName}${if (me) "（我）" else ""}", color = if (me) cs.onSurface else cs.onSurfaceSecondary,
                        fontSize = 14.sp, fontWeight = if (me) FontWeight.SemiBold else FontWeight.Normal)
                    Text("${r.status} · ${r.method} · ${r.secondsAfterStart}s", color = cs.onSurfaceSecondary, fontSize = 12.sp)
                }
                if (r.riskScore > 0) KewuTag("风险${r.riskScore}", cs.errorContainer, cs.onErrorContainer)
            }
        }
    }
}

@Composable
private fun TeacherCloseCard(publisher: String, onClose: () -> Unit) {
    val cs = COUITheme.colorScheme
    AttCard {
        Text("老师操作（发布人 $publisher）", color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        KewuPrimaryButton("结束签到", onClose, Modifier.fillMaxWidth(), danger = true)
    }
}

/**
 * 统计小块：大字数值 + 小字标签。
 * ★ 参数顺序修正为 (label, value)：旧签名是 (value, label) 而调用方一直按 (标签, 数值) 传，大字小字颠倒。
 */
@Composable
private fun RowScope.MiniStat(label: String, value: String, background: Color, content: Color) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(background).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = content, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(label, color = content.copy(alpha = 0.75f), fontSize = 11.sp, maxLines = 1)
    }
}

/** 卡内空态文字（卡外整块空态用 [KewuEmpty]）。 */
@Composable
private fun EmptyHint(text: String) {
    Text(
        text, color = COUITheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
