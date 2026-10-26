package com.kewu.app.ui

import com.kewu.app.CourseRepo
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import com.kewu.app.AssignmentRepo
import com.kewu.app.BackHandler
import com.kewu.app.AiAgent
import com.kewu.app.AiClient
import com.kewu.app.ChatHistory
import com.kewu.app.AssistantHub
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuGreen
import com.kewu.app.haptic
import com.kewu.app.shareText
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.ChipDefaults
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.Surface
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Add
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.File
import io.github.suqi8.coui.kmp.icon.extended.Mic
import io.github.suqi8.coui.kmp.icon.extended.Ok
import io.github.suqi8.coui.kmp.icon.extended.Photos
import io.github.suqi8.coui.kmp.icon.extended.Scan
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ───────────────────────── 小悟 · AI 智能体对话页（tab 1） ─────────────────────────

/**
 * 「助教」页 = AI 智能体对话页（Ardot 设计稿：`kewu-AI助教界面`）。学生和小悟对话问课堂问题。
 *
 * 视觉语言（coui 灰底上的液态玻璃，2026-10-18 晚真机打磨后；2026-10-23 非玻璃件全部换 coui 原生）：
 * - **玻璃必须看得见**：浅色底上光靠白色 tint 拓不出边界（交接文档第七节同款坑），所以每个玻璃件
 *   都套 `border(1dp, outlineVariant)` 描边 + 提高 tintAlpha 做出奶白磨砂感；
 * - 发送钮：绿芯（KewuGreen 径向渐变，白图标才看得见）+ 导航泡泡同款 spectralBrush 彩虹双带；
 * - **入场动画（凝结出场，登录页同款语言）**：人格区 scale+alpha+上浮弹簧 → 上下文 chip 错峰上浮
 *   → 输入框从下方「加速冲上来」并带阻尼欠拟合的晃动（spring 0.52/330 过冲），
 *   动画只动 placement 位移 + 纯 draw 参数（blur/tint/圆角）——采样者安全；
 * - **按压 Q 弹**：pressBounce（按下 0.88，低阻尼弹簧 0.32/480 + 显式 visibilityThreshold 跑完过冲），
 *   玻璃件只弹内部内容（采样者不裹 scale —— coui SIGSEGV 硬约束）。
 *
 * 交互：
 * - 「+」→ QQ 式附件面板（相册/拍照/文件，后端接入前占位点击）；
 * - 麦克风 → 声纹波纹录音面板（取消/发送，发送落一条「语音消息」气泡，录音后端后补）；
 * - ★ 键盘：`imePadding()` 只作用于输入框那一行 —— 早期版本误加在页面根节点，
 *   键盘弹起整页顶上去、中间留空（用户实测反馈），已修正。
 */

/** 助教输入区的弹出面板：互斥，同一时刻只可能有一个（2026-10-19）。 */
private enum class AssistantPanel { NONE, ATTACH, VOICE }

@Composable
fun TaPage(onNavHide: (Boolean) -> Unit, onGoToTab: (Int) -> Unit) {
    val cs = COUITheme.colorScheme
    val pageBackdrop = rememberGlassBackdrop()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // ── 对话状态（2026-10-24 起接入真实大模型 AiClient；失败回退占位文案保证不空屏；
    //    聊天记录落盘：默认只展示今天的，列表顶部下拉按天加载更早对话） ──
    val chatStore = remember { ChatHistory.loadAll(ChatHistory.KEY_STUDENT) }
    // 可见窗口起点：今天 00:00（UTC+8）；下拉加载更早时逐天前移
    var visibleCutoff by remember {
        mutableStateOf(ChatHistory.dayStart(kotlin.time.Clock.System.now().toEpochMilliseconds()))
    }
    var hasMoreHistory by remember {
        mutableStateOf(chatStore.any { it.ts < visibleCutoff })
    }
    fun entriesToMsgs(list: List<ChatHistory.Entry>): List<ChatMsg> = list.map { e ->
        ChatMsg(
            text = e.text, isUser = e.role == "user",
            id = if (e.id != 0L) e.id else ChatHistory.nextId(), ts = e.ts,
            quote = e.quote,
        )
    }
    fun persistHistory(current: List<ChatMsg>) {
        val entries = current.map { m ->
            ChatHistory.Entry(
                id = if (m.id != 0L) m.id else ChatHistory.nextId(),
                role = if (m.isUser) "user" else "assistant",
                text = m.text, ts = if (m.ts != 0L) m.ts else nowMs(), quote = m.quote,
            )
        }
        // 按 id 合并写入（不覆盖页面没加载的更早消息 / 页面不在时 AssistantHub 追加的回复）
        ChatHistory.upsert(ChatHistory.KEY_STUDENT, entries)
        // 是否还有更早历史：以全量存档为准（内存里可能只有今天的消息）
        hasMoreHistory = chatStore.any { it.ts < visibleCutoff }
    }
    // 今天的聊天记录：有历史就不重复显示欢迎语（2026-10-24 用户反馈：每天第一条消息才需要问候）
    val todayEntries = remember(chatStore) { chatStore.filter { it.ts >= visibleCutoff } } // 旧版每敲一个字都全量过滤
    var messages by remember {
        mutableStateOf(
            buildList {
                if (todayEntries.isEmpty()) {
                    add(
                        ChatMsg(
                            "你好，我是小悟～上课没听懂的地方直接问我：拍张板书、传个课件、发段语音都可以。",
                            isUser = false,
                            id = ChatHistory.nextId(),
                        )
                    )
                }
                addAll(entriesToMsgs(todayEntries))
            },
        )
    }
    // 顶部下拉加载更早一天
    fun loadOlderDay() {
        val older = chatStore.filter { it.ts < visibleCutoff }
        if (older.isEmpty()) return
        val nextCutoff = ChatHistory.dayStart(visibleCutoff - 1)
        val newly = entriesToMsgs(older.filter { it.ts >= nextCutoff })
        visibleCutoff = nextCutoff
        messages = newly + messages
    }
    var input by remember { mutableStateOf("") }
    // ★ 引用预览（第三轮 12.8）：引用不再塞进输入框，而是在输入框**上方**单独显示一条灰色小条，
    //   发送时随消息带上（ChatMsg.quote），× 可一键取消。
    var quotePreview by remember { mutableStateOf<String?>(null) }
    // 初值 0（旧版初值 2，接口失败时这个假数会进 AI 的 system prompt）
    var courseCount by remember { mutableIntStateOf(0) }
    var pendingCount by remember { mutableIntStateOf(0) }
    var assignmentTitles by remember { mutableStateOf<List<String>>(emptyList()) }
    var suggestions by remember {
        mutableStateOf(listOf("这节课的笔记", "作业什么时候交？", "帮我规划一下复习"))
    }
    // 附件 / 语音面板：★ 用**单一互斥状态**（2026-10-19 修连点卡顿/瞬移）。
    // 旧版用 showAttach / isRecording 两个 boolean，连点「+」与麦克风时两片玻璃面板会同时
    // 进退场（双份 blur 采样 + 布局反复回流），表现为卡顿、面板瞬移。现在只可能有一个面板存在。
    var panel by remember { mutableStateOf(AssistantPanel.NONE) }
    var voiceSecs by remember { mutableIntStateOf(0) }
    // 派生布尔：沿用原变量名，其它逻辑（返回键/建议条/录音计时）无需改
    val showAttach = panel == AssistantPanel.ATTACH
    val isRecording = panel == AssistantPanel.VOICE

    // ── 长按气泡的 QQ 式操作菜单 + 轻提示（复制/引用/分享） ──
    var menuMsg by remember { mutableStateOf<ChatMsg?>(null) }
    var menuIsUser by remember { mutableStateOf(false) }
    // ★ 菜单贴气泡定位（2026-10-19）：长按的气泡把自己的窗口坐标矩形报上来，
    //   菜单遮罩层拿它减去根盒原点 → 相对坐标，卡片贴在气泡正上方。
    //   滚动时 onGloballyPositioned 持续上报 → 菜单跟着气泡走，不会脱节。
    var menuAnchor by remember { mutableStateOf<Rect?>(null) } // 长按气泡的窗口坐标矩形
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }  // 页面根盒的窗口原点
    // 轻提示走全局 coui Snackbar（原自建中央胶囊已下线）
    val snackbar = LocalKewuSnackbar.current
    val clipboard = LocalClipboardManager.current

    // 上下文 chip + 建议提问：真实接口（/api/courses、/api/assignments），失败保留 mock
    LaunchedEffect(Unit) {
        CourseRepo.refresh()?.let { courseCount = it.size }
        // 待交数用 AssignmentRepo 的真实口径（未交 + 被打回；旧版是作业总数）
        AssignmentRepo.refresh(maxAgeMs = 60_000)?.let { items ->
            val pending = items.filter { it.pending }
            pendingCount = pending.size
            assignmentTitles = items.map { it.assignment.title }
            (pending.firstOrNull() ?: items.firstOrNull())?.let { first ->
                suggestions = listOf("「${first.assignment.title}」怎么做？") + suggestions
            }
        }
    }

    // 键盘弹起检测：人格区/上下文 chip 自动收起，把空间让给对话列（否则键盘占 830px 对话区被压没）
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    // 新消息平滑滑到底；键盘弹起 / 收起、人格区展开等视口变化时保持贴底（不再跳到上面的对话）。
    // 2026-10-26 改用 ChatAutoScroll（原 animateScrollToItem 只对齐最后一条的顶部，收起输入法会跳）
    // 流式回答每多出一截就跟随到底（按 40 字分桶，避免每个字都触发一次滚动动画）
    val streamText = AssistantHub.streaming.collectAsState().value[ChatHistory.KEY_STUDENT]
    ChatAutoScroll(listState, (messages.lastOrNull()?.id ?: 0L) to (streamText?.length?.div(40) ?: -1))

    // 录音计时（后端接入前 UI 演示用）
    LaunchedEffect(isRecording) {
        while (isRecording) {
            delay(1000)
            voiceSecs += 1
        }
    }

    // ── 入场动画（错峰触发） ──
    var enterPersona by remember { mutableStateOf(false) }
    var enterChips by remember { mutableStateOf(false) }
    var enterDock by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(60)
        enterPersona = true
        delay(150)
        enterChips = true
        delay(170)
        enterDock = true
    }

    // 发送：用户消息立即上屏并落盘；2026-10-24 起走真实大模型（AiClient），
    // 回答里可能带 KEWU_ACTION 指令 —— 由 AiAgent 真执行导航/代办；失败回退占位文案
    // 2026-10-26：AI 请求改由进程级 AssistantHub 发起——切到别的 tab 请求不会被取消，回答到达时
    // 若不在本页则点亮底部「助教」红点；回到本页时 pending 仍在就继续显示「正在输入中」。
    val pendingKeys by AssistantHub.pending.collectAsState()
    val aiThinking = ChatHistory.KEY_STUDENT in pendingKeys
    LaunchedEffect(Unit) {
        // 先补齐「本页组合之后、开始监听之前」已落盘的回复（极短窗口，防漏显示）
        val known = messages.map { it.id }.toSet()
        val missed = ChatHistory.loadAll(ChatHistory.KEY_STUDENT)
            .filter { it.ts >= visibleCutoff && it.id !in known && it.id != 0L }
        if (missed.isNotEmpty()) messages = messages + entriesToMsgs(missed)
        AssistantHub.incoming.collect { inc ->
            if (inc.key == ChatHistory.KEY_STUDENT && messages.none { it.id == inc.entry.id }) {
                messages = messages + ChatMsg(inc.entry.text, isUser = false, id = inc.entry.id, ts = inc.entry.ts)
            }
        }
    }
    val doSend: (String) -> Unit = { raw ->
        val text = raw.trim()
        // 允许「只发引用 + 正文为空」的情况（QQ 同款）；两者都空则不动
        if ((text.isNotEmpty() || quotePreview != null) && !aiThinking) {
            val quoted = quotePreview
            val userMsg = ChatMsg(text, isUser = true, quote = quoted,
                id = ChatHistory.nextId(), ts = nowMs())
            messages = messages + userMsg
            persistHistory(messages.filter { !it.isVoice }) // 语音消息不落盘
            input = ""
            quotePreview = null // 发出后清掉引用预览
            haptic(HapticKind.Light)
            // 多轮上下文：取最近 8 条纯文字消息（引用拼进正文）
            val history = messages.filter { !it.isVoice }.takeLast(8).map { m ->
                val body = if (m.quote != null) "（引用：${m.quote}）${m.text}" else m.text
                AiClient.Msg(if (m.isUser) "user" else "assistant", body.ifBlank { "…" })
            }
            val system = buildString {
                append("你是「小悟」，课悟 App 里学生的 AI 学习助手，正在和一名学生对话。")
                append("回答课堂疑问、作业思路、复习规划等问题；")
                append("讲解清晰、语气亲切，可适当分步；用中文；不要用 markdown 格式符号；")
                append("单次回答控制在 250 字以内，题目讲解可稍长。")
                append("不要重复自我介绍（「你好，我是小悟…」这类开场只在当天第一轮出现一次），")
                append("也不要每次回复都重复问候或复述用户的问题，直接回答内容本身。\n")
                append("学生当前情况：共 $courseCount 门课程 · $pendingCount 份作业")
                if (assignmentTitles.isNotEmpty()) {
                    append("，作业包括：")
                    append(assignmentTitles.take(6).joinToString("、"))
                }
                append("\n\n")
                append(AiAgent.studentCapability())
            }
            // 2026-10-26：流式输出——回答边生成边上屏（打字机效果），不再干等整段
            AssistantHub.askStream(ChatHistory.KEY_STUDENT) { emit ->
                // 2026-10-26：把软件里看得到的全部信息（作业截止 / 状态 / 成绩、课程、签到、录音…）给小悟
                val fullSystem = system + "\n\n以下是该学生在课悟 App 里此刻能看到的全部信息，回答时以此为准" +
                    "（问到截止时间、成绩、要交哪些作业等直接引用；没有的信息如实说明）：\n" + com.kewu.app.AiContext.student()
                val reply = AiClient.chatStream(listOf(AiClient.Msg("system", fullSystem)) + history, onDelta = emit)
                if (reply == null) {
                    // 直连 / 代理 / 8081 中转全部失败：如实告知，不再用随机占位语冒充回答
                    AssistantHub.Reply("小悟暂时连不上 AI 服务（网络不通或服务繁忙），请稍后再试～")
                } else {
                    val parsed = AiAgent.parse(reply)
                    // 执行软件操作指令（导航/代办），结果说明跟在后面一起展示
                    val note = parsed.action?.let { AiAgent.execute("student", it) }
                    AssistantHub.Reply(listOfNotNull(parsed.text.ifEmpty { null }, note).joinToString("\n"))
                }
            }
        }
    }

    // 返回键优先级：先关面板，再交给导航
    BackHandler(showAttach || isRecording) {
        panel = AssistantPanel.NONE
    }

    // 凝结出场动画值（弹簧过冲 = "加速冲上来后晃一下"）
    val personaAlpha by animateFloatAsState(if (enterPersona) 1f else 0f, tween(280), label = "personaA")
    val personaScale by animateFloatAsState(
        if (enterPersona) 1f else 0.6f,
        spring(dampingRatio = 0.55f, stiffness = 260f, visibilityThreshold = 0.0005f),
        label = "personaS",
    )
    val personaY by animateDpAsState(
        if (enterPersona) 0.dp else 26.dp,
        spring(dampingRatio = 0.55f, stiffness = 260f),
        label = "personaY",
    )
    val chipsY by animateDpAsState(
        if (enterChips) 0.dp else 18.dp,
        spring(dampingRatio = 0.55f, stiffness = 320f),
        label = "chipsY",
    )
    val dockY by animateDpAsState(
        if (enterDock) 0.dp else 84.dp,
        spring(dampingRatio = 0.52f, stiffness = 330f),
        label = "dockY",
    )
    val dockBlur by animateDpAsState(
        if (enterDock) 22.dp else 4.dp,
        spring(dampingRatio = 0.52f, stiffness = 330f),
        label = "dockBlur",
    )
    val dockTint by animateFloatAsState(
        if (enterDock) 0.38f else 0.04f,
        spring(dampingRatio = 0.52f, stiffness = 330f, visibilityThreshold = 0.0005f),
        label = "dockTint",
    )
    val dockCorner by animateDpAsState(
        if (enterDock) 28.dp else 40.dp,
        spring(dampingRatio = 0.52f, stiffness = 330f),
        label = "dockCorner",
    )

    val dockBottomPad by animateDpAsState(
        // 118 → 110：输入框整体下移 8dp（用户微调，仍安全避开底部导航托盘 ~90dp）
        // 2026-10-26：键盘弹起时 14 → 8dp（用户反馈输入框离输入法太远）
        if (imeVisible) 8.dp else 110.dp,
        spring(dampingRatio = 0.6f, stiffness = 380f),
        label = "dockBottomPad",
    )

    // 根盒原点：供长按菜单把「气泡窗口坐标」换算成「相对本页坐标」（贴气泡定位用）
    Box(
        Modifier
            .fillMaxSize()
            // 2026-10-26：输入法避让改由主界面内容区统一 imePadding（这里再加会与之叠加）
            .onGloballyPositioned { rootOrigin = it.positionInWindow() },
    ) {
        // 背景采样层：coui 页面底色（surface 灰底），与玻璃内容平级 ✓
        // 主界面内容区 imePadding 让整页（含采样层）随键盘变小，weight 对话区自动让位。
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(pageBackdrop)
                .background(cs.surface),
        )
        Column(Modifier.fillMaxSize()) {
            // ── AI 人格区 + 上下文 chip：键盘弹出时自动收起，把空间让给对话列 ──
            // ★ 2026-10-26 修「点输入框弹键盘即闪退」：上一版把这里的 fade 换成 shrinkVertically，
            //   玻璃 chip 跟着被裁剪/压到 0 高度 → 采样层尺寸为 0，RenderThread 直接崩。
            //   现在只让**非玻璃**的人格区做收起动画；玻璃 chip 在键盘弹起时直接移除、收起后直接出现，绝不参与尺寸动画。
            AnimatedVisibility(
                visible = !imeVisible,
                enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = 420f)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(150)) + fadeOut(tween(120)),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(14.dp))
                    // ── AI 人格区（非采样者，可放心 alpha/scale） ──
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .offset(y = personaY)
                            .alpha(personaAlpha)
                            .scale(personaScale),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .size(76.dp)
                                .clip(CircleShape)
                                .background(cs.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            // 品牌绿光环（draw 参数级发光，不套 graphicsLayer）
                            Canvas(Modifier.fillMaxSize()) {
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        0.62f to Color.Transparent,
                                        1.0f to KewuGreen.copy(alpha = 0.55f),
                                        center = center,
                                        radius = size.minDimension / 2f,
                                    ),
                                    radius = size.minDimension / 2f,
                                )
                            }
                            PersonGlyph(Modifier.size(34.dp), Color.White)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("小悟", color = cs.onSurface, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(7.dp))
                            Canvas(Modifier.size(7.dp)) { drawCircle(KewuGreen, radius = size.minDimension / 2f) }
                        }
                        Spacer(Modifier.height(3.dp))
                        // 2026-10-24：等待 AI 回复时顶部状态改为「小悟正在输入中…」
                        Text(
                            if (aiThinking) "小悟正在输入中…" else "KEWU AI",
                            color = cs.onSurfaceSecondary, fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            if (!imeVisible) {
                Column(Modifier.fillMaxWidth()) {
                    // ── 上下文 chip（玻璃采样者：不放进任何 alpha / 尺寸动画容器） ──
                    Row(
                        Modifier.fillMaxWidth().offset(y = chipsY),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ContextChip(backdrop = pageBackdrop, dot = cs.primary) {
                            Text("本学期 · $courseCount 门课程", color = cs.onSurface, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        ContextChip(backdrop = pageBackdrop, dot = KewuGreen) {
                            Text("待交作业 $pendingCount 项", color = cs.onSurface, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }

            // ── 对话列 ──
            // 2026-10-24：顶部下拉加载更早对话（nestedScroll 累积下拉量，越过阈值自动加载前一天）
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
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().nestedScroll(pullConnection),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "history-tip") {
                    Text(
                        when {
                            pullAcc > 10f -> "松开加载更早的对话…"
                            hasMoreHistory -> "下拉加载更早的对话"
                            else -> ""
                        },
                        color = cs.onSurfaceSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        textAlign = TextAlign.Center,
                    )
                }
                item(key = "date-divider") {
                    Text(
                        "今天",
                        color = cs.onSurfaceSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
                // 防御：同 id 只渲染一条（LazyColumn key 重复会直接崩溃，历史合并 / 回补时可能出现重复）
                messages.distinctBy { it.id }.forEach { msg ->
                    // 2026-10-24：稳定 key + animateItem —— 新消息滑入，聊天更丝滑
                    item(key = msg.id, contentType = msg.isUser) {
                        Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) { // 气泡是玻璃采样者：只做位移动画，不做淡入淡出（祖先禁 alpha）
                            if (msg.isUser) {
                                UserBubble(
                                    msg,
                                    // 只有被长按的这条持续上报坐标（滚动时菜单跟着走）；其余不报，避免每帧写状态
                                    reportBounds = msg == menuMsg,
                                    onBounds = { menuAnchor = it },
                                ) {
                                    menuMsg = msg
                                    menuIsUser = true
                                    haptic(HapticKind.Medium)
                                }
                            } else {
                                AiBubble(
                                    msg,
                                    pageBackdrop,
                                    reportBounds = msg == menuMsg,
                                    onBounds = { menuAnchor = it },
                                ) {
                                    menuMsg = msg
                                    menuIsUser = false
                                    haptic(HapticKind.Medium)
                                }
                            }
                        }
                    }
                }
                // 流式回答草稿：思考中显示占位，吐字后逐段增长；结束后由正式消息替换
                if (streamText != null) {
                    item(key = "ai-streaming") {
                        Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) { // 气泡是玻璃采样者：只做位移动画，不做淡入淡出（祖先禁 alpha）
                            AiBubble(
                                ChatMsg(streamText.ifEmpty { "小悟正在思考…" }, isUser = false, id = -1L, ts = nowMs()),
                                pageBackdrop, reportBounds = false, onBounds = {},
                            ) {}
                        }
                    }
                }
            }

            // ── 附件 / 录音面板（★ 2026-10-19 重做：单一互斥面板 + 稳定进退场） ──
            // 旧版两个 AnimatedVisibility 各自滑入滑出，连点「+」与麦克风时两片玻璃同时采样 + 布局反复
            // 回流 → 卡顿/瞬移。现在只有一个容器：panel 非空才显示；ATTACH↔VOICE 直接换内容（不重播进退场）。
            // 退场期间 panel 已变 NONE，故用 `shown` 记住最后一次非空面板，避免退场直接空掉。
            var shownPanel by remember { mutableStateOf(AssistantPanel.ATTACH) }
            LaunchedEffect(panel) {
                if (panel != AssistantPanel.NONE) shownPanel = panel
            }
            AnimatedVisibility(
                visible = panel != AssistantPanel.NONE,
                enter = slideInVertically(
                    animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
                    initialOffsetY = { it }, // 从自身高度下方升上来（不再从半高起跳）
                ), // 2026-10-26：去掉 fade（面板是玻璃采样者，祖先禁 alpha），纯滑入滑出
                exit = slideOutVertically(
                    animationSpec = tween(150),
                    targetOffsetY = { it },
                ),
            ) {
                when (shownPanel) {
                    // 2026-10-26：图片 / 文件 / 语音提问需要多模态与语音转写，当前大模型只收文字；
                    // 旧版点附件没反应、语音「发送」后落一条空语音气泡（AI 也不会回）。改为如实提示后收起。
                    AssistantPanel.ATTACH -> AttachPanel(backdrop = pageBackdrop, onPick = { label ->
                        panel = AssistantPanel.NONE
                        scope.launch { snackbar.toast("「$label」提问即将上线，先用文字描述问题吧") }
                    }) {
                        panel = AssistantPanel.NONE
                    }
                    AssistantPanel.VOICE -> VoiceRecordPanel(
                        backdrop = pageBackdrop,
                        voiceSecs = voiceSecs,
                        onCancel = { panel = AssistantPanel.NONE },
                        onSend = {
                            panel = AssistantPanel.NONE
                            scope.launch { snackbar.toast("语音提问即将上线，先用文字问小悟吧") }
                        },
                    )
                    AssistantPanel.NONE -> Unit
                }
            }

            // ── 建议提问（原课程/作业列表的新呈现；面板打开时让位） ──
            AnimatedVisibility(
                visible = !showAttach && !isRecording,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(120)),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // coui Chip（自带按压缩放 + Tint）；首条（由真实作业生成）用品牌绿浅底做强调
                    suggestions.forEachIndexed { i, s ->
                        Chip(
                            selected = false,
                            onClick = { haptic(HapticKind.Tick); doSend(s) },
                            label = s,
                            colors = if (i == 0) {
                                ChipDefaults.chipColors(containerColor = KewuGreen.copy(alpha = 0.14f))
                            } else {
                                ChipDefaults.chipColors()
                            },
                        )
                    }
                }
            }

            // ── 液态玻璃输入框：常驻导航栏上方；键盘行为由 manifest adjustResize 负责
            // （窗口整体收缩、weight 对话区自动让位；不要在此加 imePadding —— 会吃掉 weight 空间把对话区压没） ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .offset(y = dockY) // placement 位移（采样者安全）
                    .padding(horizontal = 16.dp)
                    // 底部留白：无键盘时为导航栏兜底（≈114dp）；键盘弹起导航已被键盘盖住，只需小边距
                    .padding(bottom = dockBottomPad),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    Column(Modifier.fillMaxWidth()) {
                        // ★ 引用预览（12.8）：输入框**上方**单独一条灰色小条，× 可取消
                        AnimatedVisibility(
                            visible = quotePreview != null,
                            enter = fadeIn(tween(140)) + slideInVertically(tween(180)) { -it / 2 },
                            exit = fadeOut(tween(110)) + slideOutVertically(tween(140)) { -it / 2 },
                        ) {
                            QuotePreviewBar(text = quotePreview.orEmpty()) { quotePreview = null }
                        }
                        KewuGlassCard(
                            backdrop = pageBackdrop,
                            shape = RoundedCornerShape(dockCorner),
                            blurRadius = dockBlur,
                            tintAlpha = dockTint,
                            modifier = Modifier.height(56.dp).fillMaxWidth(),
                        ) {
                            // 描边 + 内容（浅色底上拓玻璃边界的关键）
                            DockStroke(RoundedCornerShape(28.dp))
                            Row(
                                Modifier.fillMaxSize().padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RoundIconBtn(backdrop = pageBackdrop, onClick = {
                                    panel = if (panel == AssistantPanel.ATTACH) {
                                        AssistantPanel.NONE
                                    } else {
                                        AssistantPanel.ATTACH
                                    }
                                }) { GlyphIcon(COUIIcons.Add) }
                                Box(
                                    Modifier.weight(1f).padding(horizontal = 9.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    if (input.isEmpty()) {
                                        Text("问点什么…", color = cs.onSurfaceSecondary, fontSize = 14.sp)
                                    }
                                    BasicTextField(
                                        value = input,
                                        onValueChange = { input = it },
                                        textStyle = TextStyle(color = cs.onSurface, fontSize = 14.sp),
                                        cursorBrush = SolidColor(cs.primary),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                        keyboardActions = KeyboardActions(onSend = { doSend(input) }),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                RoundIconBtn(backdrop = pageBackdrop, onClick = {
                                    if (panel == AssistantPanel.VOICE) {
                                        panel = AssistantPanel.NONE
                                    } else {
                                        voiceSecs = 0
                                        panel = AssistantPanel.VOICE
                                    }
                                }) { GlyphIcon(COUIIcons.Mic) }
                                Spacer(Modifier.width(4.dp))
                                SendBtn(
                                    backdrop = pageBackdrop,
                                    // 有输入或有待发引用时都可发送（仅吐槽字的灰态仍由兄弟逻辑控制）
                                    enabled = input.isNotBlank() || quotePreview != null,
                                    onSend = { doSend(input) },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        // ── 长按气泡的 QQ 式操作菜单（液态玻璃 + coui Card 选项） ──
        val activeMenuMsg = menuMsg
        if (activeMenuMsg != null) {
            // 气泡窗口坐标 → 相对本页根盒的坐标（菜单遮罩是根盒的 fillMaxSize 子节点，同坐标系）
            val relRect = menuAnchor?.let { r ->
                Rect(
                    r.left - rootOrigin.x, r.top - rootOrigin.y,
                    r.right - rootOrigin.x, r.bottom - rootOrigin.y,
                )
            }
            LiquidGlassActionMenu(
                visible = true,
                anchor = if (menuIsUser) GlassMenuAnchor.TopEnd else GlassMenuAnchor.TopStart,
                // 贴气泡正上方（用户 2026-10-19：菜单离气泡太远，要贴着气泡）
                anchorRect = relRect,
                actions = listOf(
                    GlassMenuAction("复制", MenuGlyphKind.Copy) {
                        clipboard.setText(AnnotatedString(activeMenuMsg.displayText))
                        scope.launch { snackbar.toast("已复制") }
                    },
                    GlassMenuAction("引用", MenuGlyphKind.Quote) {
                        // ★ 引用（12.8）：不再把原文塞进输入框，改为在输入框上方单独显示引用条
                        quotePreview = activeMenuMsg.displayText
                    },
                    GlassMenuAction("分享", MenuGlyphKind.Share) {
                        // ★ 分享（12.8）：不再复制内容，直接调系统分享面板（可分享到任意 App）
                        shareText(activeMenuMsg.displayText)
                    },
                ),
                onDismiss = { menuMsg = null },
            )
        }
    }
}

// ───────────────────────── 数据与常量 ─────────────────────────

private data class ChatMsg(
    val text: String,
    val isUser: Boolean,
    val isVoice: Boolean = false,
    val voiceSecs: Int = 0,
    val formula: String? = null,
    /** 稳定 id：列表动画 key 与聊天记录持久化用（2026-10-24）。 */
    val id: Long = 0L,
    /** 发送时刻 epoch 毫秒；恢复的历史带原值。 */
    val ts: Long = 0L,
    /**
     * 被引用的原文（第三轮 12.8）：QQ 式引用 —— 发送时随消息带上，
     * 气泡顶部渲染一块灰色引用小条；null 表示这条消息没有引用。
     */
    val quote: String? = null,
) {
    /** 复制/引用/分享用的纯文本（语音消息给可读描述）。 */
    val displayText: String
        get() = if (isVoice) "语音消息 · ${fmtClock(voiceSecs)}" else text
}

/** 手写 mm:ss（commonMain 禁 String.format）。 */
private fun two(n: Int) = if (n < 10) "0$n" else "$n"

/** 当前 epoch 毫秒（2026-10-24 聊天记录持久化用）。 */
private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
private fun fmtClock(totalSec: Int) = "${two(totalSec / 60)}:${two(totalSec % 60)}"

// ───────────────────────── 通用按压 Q 弹 ─────────────────────────

/**
 * 按压回弹：按下 1→0.88，低阻尼弹簧带回冲 —— 全组件统一的「液态玻璃 Q 弹」。
 * ⚠️ 只能用于**非采样者**节点；玻璃件请把本修饰符加在玻璃**内部**内容上。
 */
@Composable
private fun Modifier.pressBounce(onClick: () -> Unit): Modifier {
    val pressed = remember { mutableStateOf(false) }
    // ★ 稳定手势：onClick 内联 lambda 每次重组身份都变，用 pointerInput(Unit) + rememberUpdatedState
    //   避免快速点击时手势监听被反复 dispose/recreate 导致掉帧、手势被吞（第三轮 12.5 修复①）。
    val onClickState = rememberUpdatedState(onClick)
    val scaleV by animateFloatAsState(
        targetValue = if (pressed.value) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.32f, stiffness = 480f, visibilityThreshold = 0.0005f),
        label = "pressBounce",
    )
    return this
        .scale(scaleV)
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    pressed.value = true
                    haptic(HapticKind.Tick)
                    tryAwaitRelease()
                    pressed.value = false
                },
                onTap = { onClickState.value() },
            )
        }
}

/**
 * 气泡按压 + 长按：按下 Q 弹缩放（0.90，低阻尼弹簧），长按触发 QQ 式操作菜单。
 * 单一 detectTapGestures 同时承载 onTap/onLongPress，避免 pressBounce 与 combinedClickable 抢手势。
 */
@Composable
private fun Modifier.bubblePress(onLongPress: () -> Unit): Modifier {
    val pressed = remember { mutableStateOf(false) }
    // ★ 稳定手势：onLongPress 内联 lambda 每次重组身份都变，用 pointerInput(Unit) + rememberUpdatedState
    //   避免快速交互时手势监听被反复重建（第三轮 12.5 修复①）。
    val onLongPressState = rememberUpdatedState(onLongPress)
    val scaleV by animateFloatAsState(
        targetValue = if (pressed.value) 0.90f else 1f,
        animationSpec = spring(dampingRatio = 0.32f, stiffness = 480f, visibilityThreshold = 0.0005f),
        label = "bubblePress",
    )
    return this
        .scale(scaleV)
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    pressed.value = true
                    haptic(HapticKind.Tick)
                    tryAwaitRelease()
                    pressed.value = false
                },
                onLongPress = { onLongPressState.value() },
            )
        }
}

// ───────────────────────── 子组件 ─────────────────────────

/**
 * 浅色底玻璃描边：外面看得到的「框」。
 * 外圈极淡暗轮廓 + 内圈白高光（SrcOver；Screen 在浅色 M3 底会被洗白 —— 导航托盘同款结论）。
 */
@Composable
private fun DockStroke(shape: RoundedCornerShape) {
    val cs = COUITheme.colorScheme
    Canvas(
        Modifier
            .fillMaxSize()
            .border(1.dp, cs.dividerLine, shape),
    ) {
        val w = size.width
        val h = size.height
        val edge = h * 0.018f
        drawRoundRect(
            Color.Black.copy(alpha = 0.05f),
            topLeft = Offset(edge / 2f, edge / 2f),
            size = Size(w - edge, h - edge),
            cornerRadius = CornerRadius(h / 2f),
            style = Stroke(width = edge),
        )
        val rimW = h * 0.035f
        drawRoundRect(
            Color.White.copy(alpha = 0.30f),
            topLeft = Offset(rimW, rimW),
            size = Size(w - rimW * 2f, h - rimW * 2f),
            cornerRadius = CornerRadius(h / 2f - rimW),
            style = Stroke(width = rimW),
        )
    }
}

/**
 * 顶部上下文 chip：小玻璃胶囊（描边 + 圆点 + 文字，内容可 Q 弹按压）。
 * ★ 2026-10-23 coui 化：本体是 KewuGlassCard（液态玻璃件）→ 按「除液态玻璃外全部 coui」原则保留玻璃外壳，
 *   内部文字已是 coui Text；非玻璃的建议提问 chip 才换 coui Chip。
 */
@Composable
private fun ContextChip(
    backdrop: LayerBackdrop,
    dot: Color,
    content: @Composable () -> Unit,
) {
    val cs = COUITheme.colorScheme
    Box(
        Modifier
            .border(1.dp, cs.dividerLine, RoundedCornerShape(15.dp)),
    ) {
        KewuGlassCard(
            backdrop = backdrop,
            shape = RoundedCornerShape(15.dp),
            blurRadius = 14.dp,
            tintAlpha = 0.30f,
        ) {
            Row(
                Modifier.pressBounce { /* chip 仅展示上下文，暂无下钻 */ }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.size(6.dp)) { drawCircle(dot, radius = size.minDimension / 2f) }
                Spacer(Modifier.width(6.dp))
                content()
            }
        }
    }
}

/**
 * AI 气泡（2026-10-18 强化）：**整颗液态玻璃包裹** —— 足够大的圆角（尾部小角 + 22dp）、
 * 22dp 毛玻璃 blur + 奶白磨砂 tint + outlineVariant 描边，透出身后列表内容。
 * 长按 → QQ 式操作菜单（复制/引用/分享，LiquidGlassActionMenu）。
 * 入场 = 玻璃凝结（tint/blur 从近零插值）+ placement 上浮 + 内容透明 —— 采样者安全。
 */
@Composable
private fun AiBubble(
    msg: ChatMsg,
    backdrop: LayerBackdrop,
    /** 被长按选中：持续上报气泡矩形（窗口坐标）供菜单贴脸定位；滚动时也跟着上报 */
    reportBounds: Boolean,
    onBounds: (Rect) -> Unit,
    onLongPress: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    val shape = RoundedCornerShape(topStart = 8.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 22.dp)
    val p = rememberEnterProgress(0, baseDelayMs = 60L)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                .border(1.dp, cs.dividerLine, shape)
                .onGloballyPositioned { if (reportBounds) onBounds(it.boundsInWindow()) },
        ) {
            KewuGlassCard(
                backdrop = backdrop,
                shape = shape,
                blurRadius = glassCondenseBlur(p, 22f),
                tintAlpha = 0.36f * p,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .glassEnter(p, 30f),
            ) {
                Column(
                    Modifier
                        .alpha(p)
                        .bubblePress(onLongPress)
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(6.dp)) { drawCircle(KewuGreen, radius = size.minDimension / 2f) }
                        Spacer(Modifier.width(5.dp))
                        Text("小悟", color = cs.onSurfaceSecondary, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(msg.text, color = cs.onSurface, fontSize = 13.5.sp, lineHeight = 20.sp)
                    msg.formula?.let { f ->
                        Spacer(Modifier.height(7.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = cs.secondaryContainer,
                            border = BorderStroke(1.dp, KewuGreen.copy(alpha = 0.4f)),
                        ) {
                            Text(
                                f, color = cs.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 用户气泡：coui Surface 品牌色实心填充 / 语音气泡带 mic + 时长，右对齐；同支持长按菜单。 */
@Composable
private fun UserBubble(
    msg: ChatMsg,
    /** 被长按选中：持续上报气泡矩形（窗口坐标）供菜单贴脸定位；滚动时也跟着上报 */
    reportBounds: Boolean,
    onBounds: (Rect) -> Unit,
    onLongPress: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 8.dp, bottomEnd = 22.dp, bottomStart = 22.dp)
    val p = rememberEnterProgress(0, baseDelayMs = 60L)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        // Surface 承担 clip + 品牌色底；按压缩放仍只作用于内部内容（与改造前一致）
        Surface(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .riseEnter(p, 26f)
                .onGloballyPositioned { if (reportBounds) onBounds(it.boundsInWindow()) },
            shape = shape,
            color = cs.primary,
            contentColor = cs.onPrimary,
        ) {
        Column(
            Modifier
                .bubblePress(onLongPress)
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            // ★ 引用（12.8）：气泡顶部渲染一块半透明引用小条（被引用原文）
            msg.quote?.let { quoted ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(cs.onPrimary.copy(alpha = 0.16f))
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                ) {
                    // 左侧竖条：一眼认出是"引用"而非正文
                    Box(
                        Modifier
                            .width(2.5.dp)
                            .heightIn(min = 22.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(cs.onPrimary.copy(alpha = 0.5f)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        quoted,
                        color = cs.onPrimary.copy(alpha = 0.72f),
                        fontSize = 11.sp, lineHeight = 15.sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(7.dp))
            }
            if (msg.isVoice) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(COUIIcons.Mic, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "语音消息 · ${fmtClock(msg.voiceSecs)}",
                        color = cs.onPrimary,
                        fontSize = 13.sp,
                    )
                }
            } else {
                Text(msg.text, color = cs.onPrimary, fontSize = 13.5.sp, lineHeight = 20.sp)
            }
        }
        }
    }
}

/**
 * QQ 式引用预览条（第三轮 12.8）：输入框**上方**单独显示「引用了什么」，
 * 灰色填充质地 + 小字 + 左侧品牌色竖条，右侧 × 一键取消引用。
 * （旧版是把原文包「」塞进输入框，效果差且污染输入内容。）
 */
@Composable
private fun QuotePreviewBar(text: String, onCancel: () -> Unit) {
    val cs = COUITheme.colorScheme
    // coui Surface 承载底色 + 描边；× 换 coui IconButton + Close 图标
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = cs.surfaceContainer,
        border = BorderStroke(1.dp, cs.dividerLine),
    ) {
        Row(
            Modifier.padding(start = 11.dp, top = 7.dp, bottom = 7.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(26.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(KewuGreen),
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text("引用", color = cs.onSurfaceSecondary, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
                Text(
                    text, color = cs.onSurfaceSecondary,
                    fontSize = 11.5.sp, lineHeight = 15.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            // × 取消（带轻震动）
            IconButton(
                onClick = { haptic(HapticKind.Tick); onCancel() },
                minWidth = 32.dp,
                minHeight = 32.dp,
                cornerRadius = 16.dp,
            ) {
                Icon(COUIIcons.Close, contentDescription = "取消引用", tint = cs.onSurfaceSecondary, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** 输入框内圆形玻璃小钮（照片/文件、语音共用；外圈 Q 弹 + 描边）。 */
@Composable
private fun RoundIconBtn(
    backdrop: LayerBackdrop,
    onClick: () -> Unit,
    glyph: @Composable () -> Unit,
) {
    val cs = COUITheme.colorScheme
    // ★ 第三轮 12.5：按压缩放只加在**内部图标**（非采样者），玻璃件保持静止
    // —— 否则外层 scale 裹住玻璃采样者，快速点击触发反复重采样卡顿（且踩 coui 硬约束）。
    // 手势用 pointerInput(Unit)+rememberUpdatedState，稳定不重建。
    val pressed = remember { mutableStateOf(false) }
    val iconScale by animateFloatAsState(
        targetValue = if (pressed.value) 0.82f else 1f,
        animationSpec = spring(dampingRatio = 0.32f, stiffness = 480f, visibilityThreshold = 0.0005f),
        label = "roundBtnIcon",
    )
    val onClickState = rememberUpdatedState(onClick)
    Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().border(1.dp, cs.dividerLine, CircleShape)) {
            KewuGlassCard(
                backdrop = backdrop,
                shape = CircleShape,
                blurRadius = 12.dp,
                tintAlpha = 0.24f,
                modifier = Modifier.fillMaxSize(),
            ) {
                // 玻璃件保持静止，仅内部图标随按压 Q 弹
                Box(Modifier.fillMaxSize().scale(iconScale), contentAlignment = Alignment.Center) { glyph() }
            }
        }
        // 稳定的手势监听层（盖在最上，吞掉点击不干扰内部静态玻璃）
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            pressed.value = true
                            haptic(HapticKind.Tick)
                            tryAwaitRelease()
                            pressed.value = false
                        },
                        onTap = { onClickState.value() },
                    )
                },
        )
    }
}

/** 人物 icon（头 + 肩，实心白） —— AI 头像同款。 */
@Composable
private fun PersonGlyph(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier) {
        val s = size.minDimension
        drawCircle(color, radius = s * 0.17f, center = Offset(s * 0.5f, s * 0.36f))
        drawArc(
            color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(s * 0.22f, s * 0.52f),
            size = Size(s * 0.56f, s * 0.52f),
        )
    }
}

/** 输入框内小钮的 coui 图标（原自绘 Plus / Mic 已换 coui-icons）。 */
@Composable
private fun GlyphIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = COUITheme.colorScheme.onSurfaceSecondary, modifier = Modifier.size(20.dp))
}

/**
 * 发送钮：绿芯液态玻璃圆钮（KewuGreen 径向渐变芯 —— 白图标在浅色底上才看得见）
 * + 导航泡泡同款 spectralBrush 彩虹双带 + 描边。
 * ★ 2026-10-19 定稿：按压反馈 = **内部箭头放大一圈再回缩** + **外层彩虹镶边明显外扩**。
 *   不再做整钮横向拉宽（用户明确不要）：外壳保持不动，动的是"里面的箭头"和"外面那层彩虹"。
 *   低阻尼 spring + 显式小阈值 → 松手回缩带过冲，Q 弹。玻璃件只在交互时变换（非首次组合），
 *   规避 coui 首帧 SIGSEGV。
 */
@Composable
private fun SendBtn(
    backdrop: LayerBackdrop,
    /** 可发送（有输入内容或有待发引用）；不可发送时罩一层白纱示意置灰。 */
    enabled: Boolean,
    onSend: () -> Unit,
) {
    val pressed = remember { mutableStateOf(false) }
    // ① 内部箭头：按下放大一圈（×1.34），松手回缩 —— 低阻尼 spring 带回弹过冲
    val iconScale by animateFloatAsState(
        targetValue = if (pressed.value) 1.34f else 1f,
        animationSpec = spring(dampingRatio = 0.30f, stiffness = 380f, visibilityThreshold = 0.0005f),
        label = "sendIconScale",
    )
    // ② 外层彩虹镶边：按下明显外扩 + 加粗（主要按压反馈在这里，比箭头变化更抢眼）
    val rimGrow by animateFloatAsState(
        targetValue = if (pressed.value) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.34f, stiffness = 320f, visibilityThreshold = 0.0005f),
        label = "sendRimGrow",
    )
    val rainbow = if (pressed.value) 0.85f else 0.55f

    // 外壳不再整体缩放 —— 只动内部箭头与彩虹镶边
    Box(Modifier.size(46.dp)) {
        Box(Modifier.fillMaxSize().border(1.dp, KewuGreen.copy(alpha = 0.55f), CircleShape)) {
            KewuGlassCard(
                backdrop = backdrop,
                shape = CircleShape,
                blurRadius = 14.dp,
                tintAlpha = if (pressed.value) 0.10f else 0.14f,
                magnify = 1.28f, // 导航泡泡同款"内容透过来还被放大"的透镜感
                modifier = Modifier.fillMaxSize(),
            ) {
                // 绿芯 + 棱边 + 彩虹双带（复刻导航泡泡 Canvas 画法；彩虹 SrcOver 不用 Screen——浅色底会洗白）
                Canvas(Modifier.fillMaxSize()) {
                    val h = size.height
                    val r = h / 2f
                    drawCircle(
                        brush = Brush.radialGradient(
                            0.0f to Color(0xFF2BFF9E).copy(alpha = if (pressed.value) 1f else 0.92f),
                            0.72f to KewuGreen.copy(alpha = if (pressed.value) 0.85f else 0.70f),
                            1.0f to KewuGreen.copy(alpha = 0.18f),
                            center = center,
                            radius = r,
                        ),
                        radius = r,
                    )
                    // 外圈：极淡暗轮廓（浅色 M3 背景上给玻璃一个边界）
                    val edge = h * 0.012f
                    drawCircle(Color.Black.copy(alpha = 0.06f), radius = r - edge / 2f, style = Stroke(width = edge))
                    // 内圈：白高光（棱边反光；浅色底用 SrcOver 低透明度）
                    val rimW = h * 0.040f
                    drawCircle(Color.White.copy(alpha = 0.35f), radius = r - rimW, style = Stroke(width = rimW))
                    // 彩虹主带：贴白高光内侧、细而亮。★ 按下时 inset 变小 → 半径外扩，同时加粗
                    val mainInset = h * (0.058f - 0.032f * rimGrow)
                    drawCircle(
                        brush = spectralBrush(alpha = 0.55f * rainbow),
                        radius = r - mainInset,
                        style = Stroke(width = h * (0.026f + 0.012f * rimGrow)),
                    )
                    // 彩虹次带：稍靠内、宽而柔。同样随 rimGrow 外扩加粗
                    val softInset = h * (0.095f - 0.040f * rimGrow)
                    drawCircle(
                        brush = spectralBrush(alpha = 0.26f * rainbow),
                        radius = r - softInset,
                        style = Stroke(width = h * (0.030f + 0.014f * rimGrow)),
                    )
                }
            }
        }
        // 手势层 + 内部箭头（★ 箭头随按压放大一圈再回缩；外壳不缩放）
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            pressed.value = true
                            haptic(HapticKind.Tick)
                            tryAwaitRelease()
                            pressed.value = false
                        },
                        onTap = { onSend() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(19.dp).scale(iconScale)) {
                val s = size.minDimension
                val plane = Path().apply {
                    moveTo(s * 0.08f, s * 0.50f)
                    lineTo(s * 0.92f, s * 0.10f)
                    lineTo(s * 0.58f, s * 0.90f)
                    lineTo(s * 0.44f, s * 0.60f)
                    close()
                }
                drawPath(plane, Color.White)
            }
        }
        // 不可发送时罩一层白纱示意置灰（真正的空串拦截在 doSend 里）
        if (!enabled) {
            Box(Modifier.matchParentSize().clip(CircleShape).background(Color.White.copy(alpha = 0.25f)))
        }
    }
}

// ───────────────────────── 附件 / 录音面板 ─────────────────────────

/** QQ 式附件面板：相册 / 拍照 / 文件（后端接入前点击仅反馈 + 收起）。 */
@Composable
private fun AttachPanel(backdrop: LayerBackdrop, onPick: (String) -> Unit, onClose: () -> Unit) {
    val cs = COUITheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 6.dp)
            .border(1.dp, cs.dividerLine, RoundedCornerShape(20.dp)),
    ) {
        KewuGlassCard(
            backdrop = backdrop,
            shape = RoundedCornerShape(20.dp),
            blurRadius = 20.dp,
            tintAlpha = 0.36f,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AttachOption("相册", COUIIcons.Photos) { onPick("图片") }
                AttachOption("拍照", COUIIcons.Scan) { onPick("拍照") }
                AttachOption("文件", COUIIcons.File) { onPick("文件") }
            }
        }
    }
}

@Composable
private fun AttachOption(label: String, icon: ImageVector, onClick: () -> Unit) {
    val cs = COUITheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.pressBounce { onClick() },
    ) {
        Surface(
            modifier = Modifier.size(56.dp),
            shape = CircleShape,
            color = cs.secondaryContainer,
            border = BorderStroke(1.dp, cs.dividerLine),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = cs.onSurfaceSecondary, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = cs.onSurfaceSecondary, fontSize = 11.sp)
    }
}

/** 声纹波纹录音面板：取消 / 波纹 / 计时 / 发送（发送落一条语音消息气泡）。 */
@Composable
private fun VoiceRecordPanel(
    backdrop: LayerBackdrop,
    voiceSecs: Int,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 6.dp)
            .border(1.dp, cs.dividerLine, RoundedCornerShape(20.dp)),
    ) {
        KewuGlassCard(
            backdrop = backdrop,
            shape = RoundedCornerShape(20.dp),
            blurRadius = 20.dp,
            tintAlpha = 0.36f,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 取消（coui IconButton 灰底圆钮）
                IconButton(
                    onClick = { haptic(HapticKind.Tick); onCancel() },
                    backgroundColor = cs.secondaryContainer,
                    minWidth = 38.dp,
                    minHeight = 38.dp,
                    cornerRadius = 19.dp,
                ) {
                    Icon(COUIIcons.Close, contentDescription = "取消", tint = cs.onSurfaceSecondary, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(14.dp))
                // 声纹波纹
                VoiceWave(active = true)
                Spacer(Modifier.width(10.dp))
                Text(fmtClock(voiceSecs), color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                // 发送（coui IconButton 品牌绿圆钮）
                IconButton(
                    onClick = { haptic(HapticKind.Light); onSend() },
                    backgroundColor = KewuGreen,
                    minWidth = 38.dp,
                    minHeight = 38.dp,
                    cornerRadius = 19.dp,
                ) {
                    Icon(COUIIcons.Ok, contentDescription = "发送语音", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** 声纹波纹：5 根错峰起伏的小竖条（infinite 过渡，纯 draw 动画）。 */
@Composable
private fun VoiceWave(active: Boolean) {
    val color = if (active) KewuGreen else KewuGreen.copy(alpha = 0.4f)
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until 5) {
            val t = rememberInfiniteTransition(label = "wave$i")
            val f by t.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(320 + i * 80, easing = FastOutSlowInEasing),
                    RepeatMode.Reverse,
                ),
                label = "bar$i",
            )
            Box(
                Modifier
                    .width(4.dp)
                    .height((6 + 18 * f).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}
