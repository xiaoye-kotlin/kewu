package com.kewu.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import com.kewu.app.BackHandler
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Add
import io.github.suqi8.coui.kmp.icon.extended.Mic
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.style.TextOverflow
import com.kewu.app.shareText
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.AiAgent
import com.kewu.app.AiClient
import com.kewu.app.ChatHistory
import com.kewu.app.AssistantHub
import com.kewu.app.Analytics
import com.kewu.app.AssignmentInfo
import com.kewu.app.AssignmentStats
import com.kewu.app.AttendanceRisk
import com.kewu.app.AttendanceStats
import com.kewu.app.CourseInfo
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.isLight
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import com.kewu.app.KewuGreen
import com.kewu.app.haptic
import com.kewu.app.kvGet
import com.kewu.app.kvSet
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.basic.Surface
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ───────────────────────── 教师端 · 助教（tab 1，AI 学情对话页） ─────────────────────────
//
// 形态与学生端 TaPage 同款：**AI 对话页**，进入时小悟主动连发教学简报（不直接堆卡片）：
//   ① 今日概览（课程/作业/关注人数/签到/作业质量）→ ② 重点关注学生名单 → ③ 高频疑问 + 建议行动。
// 数据来自 KewuApi 现有接口交叉计算（无专用画像接口；无数据时降级 mock 并标注「示例」）。
// 教师可随时在底部输入框提问，2026-10-24 起接入真实大模型（AiClient，openpangu-2.0-flash），
// 学情上下文随 system prompt 注入；AI 不可用时回退本地规则 answer() 兜底。
//
// 组件语言与学生端小悟一致：AI coui 毛玻璃气泡（标签行+内容块）/ 用户 coui Surface 品牌色气泡 /
// 液态玻璃输入框常驻导航栏上方（bottom 118dp；键盘弹起时 8dp，避让由主界面内容区 imePadding 统一处理）。

/**
 * 教学简报播报日期标记（kv，存「当天 00:00」的时间戳）。
 * 2026-10-25：教师端助教页每天只在第一次进入时播报一次简报，当天再进入不重复发。
 */
private const val KEY_TA_BRIEF_DAY = AssistantHub.KEY_TA_BRIEF_DAY

private data class WatchStudent(val name: String, val reason: String, val kind: Int) // 0=退步 1=风险 2=关注

/** AI 消息块：文本 / 重点学生列表 / 标签行，拼在一条 AI 气泡里。 */
private sealed interface TaBlock {
    data class Text(val s: String, val emphasis: Boolean = false) : TaBlock
    data class Students(val list: List<WatchStudent>) : TaBlock
    data class Chips(val list: List<String>) : TaBlock
}

private data class TaMsg(
    val isUser: Boolean, val blocks: List<TaBlock>,
    /** 稳定 id（列表动画 key / 持久化），0 = 未分配（入场简报等不落盘消息） */
    val id: Long = 0L,
    /** 发送时刻 epoch 毫秒 */
    val ts: Long = 0L,
    /** true = 属于真实对话，需要写进聊天记录（入场简报每次进页现生成，不落盘） */
    val keep: Boolean = false,
    /** 被引用的原文（与学生端 ChatMsg.quote 同口径）：用户气泡顶部渲染引用小条；null = 无引用。 */
    val quote: String? = null,
    /** 图片 / 语音消息（2026-11-03）：渲染与上传状态复用学生端 [AiChatMsg]；null = 普通文字。 */
    val media: AiChatMsg? = null,
)

/** 附件 / 录音面板（互斥，与学生端同一套）。 */
private enum class TeacherPanel { NONE, ATTACH, VOICE }

/** 复制 / 引用 / 分享用的纯文本（Students / Chips 块转可读行，与落盘口径一致）。 */
private fun TaMsg.plainText(): String = blocks.joinToString("\n") { b ->
    when (b) {
        is TaBlock.Text -> b.s
        is TaBlock.Students -> b.list.joinToString("\n") { "· ${it.name}：${it.reason}" }
        is TaBlock.Chips -> b.list.joinToString("、")
    }
}

@Composable
fun TeacherAssistantPage(onNavHide: (Boolean) -> Unit = {}) {
    val cs = COUITheme.colorScheme
    val pageBackdrop = rememberGlassBackdrop()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var dataReady by remember { mutableStateOf(false) }

    // ── 长按气泡操作菜单（复制 / 引用 / 分享），与学生端 TaPage 同一套交互（2026-10-26） ──
    // 引用：输入框上方单独一条引用小条，发送时随消息带上（TaMsg.quote），× 可取消
    var quotePreview by remember { mutableStateOf<String?>(null) }
    var menuMsg by remember { mutableStateOf<TaMsg?>(null) }
    // 菜单贴气泡定位：被长按的气泡持续上报窗口坐标，减去根盒原点得相对坐标（滚动时菜单跟着走）
    var menuAnchor by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    val snackbar = LocalKewuSnackbar.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    // 2026-11-04（P4）：文字选择状态（与学生端共用组件），教师端操作条 = 复制 / 全选 / 分享
    val textSelect = remember { ChatTextSelectState() }

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
    var pendingReview by remember { mutableStateOf<Int?>(null) } // 控制台聚合的待批阅数

    // ── 对话状态：聊天记录落盘（2026-10-24）。默认只展示今天的，列表顶部下拉按天加载更早。
    val chatStore = remember { ChatHistory.loadAll(ChatHistory.KEY_TEACHER) }
    var visibleCutoff by remember {
        mutableStateOf(ChatHistory.dayStart(kotlin.time.Clock.System.now().toEpochMilliseconds()))
    }
    var hasMoreHistory by remember {
        mutableStateOf(chatStore.any { it.ts < visibleCutoff })
    }
    val messages = remember { mutableListOf<TaMsg>() }
    var msgVersion by remember { mutableStateOf(0) }

    /** 块列表 → 纯文本（持久化用；Students/Chips 转为可读行）。 */
    fun blocksToText(blocks: List<TaBlock>): String = blocks.joinToString("\n") { b ->
        when (b) {
            is TaBlock.Text -> b.s
            is TaBlock.Students -> b.list.joinToString("\n") { "· ${it.name}：${it.reason}" }
            is TaBlock.Chips -> b.list.joinToString("、")
        }
    }

    fun persistMessages(current: List<TaMsg>) {
        // ★ 只落盘真实对话（keep=true）。入场简报不落盘，否则每次进页都会往历史里堆一份，
        //   下次进来「历史里的简报」+「现生成的简报」会重复出现。
        // 图片 / 语音由 ChatMediaHub 落盘（上传完成后换成 OBS 链接），这里跳过，免得旧状态覆盖
        val entries = current.filter { it.keep && it.id != 0L && it.media == null }.map { m ->
            ChatHistory.Entry(
                id = m.id,
                role = if (m.isUser) "user" else "assistant",
                text = blocksToText(m.blocks),
                ts = m.ts,
                quote = m.quote,
            )
        }
        // 按 id 合并写入（不覆盖更早几天的记录 / 页面不在时 AssistantHub 追加的回复）
        ChatHistory.upsert(ChatHistory.KEY_TEACHER, entries)
        hasMoreHistory = chatStore.any { it.ts < visibleCutoff }
    }

    /**
     * 广播一条消息。
     * ★ 2026-10-24 闪退修复：LazyColumn 用 `key = msg.id`，id 必须唯一 ——
     *   入场简报三条都带默认 id=0，key 重复会直接抛出并崩掉整页，所以这里统一补发 id。
     * [persist] = true 才写聊天记录（简报这类每次进页现生成的消息不落盘）。
     */
    fun push(msg: TaMsg, persist: Boolean = false) {
        val stamped = msg.copy(
            id = if (msg.id != 0L) msg.id else ChatHistory.nextId(),
            ts = if (msg.ts != 0L) msg.ts else nowMs(),
            keep = msg.keep || persist,
        )
        messages += stamped
        msgVersion++ // 触发重组（LazyColumn items 读取 list 快照）
        if (persist) persistMessages(messages.toList())
    }

    /**
     * 聊天记录条目 → 气泡消息。
     * ★ 2026-10-24 气泡身份颠倒修复：持久化写入是 `isUser → role="user"`（见 persistMessages），
     *   还原必须用同一个方向 `role == "user" → isUser`。此前写成 `!=` 导致重新进页后
     *   「用户发的显示成 AI 的、AI 发的显示成用户的」。
     */
    fun restored(e: ChatHistory.Entry): TaMsg = TaMsg(
        isUser = e.role == "user",
        blocks = e.text.lines().filter { it.isNotBlank() }.map { TaBlock.Text(it) },
        id = if (e.id != 0L) e.id else ChatHistory.nextId(),
        ts = e.ts,
        keep = true, // 来自历史，后续整体落盘时要保留
        quote = e.quote,
        media = if (e.image != null || e.voice != null) AiChatMsg(
            e.text, isUser = true, isVoice = e.voice != null, voiceSecs = e.voiceSecs,
            id = e.id, ts = e.ts, quote = e.quote, image = e.image, voice = e.voice,
        ) else null,
    )

    // 顶部下拉加载更早一天
    fun loadOlderDay() {
        val older = chatStore.filter { it.ts < visibleCutoff }
        if (older.isEmpty()) return
        val nextCutoff = ChatHistory.dayStart(visibleCutoff - 1)
        val newly = older.filter { it.ts >= nextCutoff }.map { e ->
            restored(e)
        }
        visibleCutoff = nextCutoff
        messages.addAll(0, newly)
        msgVersion++
    }

    LaunchedEffect(Unit) {
        // 恢复今天的聊天记录（更早的由列表顶部下拉按天加载）
        val today = chatStore.filter { it.ts >= visibleCutoff }.map { e -> restored(e) }
        if (today.isNotEmpty()) {
            messages.addAll(0, today)
            msgVersion++
        }
        if (KewuApi.currentUser == null) KewuApi.me()
        teacherName = KewuApi.displayName("老师")
        // 教师课程：/me 绑定课 → 控制台今日课程 → 教学班所属课程 → 全量（教师账号通常不在 /me 里绑课，
        // 旧版简报一直显示「课程 0 门」，小悟据此回答「暂无课程」）
        val courses: List<CourseInfo>? =
            KewuApi.currentUser?.courses?.takeIf { it.isNotEmpty() }
                ?: KewuApi.getTeacherConsole()?.todayCourses?.map { CourseInfo(it.courseId, it.courseName, "", "") }
                    ?.takeIf { it.isNotEmpty() }
                ?: KewuApi.getTeacherClasses()?.classes?.map { CourseInfo(it.courseId, it.courseName, "", "") }
                    ?.distinctBy { it.id }?.takeIf { it.isNotEmpty() }
                ?: KewuApi.myCourses()
        courseCount = courses?.size ?: 0
        // 全部课程的作业（旧版只取第一门课）
        val myIds = courses.orEmpty().map { it.id }.toSet()
        val assignments: List<AssignmentInfo>? = KewuApi.getAssignments(null)?.filter { myIds.isEmpty() || it.courseId in myIds }
        assignmentCount = assignments?.size ?: 0
        // 控制台聚合：待批阅红点数（2026-10-21 接入，简报用）
        pendingReview = KewuApi.getTeacherConsole()?.pendingReview
        // 跨课程待批阅聚合（2026-10-23）：简报里补「紧急 X 份 + 建议先改谁」
        val pendingDetail = KewuApi.getPendingReview()
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
        // 2026-10-26：不再用「张明 / 李婷 / 王浩」示例名单兜底——旧版示例学生会写进聊天记录、还会作为
        // 学情上下文发给大模型，老师以为是真实学生。没有数据就如实说没有。
        watchList = watch.distinctBy { it.name }.take(5)
        dataReady = true

        // ── AI 主动连发教学简报（错峰进场，像一条条发过来，不直接堆卡片） ──
        // ★ 2026-10-25：同一天只在**第一次**进入助教页时播报一次；当天再进来直接复用
        //   聊天记录里已存的简报，不再重复打扰（用户反馈「每次进入都会发」）。
        //   判据用「当天 00:00 的时间戳」写进 kv，跨天自动失效；次日进入会重新播报。
        // ★ 2026-11-02：判据改用「今天 00:00」而不是 visibleCutoff —— 后者会随下拉加载更早对话往前推，
        //   旧版先下拉再写标记就写成了前一天，AssistantHub 每次都判「今天没播报」→ 助教 tab 红点 3 永远消不掉。
        //   标记在开始播报时立即写：老师已经进了页面，切走也不再用红点催。
        val briefDay = ChatHistory.dayStart(kotlin.time.Clock.System.now().toEpochMilliseconds())
        if (kvGet(KEY_TA_BRIEF_DAY)?.toLongOrNull() == briefDay) {
            return@LaunchedEffect
        }
        kvSet(KEY_TA_BRIEF_DAY, briefDay.toString())
        AssistantHub.refreshUnread(ChatHistory.KEY_TEACHER)

        delay(500)
        push(TaMsg(false, buildList {
            add(TaBlock.Text("${greetingByHour()}，${teacherName}！我是小悟，先给你今天的教学简报："))
            add(TaBlock.Text("课程 $courseCount 门 · 作业 $assignmentCount 份 · 重点关注 ${watchList.size} 人" +
                (pendingReview?.let { " · 待批阅 $it 份" } ?: ""), emphasis = true))
            // 紧急批阅提示：聚合为 null 或 total=0 不出这句；items 按服务端优先级排序，取前 2 名
            pendingDetail?.takeIf { it.total > 0 }?.let { pr ->
                val top = pr.items.take(2).joinToString("、") { item ->
                    item.studentName + (item.reasons.firstOrNull()?.let { "（$it）" } ?: "")
                }
                add(TaBlock.Text("其中紧急 ${pr.urgentCount} 份" + (if (top.isNotEmpty()) "，建议先改：$top" else "")))
            }
            if (attStats != null) {
                add(TaBlock.Text("最近签到：应到 ${attStats!!.expected} · 实到 ${attStats!!.checked} · " +
                    "签到率 ${(attStats!!.checkRate * 100).toInt()}%"))
            }
            if (hwStats != null) {
                add(TaBlock.Text("作业质量：均分 ${hwStats!!.avgScore} · 待批 ${hwStats!!.pendingCount} · " +
                    "高风险 ${hwStats!!.highRiskCount} 人"))
            }
        }), persist = true)
        delay(650)
        push(TaMsg(false, buildList {
            if (watchList.isEmpty()) {
                add(TaBlock.Text("近期没有需要特别关注的学生。"))
            } else {
                add(TaBlock.Text("以下学生近期需要关注：", emphasis = true))
                add(TaBlock.Students(watchList))
            }
        }), persist = true)
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
        }), persist = true)
    }

    // 新消息平滑滑到底；键盘弹起 / 收起等视口变化时保持贴底（2026-10-26 改用 ChatAutoScroll，
    // 原 animateScrollToItem 只对齐最后一条的顶部，收起输入法会跳到上面的对话）。
    // messages 是普通 list，靠读 msgVersion 触发重组；key 只取最后一条 id —— 顶部加载更早历史不触发滚底
    val lastMsgId = msgVersion.let { messages.lastOrNull()?.id ?: 0L }
    val streamText = AssistantHub.streaming.collectAsState().value[ChatHistory.KEY_TEACHER]
    ChatAutoScroll(listState, lastMsgId to (streamText?.length?.div(40) ?: -1))

    // ── 真实 AI 问答（2026-10-24 接入）：AiClient 大模型 + 本页已聚合的学情上下文 ──

    /** 给大模型的学情上下文快照（数据来自页首已聚合的 KewuApi 结果）。 */
    fun buildAiContext(): String = buildString {
        appendLine("教师：$teacherName")
        appendLine("课程数：$courseCount · 作业数：$assignmentCount" +
            (pendingReview?.let { " · 待批阅 $it 份" } ?: ""))
        attStats?.let {
            appendLine("最近一次签到：应到 ${it.expected} · 实到 ${it.checked} · 迟到 ${it.late} · " +
                "缺勤 ${it.absent} · 签到率 ${(it.checkRate * 100).toInt()}%")
        }
        hwStats?.let {
            appendLine("最近一份作业：均分 ${it.avgScore} · 已交 ${it.submittedCount} · " +
                "待批 ${it.pendingCount} · 高风险 ${it.highRiskCount} 人")
        }
        if (watchList.isNotEmpty()) {
            appendLine("重点关注学生" + (if (watchFromMock) "（示例数据）" else "") + "：" +
                watchList.joinToString("；") { "${it.name}（${it.reason}）" })
        }
        if (weakPoints.isNotEmpty()) {
            appendLine("薄弱知识点：" + weakPoints.take(5).joinToString("、"))
        }
        if (mastery.isNotEmpty()) {
            appendLine("知识点掌握度：" + mastery.take(5)
                .joinToString("；") { "${it.point} ${(it.avgScoreRate * 100).toInt()}%" })
        }
    }

    val aiSystemPrompt =
        "你是「小悟」，课悟 App 里教师的 AI 教学洞察助手。回答教师关于班级学情、作业、签到、学生表现的问题。\n" +
            "要求：基于给出的学情数据回答，数据里没有的信息不要编造，可说明「当前暂无该数据」；\n" +
            "语气专业亲切、简洁，优先给可执行的教学建议；用中文回答；不要使用 markdown 格式符号，" +
            "分点用「·」开头、每点一行；总长度控制在 200 字以内。" +
            "不要重复自我介绍（「你好，我是小悟…」这类开场只在当天第一轮出现一次），也不要复述问题。\n\n当前班级学情数据：\n"

    /** 真实 AI 回答：成功返回文本块列表；失败返回 null（调用方回退本地规则）。 */
    suspend fun aiAnswer(q: String, emit: (String) -> Unit = {}, images: List<String> = emptyList()): List<TaBlock>? {
        // 2026-10-26：流式——边生成边上屏
        val reply = AiClient.chatStream(
            listOf(
                AiClient.Msg("system", aiSystemPrompt + buildAiContext() + "\n【课悟 App 里此刻能看到的全部信息（以此为准，作业截止、提交、批阅、签到等直接引用）】\n" + com.kewu.app.AiContext.teacher() + "\n\n" + AiAgent.teacherCapability()),
                AiClient.Msg("user", q, images),
            ),
            onDelta = emit,
        ) ?: return null
        val parsed = AiAgent.parse(reply)
        val blocks = parsed.text.lines().filter { it.isNotBlank() }.map { TaBlock.Text(it.trim()) }.toMutableList()
        // 2026-10-24：AI 软件操作代理 —— 执行 KEWU_ACTION 指令并把结果附在回答里
        parsed.action?.let { action ->
            AiAgent.execute("teacher", action)?.let { note ->
                if (note.isNotBlank()) blocks += TaBlock.Text(note, emphasis = true)
            }
        }
        return blocks
    }

    /** 本地规则兜底：AI 不可用（断网/超时/鉴权失败）时保证问答可用。 */
    fun answer(q: String): List<TaBlock> = when {
        q.contains("关注") || q.contains("退步") || q.contains("学生") -> buildList {
            if (watchList.isEmpty()) {
                add(TaBlock.Text("当前没有需要特别关注的学生。", emphasis = true))
            } else {
                add(TaBlock.Text("重点关注名单：", emphasis = true))
                add(TaBlock.Students(watchList))
            }
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

    // 2026-10-26：AI 请求改由进程级 AssistantHub 发起——切走页面不取消，回答到达时若不在本页
    // 就点亮底部「助教」红点；回到本页 pending 仍在则继续显示「正在输入中」。
    val pendingKeys by AssistantHub.pending.collectAsState()
    val aiThinking = ChatHistory.KEY_TEACHER in pendingKeys
    LaunchedEffect(Unit) {
        AssistantHub.incoming.collect { inc ->
            if (inc.key != ChatHistory.KEY_TEACHER || messages.any { it.id == inc.entry.id }) return@collect
            @Suppress("UNCHECKED_CAST")
            val blocks = (inc.payload as? List<TaBlock>)
                ?: inc.entry.text.lines().filter { it.isNotBlank() }.map { TaBlock.Text(it) }
            // 已由 AssistantHub 落盘，这里只上屏（keep=true：之后整体落盘时保留）
            messages += TaMsg(false, blocks, id = inc.entry.id, ts = inc.entry.ts, keep = true)
            msgVersion++
        }
    }

    // ── 发图 / 发语音（2026-11-03）：图片 → OBS → 视觉模型；语音 → OBS → SIS 转写 → 小悟 ──
    var panel by remember { mutableStateOf(TeacherPanel.NONE) }
    var stagedImage by remember { mutableStateOf<String?>(null) }
    val recorder = rememberChatVoiceRecorder()
    var finishingVoice by remember { mutableStateOf(false) }
    val mediaBusy = ChatHistory.KEY_TEACHER in ChatMediaHub.busy.collectAsState().value
    val mediaPatches by ChatMediaHub.patches.collectAsState()
    LaunchedEffect(mediaPatches) {
        var changed = false
        messages.forEachIndexed { i, m ->
            val media = m.media ?: return@forEachIndexed
            val p = mediaPatches[m.id] ?: return@forEachIndexed
            val next = ChatMediaHub.apply(media, p)
            if (next != media) {
                // 语音识别出的文字也写进 blocks（复制 / 引用 / 分享用）
                messages[i] = m.copy(media = next, blocks = if (next.isVoice && next.text.isNotBlank()) listOf(TaBlock.Text(next.text)) else m.blocks)
                changed = true
            }
        }
        if (changed) msgVersion++
    }
    LaunchedEffect(panel) {
        if (panel != TeacherPanel.VOICE && recorder.recording && !finishingVoice) recorder.cancel()
    }

    /** 把问题交给小悟（文字 / 图片 / 语音共用）。[images] 为 OBS 图片链接。 */
    fun ask(text: String, quoted: String?, images: List<String> = emptyList()) {
        // 引用拼进问题正文交给大模型 / 本地规则（学生端同口径）
        val base = text.ifEmpty { if (images.isNotEmpty()) "请看这张图片" else "请解释一下这段内容" }
        val q = if (quoted != null) "（引用：$quoted）$base" else text.ifEmpty { base }
        AssistantHub.askStream(ChatHistory.KEY_TEACHER) { emit ->
            // 优先真实大模型（流式）；失败回退本地规则（学情数据仍有价值），并明确告知用户
            val blocks = aiAnswer(q, emit, images) ?: buildList {
                if (images.isNotEmpty()) {
                    add(TaBlock.Text("⚠ AI 服务暂时连不上，图片没能看到，稍后再试一次。"))
                } else {
                    add(TaBlock.Text("⚠ AI 服务暂时连不上，先根据本地学情数据回答："))
                    addAll(answer(q))
                }
            }
            AssistantHub.Reply(blocksToText(blocks), payload = blocks)
        }
    }

    fun startImageJob(m: AiChatMsg) {
        ChatMediaHub.run(ChatHistory.KEY_TEACHER) {
            val local = m.image ?: return@run
            val (url, err) = if (local.startsWith("http")) local to null
            else KewuApi.uploadChatImage(local, guessImageMime(local))
            if (url == null) {
                ChatMediaHub.patch(ChatMediaHub.Patch(m.id, ChatMediaState.FAILED, error = err ?: "发送失败"))
                snackbar.toast(err ?: "图片没发出去，点图片重试")
                return@run
            }
            val done = m.copy(image = url, mediaState = ChatMediaState.DONE, mediaError = null)
            ChatMediaHub.patch(ChatMediaHub.Patch(m.id, ChatMediaState.DONE, image = url))
            ChatMediaHub.persist(ChatHistory.KEY_TEACHER, done)
            ask(m.text, m.quote, listOf(url))
        }
    }
    /** [liveText]：实时转录已得到的文字（2026-11-03），有就直接问 AI、不再上传整段录音转写；重试 / 实时失败时为 null。 */
    fun startVoiceJob(m: AiChatMsg, liveText: String? = null) {
        ChatMediaHub.run(ChatHistory.KEY_TEACHER) {
            val local = m.voice ?: return@run
            if (liveText != null) {
                val text = liveText
                val done = m.copy(text = text, mediaState = ChatMediaState.DONE, mediaError = null)
                ChatMediaHub.patch(ChatMediaHub.Patch(m.id, ChatMediaState.DONE, voice = local, text = text))
                ChatMediaHub.persist(ChatHistory.KEY_TEACHER, done)
                ask(text, null)
                return@run
            }
            val r = KewuApi.sendChatVoice(local)
            val text = r.text.trim()
            if (r.url == null || text.isEmpty()) {
                val why = if (r.url == null) r.error ?: "发送失败" else "没听清"
                ChatMediaHub.patch(ChatMediaHub.Patch(m.id, ChatMediaState.FAILED, error = why))
                return@run
            }
            val done = m.copy(voice = r.url, text = text, mediaState = ChatMediaState.DONE, mediaError = null)
            ChatMediaHub.patch(ChatMediaHub.Patch(m.id, ChatMediaState.DONE, voice = r.url, text = text))
            ChatMediaHub.persist(ChatHistory.KEY_TEACHER, done)
            ask(text, null)
        }
    }
    val retryMedia: (AiChatMsg) -> Unit = { m ->
        when {
            m.mediaState != ChatMediaState.FAILED -> Unit
            aiThinking || mediaBusy -> scope.launch { snackbar.toast("小悟还在忙，稍等一下再试") }
            else -> {
                ChatMediaHub.patch(ChatMediaHub.Patch(m.id, ChatMediaState.SENDING))
                val pending = m.copy(mediaState = ChatMediaState.SENDING, mediaError = null)
                if (m.isVoice) startVoiceJob(pending) else startImageJob(pending)
            }
        }
    }

    fun send() {
        val text = input.trim()
        val quoted = quotePreview
        val img = stagedImage
        if (aiThinking || mediaBusy) return
        if (img != null) {
            // 图片（可带配文 / 引用）：先上屏（本地图 + 转圈），上传完再问
            val id = ChatHistory.nextId()
            val ts = nowMs()
            val media = AiChatMsg(text, isUser = true, id = id, ts = ts, quote = quoted, image = img,
                mediaState = ChatMediaState.SENDING)
            push(TaMsg(true, if (text.isEmpty()) emptyList() else listOf(TaBlock.Text(text)),
                id = id, ts = ts, keep = true, quote = quoted, media = media))
            ChatMediaHub.persist(ChatHistory.KEY_TEACHER, media)
            input = ""
            quotePreview = null
            stagedImage = null
            haptic(HapticKind.Light)
            startImageJob(media)
            return
        }
        // 允许「只发引用 + 正文为空」（与学生端一致）；两者都空则不动
        if (text.isEmpty() && quoted == null) return
        push(TaMsg(true, if (text.isEmpty()) emptyList() else listOf(TaBlock.Text(text)), quote = quoted), persist = true)
        input = ""
        quotePreview = null
        haptic(HapticKind.Light)
        ask(text, quoted)
    }

    /** 录音结束：取消则丢弃；太短丢弃；否则上屏（识别中）并交给 SIS。 */
    fun finishVoice(send: Boolean) {
        if (finishingVoice) return
        finishingVoice = true
        scope.launch {
            try {
                if (!send) {
                    recorder.cancel()
                    return@launch
                }
                val r = recorder.stop()
                when {
                    r == null -> snackbar.toast("录音保存失败，请重试")
                    r.second < 1 -> {
                        com.kewu.app.platformDeleteFile(r.first)
                        snackbar.toast("说话时间太短")
                    }
                    else -> {
                        val id = ChatHistory.nextId()
                        val ts = nowMs()
                        val media = AiChatMsg("", isUser = true, isVoice = true, voiceSecs = r.second, id = id, ts = ts,
                            voice = r.first, mediaState = ChatMediaState.SENDING)
                        push(TaMsg(true, emptyList(), id = id, ts = ts, keep = true, media = media))
                        ChatMediaHub.persist(ChatHistory.KEY_TEACHER, media)
                        haptic(HapticKind.Light)
                        startVoiceJob(media, recorder.lastText)
                    }
                }
            } finally {
                panel = TeacherPanel.NONE
                finishingVoice = false
            }
        }
    }
    BackHandler(panel != TeacherPanel.NONE) { panel = TeacherPanel.NONE }
    // P4：文字选择模式下返回键先退出选择（后组合者优先）
    BackHandler(textSelect.active) { textSelect.exit() }

    // P8 微信风格浅灰底（仅浅色主题；深色保持 coui surface）
    val taBg = if (cs.surface.isLight()) Color(0xFFEDEDED) else cs.surface
    Box(
        Modifier
            .fillMaxSize()
            .background(taBg)
            .onGloballyPositioned { rootOrigin = it.positionInWindow() },
    ) {
        // 背景采样层（玻璃气泡的采样源，与气泡平级；底色 = 页面灰底）
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(pageBackdrop)
                .background(taBg),
        )
        // 2026-10-26：输入法避让由主界面内容区统一 imePadding，这里不再重复加
        Column(Modifier.fillMaxSize()) {
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
                    // 2026-10-24：等待 AI 回复时顶部状态改为「小悟正在输入中…」
                    Text(
                        if (aiThinking) "小悟正在输入中…" else "学情分析",
                        color = cs.onSurfaceSecondary, fontSize = 11.5.sp,
                    )
                }
            }

            // ── 对话列（2026-10-24：顶部下拉加载更早对话；消息 animateItem 滑入） ──
            val pullLoadPx = with(LocalDensity.current) { 110.dp.toPx() }
            var pullAcc by remember { mutableFloatStateOf(0f) }
            val pullConnection = remember {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        val atTop = listState.firstVisibleItemIndex == 0 &&
                            listState.firstVisibleItemScrollOffset == 0
                        if (atTop && available.y > 0 && hasMoreHistory) {
                            pullAcc += available.y
                            if (pullAcc >= pullLoadPx) {
                                pullAcc = 0f
                                loadOlderDay()
                            }
                            return Offset(0f, available.y)
                        }
                        if (available.y < 0) pullAcc = 0f
                        return Offset.Zero
                    }

                    // 松手即归零：旧版松手后 pullAcc 留在半路，提示文字在两种状态间来回跳
                    override suspend fun onPreFling(available: Velocity): Velocity {
                        pullAcc = 0f
                        return Velocity.Zero
                    }
                }
            }
            // 防御：同 id 只渲染一条（LazyColumn key 重复会直接崩溃）
            val snapshot = messages.distinctBy { it.id }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).nestedScroll(pullConnection),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
            ) {
                item(key = "history-tip") { PullHistoryHint(hasMoreHistory, pullAcc / pullLoadPx) }
                // P8：日期/时间由每条消息上方的 chatTimeLabel 承担（5 分钟规则），不再单独插日期行
                // P8 微信时间条：与上一条间隔 >5 分钟或跨天时，本条上方插一条居中灰字时间
                itemsIndexed(snapshot, key = { _, m -> m.id }, contentType = { _, m -> m.isUser }) { idx, msg ->
                    val prevMsg = snapshot.getOrNull(idx - 1)
                    if (chatNeedTimeLabel(prevMsg?.ts ?: 0L, msg.ts)) {
                        Text(
                            chatTimeLabel(msg.ts),
                            color = COUITheme.colorScheme.onSurfaceSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                    Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) { // 气泡是玻璃采样者：只做位移动画，不做淡入淡出（祖先禁 alpha）
                        androidx.compose.runtime.CompositionLocalProvider(LocalChatMediaRetry provides retryMedia) {
                            TaBubble(
                                pageBackdrop, msg,
                                // 只有被长按的这条持续上报坐标（滚动时菜单跟着走）；其余不报，避免每帧写状态
                                reportBounds = msg == menuMsg,
                                onBounds = { menuAnchor = it },
                                selectFor = textSelect,
                                // P8 weui 规则：连续 AI 消息只第一条带头像
                                showAvatar = prevMsg?.isUser != false,
                                onLongPress = {
                                    // 2026-11-04（P4）：文字消息长按进文字选择（SelectTextHelper 风格）；
                                    // 图片 / 语音消息长按仍弹原菜单
                                    if (msg.media == null) {
                                        haptic(HapticKind.Medium)
                                        textSelect.enter(
                                            msg.id,
                                            msg.blocks.joinToString("\n") { b ->
                                                when (b) {
                                                    is TaBlock.Text -> stripMarkdown(b.s)
                                                    is TaBlock.Students -> b.list.joinToString("\n") { "· ${it.name}：${it.reason}" }
                                                    is TaBlock.Chips -> b.list.joinToString("、")
                                                }
                                            },
                                        )
                                    } else {
                                        menuMsg = msg
                                        haptic(HapticKind.Medium)
                                    }
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                // 流式回答草稿（思考中占位 → 逐段增长；结束后由正式消息替换）
                if (streamText != null) {
                    item(key = "ai-streaming") {
                        Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) { // 气泡是玻璃采样者：只做位移动画，不做淡入淡出（祖先禁 alpha）
                            TaBubble(
                                pageBackdrop,
                                TaMsg(false, streamText.ifEmpty { "小悟正在思考…" }.lines()
                                    .filter { it.isNotBlank() }.map { TaBlock.Text(it.trim()) }),
                                // P8 weui 规则：上一条是用户消息 → 流式泡带头像
                                showAvatar = snapshot.lastOrNull()?.isUser != false,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
                if (!dataReady && snapshot.isEmpty()) {
                    item {
                        KewuLoading(Modifier.padding(top = 12.dp), text = "小悟正在整理今日学情…")
                    }
                }
            }

            // ── 附件 / 录音面板：单一互斥容器，纯滑入滑出（面板是玻璃采样者，祖先禁 alpha） ──
            var shownPanel by remember { mutableStateOf(TeacherPanel.ATTACH) }
            LaunchedEffect(panel) { if (panel != TeacherPanel.NONE) shownPanel = panel }
            AnimatedVisibility(
                visible = panel != TeacherPanel.NONE,
                enter = slideInVertically(spring(dampingRatio = 0.82f, stiffness = 420f)) { it },
                exit = slideOutVertically(tween(150)) { it },
            ) {
                when (shownPanel) {
                    TeacherPanel.ATTACH -> ChatAttachPanel(
                        backdrop = pageBackdrop,
                        onAlbum = {
                            panel = TeacherPanel.NONE
                            com.kewu.app.pickFiles(images = true) { files ->
                                val f = files.firstOrNull() ?: return@pickFiles
                                if (f.size > 15L * 1024 * 1024) {
                                    scope.launch { snackbar.toast("图片超过 15MB，换一张吧") }
                                } else {
                                    stagedImage = f.path
                                    if (files.size > 1) scope.launch { snackbar.toast("一次发一张，已选第一张") }
                                }
                            }
                        },
                        onCamera = if (com.kewu.app.CameraCapture.available) {
                            {
                                panel = TeacherPanel.NONE
                                com.kewu.app.CameraCapture.launcher?.invoke { path ->
                                    if (path != null) stagedImage = path
                                }
                            }
                        } else null,
                    )
                    TeacherPanel.VOICE -> ChatVoicePanel(
                        backdrop = pageBackdrop,
                        recorder = recorder,
                        onCancel = { finishVoice(send = false) },
                        onSend = { finishVoice(send = true) },
                    )
                    TeacherPanel.NONE -> Unit
                }
            }

            // ── 液态玻璃输入框（常驻导航栏上方，与学生端同款位置约束） ──
            ChatInputDock(
                backdrop = pageBackdrop,
                input = input,
                onInput = { input = it },
                onSend = { send() },
                placeholder = "问小悟：谁退步了 / 作业质量 / 薄弱知识点…",
                sendEnabled = !aiThinking && !mediaBusy,
                quote = quotePreview,
                onCancelQuote = { quotePreview = null },
                stagedImage = stagedImage,
                onRemoveImage = { stagedImage = null },
                leading = {
                    ChatRoundIconBtn(backdrop = pageBackdrop, onClick = {
                        panel = if (panel == TeacherPanel.ATTACH) TeacherPanel.NONE else TeacherPanel.ATTACH
                    }) { ChatGlyphIcon(COUIIcons.Add) }
                },
                trailing = {
                    ChatRoundIconBtn(backdrop = pageBackdrop, onClick = {
                        when {
                            panel == TeacherPanel.VOICE -> finishVoice(send = false)
                            aiThinking || mediaBusy -> scope.launch { snackbar.toast("小悟还在回答，稍等一下") }
                            else -> scope.launch {
                                com.kewu.app.AudioPlayback.stop() // 别把正在播放的声音录进去
                                val err = recorder.start()
                                if (err != null) snackbar.toast(err) else {
                                    haptic(HapticKind.Light)
                                    panel = TeacherPanel.VOICE
                                }
                            }
                        }
                    }) { ChatGlyphIcon(COUIIcons.Mic) }
                },
            )
        }

        // ── 长按气泡的操作菜单（液态玻璃 + coui 选项，与学生端同款）：必须是根盒最后一个子节点（盖在最上层） ──
        // P4 文字选择工具条（复制 / 全选 / 分享），渲染在菜单层之上
        ChatSelectToolbar(
            state = textSelect,
            rootOrigin = rootOrigin,
            onCopy = { t ->
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(t))
                scope.launch { snackbar.toast("已复制") }
            },
            onShare = { t ->
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(t))
                scope.launch { snackbar.toast("内容已复制，可以粘贴分享") }
            },
        )
        val activeMenuMsg = menuMsg
        if (activeMenuMsg != null) {
            val relRect = menuAnchor?.let { r ->
                androidx.compose.ui.geometry.Rect(
                    r.left - rootOrigin.x, r.top - rootOrigin.y,
                    r.right - rootOrigin.x, r.bottom - rootOrigin.y,
                )
            }
            val text = activeMenuMsg.plainText().ifBlank { activeMenuMsg.quote.orEmpty() }
            LiquidGlassActionMenu(
                visible = true,
                anchor = if (activeMenuMsg.isUser) GlassMenuAnchor.TopEnd else GlassMenuAnchor.TopStart,
                anchorRect = relRect,
                actions = listOf(
                    GlassMenuAction("复制", MenuGlyphKind.Copy) {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                        scope.launch { snackbar.toast("已复制") }
                    },
                    GlassMenuAction("引用", MenuGlyphKind.Quote) { quotePreview = text },
                    GlassMenuAction("分享", MenuGlyphKind.Share) { shareText(text) },
                ),
                onDismiss = { menuMsg = null },
            )
        }
    }
}

// ───────────────────────── 气泡 ─────────────────────────

/**
 * 对话气泡。长按 → 复制 / 引用 / 分享菜单（[onLongPress] 为 null 时不响应，如流式草稿）。
 * 按压 Q 弹只作用在气泡**内部内容**上（AI 气泡是玻璃采样者，外壳禁 scale）。
 */
@Composable
private fun TaBubble(
    backdrop: LayerBackdrop,
    msg: TaMsg,
    /** 被长按选中：持续上报气泡矩形（窗口坐标）供菜单贴脸定位 */
    reportBounds: Boolean = false,
    onBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    onLongPress: (() -> Unit)? = null,
    /** 文字选择模式（P4）：本消息被选中时正文渲染为可选中纯文本 + 拖柄 */
    selectFor: ChatTextSelectState? = null,
    /** P8 weui 规则：上一条也是 AI 消息时省略本条头像（false = 省略）。 */
    showAvatar: Boolean = true,
) {
    val cs = COUITheme.colorScheme
    val press = if (onLongPress != null) Modifier.bubblePress(onLongPress) else Modifier
    val bounds = Modifier.onGloballyPositioned { if (reportBounds) onBounds(it.boundsInWindow()) }
    // P4 选择模式：整条消息的纯文本（blocks → 纯文本），选区按字符稳定映射
    val selecting = selectFor?.msgKey == msg.id
    val plainText = if (selecting) msg.blocks.joinToString("\n") { b ->
        when (b) {
            is TaBlock.Text -> stripMarkdown(b.s)
            is TaBlock.Students -> b.list.joinToString("\n") { "· ${it.name}：${it.reason}" }
            is TaBlock.Chips -> b.list.joinToString("、")
        }
    } else ""
    val media = msg.media
    if (msg.isUser && media != null) {
        // 图片 / 语音：与学生端同一套气泡（上传 / 识别状态、失败点按重试）
        val longPress = onLongPress ?: {}
        if (media.isVoice) {
            Box(Modifier.fillMaxWidth().then(bounds), contentAlignment = Alignment.CenterEnd) {
                ChatVoiceBubble(media, longPress)
            }
        } else {
            Column(
                Modifier.fillMaxWidth().then(bounds),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ChatImageBubble(media, longPress)
                if (msg.blocks.isNotEmpty() || msg.quote != null) {
                    TaBubble(backdrop, msg.copy(media = null), onLongPress = onLongPress)
                }
            }
        }
    } else if (msg.isUser) {
        // U3 weui：用户泡纯 #95EC69 + 黑字 + 6dp 圆角 + 右侧尖角 + 40dp 方头像（与学生端一致）
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier
                    .widthIn(max = 280.dp)
                    .then(bounds)
                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 2.dp, bottomEnd = 6.dp, bottomStart = 6.dp))
                    .background(WeuiUserGreen),
            ) {
                Column(press.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    if (selecting && selectFor != null) {
                        SelectableChatText(plainText, selectFor, color = WeuiText,
                            fontSize = 15.sp, lineHeight = 22.sp, selectedBg = Color.White.copy(alpha = 0.45f))
                    } else {
                        // 微信引用条：灰底 + 左侧竖条 + 小字
                        msg.quote?.let { quoted ->
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0x1A000000))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                Box(
                                    Modifier.width(2.5.dp).heightIn(min = 20.dp)
                                        .clip(RoundedCornerShape(2.dp)).background(Color(0xFF9A9A9A)),
                                )
                                Spacer(Modifier.width(7.dp))
                                Text(
                                    quoted, color = Color(0xFF5B5B5B),
                                    fontSize = 11.5.sp, lineHeight = 16.sp,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 220.dp),
                                )
                            }
                            if (msg.blocks.isNotEmpty()) Spacer(Modifier.height(7.dp))
                        }
                        msg.blocks.filterIsInstance<TaBlock.Text>().forEach { t ->
                            Text(t.s, color = WeuiText, fontSize = 15.sp, lineHeight = 22.sp)
                        }
                    }
                }
            }
            WeuiBubbleTail(isUser = true, modifier = Modifier.padding(top = 13.dp))
            Spacer(Modifier.width(8.dp))
            ChatUserAvatar()
        }
    } else {
        // U3 weui：AI 泡纯白 + 黑字 + 左侧尖角（不再整颗毛玻璃），40dp 方头像
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.Top) {
            if (showAvatar) {
                ChatAiAvatar()
                Spacer(Modifier.width(8.dp))
            } else {
                Spacer(Modifier.width(40.dp))
            }
            WeuiBubbleTail(isUser = false, modifier = Modifier.padding(top = 13.dp))
            val aiShape = RoundedCornerShape(topStart = 2.dp, topEnd = 6.dp, bottomEnd = 6.dp, bottomStart = 6.dp)
            Box(
                Modifier
                    .widthIn(max = 300.dp)
                    .border(1.dp, WeuiBubbleBorder, aiShape)
                    .clip(aiShape)
                    .background(WeuiAiWhite)
                    .then(bounds),
            ) {
                Column(press.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("小悟", color = Color(0xFF7A7A7A), fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFFF0F0F0)) {
                            Text(
                                "KEWU AI", color = Color(0xFF8A8A8A), fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (selecting && selectFor != null) {
                        SelectableChatText(plainText, selectFor, color = WeuiText,
                            fontSize = 15.sp, lineHeight = 22.sp,
                            selectedBg = Color(0xFF3D7BEF).copy(alpha = 0.26f))
                    } else {
                    msg.blocks.forEach { block ->
                        when (block) {
                            is TaBlock.Text -> {
                                if (block.emphasis) Text(
                                    stripMarkdown(block.s),
                                    color = cs.onSurface,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    lineHeight = 20.sp,
                                ) else MarkdownText(block.s, color = cs.onSurfaceSecondary, fontSize = 13.5.sp, lineHeight = 20.sp)
                                Spacer(Modifier.height(4.dp))
                            }
                            is TaBlock.Students -> {
                                block.list.forEach { w ->
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp),
                                        color = cs.secondaryContainer,
                                    ) {
                                    Row(
                                        Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(w.name, color = cs.onSurface, fontSize = 13.5.sp,
                                                fontWeight = FontWeight.Medium)
                                            Text(w.reason, color = cs.onSurfaceSecondary, fontSize = 11.5.sp,
                                                maxLines = 2, lineHeight = 15.sp)
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        KewuTag(
                                            when (w.kind) { 0 -> "退步"; 1 -> "风险"; else -> "需关注" },
                                            when (w.kind) {
                                                0 -> cs.errorContainer
                                                1 -> Color(0xFFFFB300).copy(alpha = 0.2f)
                                                else -> cs.tertiaryContainer
                                            },
                                            when (w.kind) {
                                                0 -> cs.onErrorContainer
                                                1 -> Color(0xFF8A5A00)
                                                else -> cs.onTertiaryContainer
                                            },
                                        )
                                    }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                }
                            }
                            is TaBlock.Chips -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    block.list.forEach { c ->
                                        Surface(shape = RoundedCornerShape(8.dp), color = cs.tertiaryContainer) {
                                            Text(
                                                c, color = cs.onTertiaryContainer, fontSize = 11.5.sp, maxLines = 1,
                                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                            )
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
}

// ───────────────────────── 输入框 ─────────────────────────

/** 当前 epoch 毫秒（2026-10-24 聊天记录持久化用）。 */
private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

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
