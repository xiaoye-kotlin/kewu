package com.kewu.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.Analytics
import com.kewu.app.AssignmentInfo
import com.kewu.app.AssignmentStats
import com.kewu.app.AttendanceRisk
import com.kewu.app.AttendanceStats
import com.kewu.app.CourseInfo
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuBlue
import com.kewu.app.KewuGreen
import com.kewu.app.haptic
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.defaultKewuHighlight
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ───────────────────────── 教师端 · 助教（tab 1，AI 学情对话页） ─────────────────────────
//
// 形态与学生端 TaPage 同款：**AI 对话页**，进入时小悟主动连发教学简报（不直接堆卡片）：
//   ① 今日概览（课程/作业/关注人数/签到/作业质量）→ ② 重点关注学生名单 → ③ 高频疑问 + 建议行动。
// 数据来自 KewuApi 现有接口交叉计算（无专用画像接口；无数据时降级 mock 并标注「示例」）。
// 教师可随时在底部输入框提问，AI 先用本地规则 mock 回答（按关键词匹配学情数据），
// 后端问答接口就绪后替换 answer() 实现即可（接入点已标注）。
//
// 组件语言与学生端小悟一致：AI coui 毛玻璃气泡（标签行+内容块）/ 用户 M3 primary 气泡 /
// 液态玻璃输入框常驻导航栏上方（bottom 118dp 兜底 + 根 imePadding 随键盘上移）。

private data class WatchStudent(val name: String, val reason: String, val kind: Int) // 0=退步 1=风险 2=关注

/** AI 消息块：文本 / 重点学生列表 / 标签行，拼在一条 AI 气泡里。 */
private sealed interface TaBlock {
    data class Text(val s: String, val emphasis: Boolean = false) : TaBlock
    data class Students(val list: List<WatchStudent>) : TaBlock
    data class Chips(val list: List<String>) : TaBlock
}

private data class TaMsg(val isUser: Boolean, val blocks: List<TaBlock>)

@Composable
fun TeacherAssistantPage(onNavHide: (Boolean) -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    val pageBackdrop = rememberGlassBackdrop()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var dataReady by remember { mutableStateOf(false) }

    // ── 学情数据（全部带兜底；问答与主动简报共用） ──
    var teacherName by remember { mutableStateOf("老师") }
    var courseCount by remember { mutableStateOf(0) }
    var assignmentCount by remember { mutableStateOf(0) }
    var weakPoints by remember { mutableStateOf<List<String>>(emptyList()) }
    var mastery by remember { mutableStateOf<List<com.kewu.app.KnowledgeMastery>>(emptyList()) }
    var watchList by remember { mutableStateOf<List<WatchStudent>>(emptyList()) }
    var watchFromMock by remember { mutableStateOf(false) }
    var attStats by remember { mutableStateOf<AttendanceStats?>(null) }
    var hwStats by remember { mutableStateOf<AssignmentStats?>(null) }

    val messages = remember { mutableListOf<TaMsg>() }
    var msgVersion by remember { mutableStateOf(0) }
    fun push(msg: TaMsg) {
        messages += msg
        msgVersion++ // 触发重组（LazyColumn items 读取 list 快照）
    }

    LaunchedEffect(Unit) {
        if (KewuApi.currentUser == null) KewuApi.me()
        teacherName = KewuApi.currentUser?.user?.nickname ?: KewuApi.username ?: "老师"
        val courses: List<CourseInfo>? =
            KewuApi.currentUser?.courses?.takeIf { it.isNotEmpty() } ?: KewuApi.getCourses()
        courseCount = courses?.size ?: 0
        val assignments: List<AssignmentInfo>? = KewuApi.getAssignments(courses?.firstOrNull()?.id)
        assignmentCount = assignments?.size ?: 0
        val analytics: Analytics? = courses?.firstOrNull()?.let { KewuApi.getAnalytics(it.id) }
        weakPoints = analytics?.weakPoints.orEmpty()
        mastery = analytics?.knowledgeMastery.orEmpty()

        // 重点关注：批阅队列（低分/高风险）+ 签到风险 交叉
        val watch = mutableListOf<WatchStudent>()
        courses?.firstOrNull()?.let { cid ->
            KewuApi.getAttendances(cid.id)?.lastOrNull()?.let { last ->
                attStats = KewuApi.getAttendanceStats(last.id)
                KewuApi.getAttendanceRisk(last.id)?.filter { it.riskScore >= 60 }?.forEach { r: AttendanceRisk ->
                    watch += WatchStudent(r.studentName,
                        r.reasons.joinToString("；").ifBlank { "签到风险分 ${r.riskScore}" }, 1)
                }
            }
            assignments?.firstOrNull()?.let { a ->
                hwStats = KewuApi.getAssignmentStats(a.id)
                KewuApi.getReviewQueue(a.id)?.filter {
                    it.aiSuggestedScore < 60.0 || it.reasons.any { r -> r.contains("风险") || r.contains("相似") }
                }?.forEach { q ->
                    watch += WatchStudent(q.studentName,
                        "作业 AI 建议分 ${q.aiSuggestedScore} · ${q.reasons.firstOrNull() ?: "需关注"}", 0)
                }
            }
        }
        if (watch.isEmpty()) {
            watch += listOf(
                WatchStudent("张明", "近两次作业得分下滑 18 分", 0),
                WatchStudent("李婷", "连续两次签到风险分偏高", 1),
                WatchStudent("王浩", "作业提交质量波动大，建议面谈", 2),
            )
            watchFromMock = true
        }
        watchList = watch.distinctBy { it.name }.take(5)
        dataReady = true

        // ── AI 主动连发教学简报（错峰进场，像一条条发过来，不直接堆卡片） ──
        delay(500)
        push(TaMsg(false, buildList {
            add(TaBlock.Text("${greetingByHour()}，${teacherName}！我是小悟，先给你今天的教学简报："))
            add(TaBlock.Text("课程 $courseCount 门 · 作业 $assignmentCount 份 · 重点关注 ${watchList.size} 人",
                emphasis = true))
            if (attStats != null) {
                add(TaBlock.Text("最近签到：应到 ${attStats!!.expected} · 实到 ${attStats!!.checked} · " +
                    "签到率 ${(attStats!!.checkRate * 100).toInt()}%"))
            }
            if (hwStats != null) {
                add(TaBlock.Text("作业质量：均分 ${hwStats!!.avgScore} · 待批 ${hwStats!!.pendingCount} · " +
                    "高风险 ${hwStats!!.highRiskCount} 人"))
            }
        }))
        delay(650)
        push(TaMsg(false, buildList {
            add(TaBlock.Text(if (watchFromMock) "以下学生近期需要关注（示例数据）：" else "以下学生近期需要关注：",
                emphasis = true))
            add(TaBlock.Students(watchList))
        }))
        delay(650)
        push(TaMsg(false, buildList {
            if (weakPoints.isNotEmpty()) {
                add(TaBlock.Text("⚠ 多名学生对「${weakPoints.first()}」有疑问，建议下节课开头回顾 5 分钟"))
                add(TaBlock.Chips(listOf("回顾「${weakPoints.first()}」", "加练「${weakPoints.first()}」习题",
                    "布置针对性小测")))
            } else {
                add(TaBlock.Text("暂未发现集中的薄弱知识点，继续保持！"))
            }
            add(TaBlock.Text("有问题随时问我，比如「谁退步了」「作业质量怎么样」「谁没签到」。"))
        }))
    }

    // 新消息自动滚到底
    LaunchedEffect(msgVersion) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    /** ★ AI 问答接入点：后端就绪后把这里换成真实问答接口（上下文 courses/analytics/watchList 已备好）。 */
    fun answer(q: String): List<TaBlock> = when {
        q.contains("关注") || q.contains("退步") || q.contains("学生") -> buildList {
            add(TaBlock.Text(if (watchFromMock) "重点关注名单（示例数据）：" else "重点关注名单：", emphasis = true))
            add(TaBlock.Students(watchList))
            add(TaBlock.Text("建议：退步学生优先一对一沟通，风险学生核实签到设备与代签可能。"))
        }
        q.contains("作业") || q.contains("批阅") || q.contains("提交") -> buildList {
            val hw = hwStats
            if (hw == null) add(TaBlock.Text("暂无作业数据，发布后我帮你盯提交质量。"))
            else {
                add(TaBlock.Text("当前作业：均分 ${hw.avgScore} · 已交 ${hw.submittedCount} · " +
                    "待批 ${hw.pendingCount} · 高风险 ${hw.highRiskCount} 人", emphasis = true))
                if (hw.highRiskCount > 0) add(TaBlock.Text("高风险提交建议优先批阅，可先看 AI 预批阅建议分。"))
            }
        }
        q.contains("签到") || q.contains("课堂") || q.contains("缺勤") || q.contains("到课") -> buildList {
            val st = attStats
            if (st == null) add(TaBlock.Text("暂无签到数据，发起一次课堂签到后我帮你盯到课率。"))
            else {
                add(TaBlock.Text("最近签到：应到 ${st.expected} · 实到 ${st.checked} · 迟到 ${st.late} · " +
                    "缺勤 ${st.absent}", emphasis = true))
                add(TaBlock.Text("签到率 ${(st.checkRate * 100).toInt()}%，平均用时 ${st.avgSeconds.toInt()} 秒。"))
            }
        }
        q.contains("薄弱") || q.contains("知识点") || q.contains("掌握") -> buildList {
            if (weakPoints.isEmpty()) add(TaBlock.Text("暂无薄弱知识点记录，批阅几份作业后我就能画出班级画像。"))
            else {
                add(TaBlock.Text("薄弱知识点：" + weakPoints.take(4).joinToString("、"), emphasis = true))
                mastery.take(3).forEach {
                    add(TaBlock.Text("· ${it.point}：掌握度 ${(it.avgScoreRate * 100).toInt()}%"))
                }
            }
        }
        else -> listOf(TaBlock.Text("这个问题我还在学习（本地规则暂不支持）。可以试试问我：谁需要关注、作业质量、签到情况、薄弱知识点。"))
    }

    fun send() {
        val q = input.trim()
        if (q.isEmpty()) return
        push(TaMsg(true, listOf(TaBlock.Text(q))))
        input = ""
        haptic(HapticKind.Light)
        scope.launch {
            delay(500) // 模拟 AI 思考（接后端时换成真实请求耗时）
            push(TaMsg(false, answer(q)))
        }
    }

    Box(Modifier.fillMaxSize().background(cs.background)) {
        // 背景采样层（玻璃气泡的采样源，与气泡平级）
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(pageBackdrop)
                .background(cs.background),
        )
        Column(Modifier.fillMaxSize().imePadding()) {
            // ── AI 人格区（与学生端小悟同款语言） ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(52.dp)
                        .background(KewuGreen.copy(alpha = 0.16f), CircleShape)
                        .border(1.5.dp, KewuGreen, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("悟", color = KewuGreen, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("小悟 · 教学洞察", color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(7.dp))
                        Box(Modifier.size(7.dp).background(KewuGreen, CircleShape))
                    }
                    Spacer(Modifier.height(2.dp))
                    Text("KEWU AI · 学情分析", color = cs.onSurfaceVariant, fontSize = 11.5.sp)
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(cs.primaryContainer)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text("主动播报", color = cs.onPrimaryContainer, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            // ── 对话列 ──
            val snapshot = messages.toList()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
            ) {
                itemsIndexed(snapshot, key = { i, _ -> i }) { _, msg ->
                    TaBubble(pageBackdrop, msg)
                    Spacer(Modifier.height(10.dp))
                }
                if (!dataReady && snapshot.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                            Text("小悟正在整理今日学情…", color = cs.onSurfaceVariant, fontSize = 13.sp)
                        }
                    }
                }
            }

            // ── 液态玻璃输入框（常驻导航栏上方，与学生端同款位置约束） ──
            TaInputBar(
                backdrop = pageBackdrop,
                input = input,
                onInput = { input = it },
                onSend = { send() },
            )
        }
    }
}

// ───────────────────────── 气泡 ─────────────────────────

@Composable
private fun TaBubble(backdrop: LayerBackdrop, msg: TaMsg) {
    val cs = MaterialTheme.colorScheme
    if (msg.isUser) {
        // 用户：M3 品牌蓝气泡（右对齐，与学生端同款）
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(KewuBlue)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                msg.blocks.filterIsInstance<TaBlock.Text>().forEach { t ->
                    Text(t.s, color = Color.White, fontSize = 14.5.sp, lineHeight = 21.sp)
                }
            }
        }
    } else {
        // AI：coui 毛玻璃气泡（左对齐，浅色底描边，标签行 + 内容块）
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            KewuGlassCard(
                backdrop = backdrop,
                shape = RoundedCornerShape(20.dp),
                blurRadius = 16.dp,
                tintAlpha = 0.30f,
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .border(1.dp, cs.outlineVariant, RoundedCornerShape(20.dp)),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("小悟", color = KewuGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(KewuGreen.copy(alpha = 0.16f))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        ) {
                            Text("KEWU AI", color = KewuGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    msg.blocks.forEach { block ->
                        when (block) {
                            is TaBlock.Text -> {
                                Text(
                                    block.s,
                                    color = if (block.emphasis) cs.onSurface else cs.onSurfaceVariant,
                                    fontSize = 13.5.sp,
                                    fontWeight = if (block.emphasis) FontWeight.SemiBold else FontWeight.Normal,
                                    lineHeight = 20.sp,
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                            is TaBlock.Students -> {
                                block.list.forEach { w ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(cs.surfaceVariant.copy(alpha = 0.6f))
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(w.name, color = cs.onSurface, fontSize = 13.5.sp,
                                                fontWeight = FontWeight.Medium)
                                            Text(w.reason, color = cs.onSurfaceVariant, fontSize = 11.5.sp,
                                                maxLines = 2, lineHeight = 15.sp)
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        StatusTag(
                                            when (w.kind) { 0 -> "退步"; 1 -> "风险"; else -> "需关注" },
                                            when (w.kind) {
                                                0 -> cs.errorContainer
                                                1 -> Color(0xFFFFB300).copy(alpha = 0.2f)
                                                else -> cs.primaryContainer
                                            },
                                            when (w.kind) {
                                                0 -> cs.onErrorContainer
                                                1 -> Color(0xFF8A5A00)
                                                else -> cs.onPrimaryContainer
                                            },
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                }
                            }
                            is TaBlock.Chips -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    block.list.forEach { c ->
                                        Box(
                                            Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(cs.primaryContainer)
                                                .padding(horizontal = 9.dp, vertical = 4.dp),
                                        ) {
                                            Text(c, color = cs.onPrimaryContainer, fontSize = 11.5.sp, maxLines = 1)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── 输入框 ─────────────────────────

/** 液态玻璃输入框 + 发送钮（学生端 TaPage 同款语言：常驻导航栏上方 bottom 118dp 兜底）。 */
@Composable
private fun TaInputBar(
    backdrop: LayerBackdrop,
    input: String,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .padding(bottom = 118.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 玻璃输入框
        Box(Modifier.weight(1f)) {
            KewuGlassCard(
                backdrop = backdrop,
                shape = RoundedCornerShape(26.dp),
                blurRadius = 18.dp,
                tintAlpha = 0.34f,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, cs.outlineVariant, RoundedCornerShape(26.dp)),
            ) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    BasicTextField(
                        value = input,
                        onValueChange = onInput,
                        singleLine = true,
                        textStyle = TextStyle(color = cs.onSurface, fontSize = 14.5.sp),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth()) {
                                if (input.isEmpty()) {
                                    Text("问小悟：谁退步了 / 作业质量 / 薄弱知识点…",
                                        color = cs.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 14.sp)
                                }
                                inner()
                            }
                        },
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        TaSendBtn(backdrop = backdrop, enabled = input.isNotBlank(), onClick = onSend)
    }
}

/** 发送钮：46dp 液态玻璃圆钮（magnify 透镜感 + 品牌绿芯 + 纸飞机），横向拉伸式按压回弹。 */
@Composable
private fun TaSendBtn(backdrop: LayerBackdrop, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var pressed by remember { mutableStateOf(false) }
    val onClickState = rememberUpdatedState(onClick)
    val scaleV by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.34f, stiffness = 320f, visibilityThreshold = 0.0005f),
        label = "taSendPress",
    )
    Box(
        Modifier
            .size(46.dp)
            .scale(scaleV)
            .pointerInput(enabled) {
                detectTapGestures(
                    onTap = { if (enabled) onClickState.value() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        KewuGlassCard(
            backdrop = backdrop,
            shape = CircleShape,
            blurRadius = 14.dp,
            tintAlpha = if (enabled) 0.30f else 0.18f,
            magnify = 1.28f,
            highlight = defaultKewuHighlight(false),
            modifier = Modifier
                .fillMaxSize()
                .border(1.dp, cs.outlineVariant, CircleShape),
        ) {
            // 绿芯（白图标在白底上不可见的修复，与学生端 SendBtn 同款思路）
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(5.dp)
                    .background(if (enabled) KewuGreen else cs.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(17.dp)) {
                    val w = size.width
                    val h = size.height
                    val p = Path().apply {
                        moveTo(w * 0.08f, h * 0.5f)
                        lineTo(w * 0.92f, h * 0.12f)
                        lineTo(w * 0.58f, h * 0.92f)
                        lineTo(w * 0.42f, h * 0.58f)
                        close()
                    }
                    drawPath(p, if (enabled) Color(0xFF0B3B26) else cs.onSurfaceVariant)
                }
            }
        }
    }
}

private fun greetingByHour(): String {
    val hour = ((kotlin.time.Clock.System.now().toEpochMilliseconds() / 3_600_000L + 8L) % 24L).toInt()
    return when {
        hour < 6 -> "凌晨好"
        hour < 12 -> "早上好"
        hour < 14 -> "中午好"
        hour < 18 -> "下午好"
        else -> "晚上好"
    }
}
