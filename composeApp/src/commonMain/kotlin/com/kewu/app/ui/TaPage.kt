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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuGreen
import com.kewu.app.haptic
import com.kewu.app.shareText
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.isLight
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
import io.github.suqi8.coui.kmp.icon.extended.Edit
import io.github.suqi8.coui.kmp.icon.extended.File
import io.github.suqi8.coui.kmp.icon.extended.Mic
import io.github.suqi8.coui.kmp.icon.extended.Ok
import io.github.suqi8.coui.kmp.icon.extended.Phone
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
            image = e.image, voice = e.voice, isVoice = e.voice != null, voiceSecs = e.voiceSecs,
            // ★ V7：从历史恢复 B 站推荐标记，切页 / 下拉加载更早消息后卡片仍在
            biliKeyword = e.biliKeyword,
        )
    }
    fun persistHistory(current: List<ChatMsg>) {
        // 图片 / 语音消息由 ChatMediaHub 落盘（上传完成后会换成 OBS 链接），这里不写，免得旧状态覆盖新链接
        val entries = current.filter { it.image == null && !it.isVoice }.map { m ->
            ChatHistory.Entry(
                id = if (m.id != 0L) m.id else ChatHistory.nextId(),
                role = if (m.isUser) "user" else "assistant",
                text = m.text, ts = if (m.ts != 0L) m.ts else nowMs(), quote = m.quote,
                // ★ V7：回写历史时别把 B 站标记抹掉（upsert 按 id 覆盖，漏写会直接丢卡片）
                biliKeyword = m.biliKeyword,
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
                            // ★ C1（2026-11-05）：无聊天记录首次进入只有这一条介绍语时，也带上当前时间，
                            // 让微信风格时间条显示「现在」的时刻，表示 AI 是刚刚发的（否则 ts=0 不显示时间）。
                            ts = nowMs(),
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
    // B 站学习视频浏览面板（2026-11-02）：快捷提问行「B站视频」chip 打开
    var showBiliPanel by remember { mutableStateOf(false) }
    // P7 AI 语音通话全屏层：人格区电话钮进入
    var showVoiceCall by remember { mutableStateOf(false) }
    // ★ 2026-11-04（U1）：通话层是页内全屏层，底部导航在主界面外层仍会渲染 →
    //   与通话页底部「说话/挂断」按钮重合。通话期间通知 MainScreen 收起导航，退出/页面销毁恢复。
    //   navHidden 的收起动画 + 手势禁用已在 MainScreen/AppleLiquidGlassNavBar 内实现。
    LaunchedEffect(showVoiceCall) { onNavHide(showVoiceCall) }
    DisposableEffect(Unit) { onDispose { onNavHide(false) } }
    // 助教发图 / 发语音（2026-11-03）：待发图片（本地路径）+ 录音控制器 + 媒体任务进度
    var stagedImage by remember { mutableStateOf<String?>(null) }
    val recorder = rememberChatVoiceRecorder()
    var finishingVoice by remember { mutableStateOf(false) }
    val mediaBusy = ChatHistory.KEY_STUDENT in ChatMediaHub.busy.collectAsState().value
    val mediaPatches by ChatMediaHub.patches.collectAsState()
    // 派生布尔：沿用原变量名，其它逻辑（返回键/建议条/录音计时）无需改
    val showAttach = panel == AssistantPanel.ATTACH
    val isRecording = panel == AssistantPanel.VOICE

    // ── 长按气泡的 QQ 式操作菜单 + 轻提示（复制/引用/分享） ──
    // 菜单状态与贴气泡定位见共用组件 ChatMenuState / ChatBubbleMenu（KewuAiChat.kt）
    val menu = rememberChatMenuState()
    // 2026-11-04（P4）：文字选择状态（SelectTextHelper 风格）——长按文字消息进入，工具条渲染在页面根
    val textSelect = remember { ChatTextSelectState() }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }  // 页面根盒的窗口原点
    // 轻提示走全局 coui Snackbar（原自建中央胶囊已下线）
    val snackbar = LocalKewuSnackbar.current

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

    // ★ W1：人格区/chip 已删，键盘弹起检测（density / imeVisible）不再需要；
    //   输入法避让由主界面内容区统一 imePadding + ChatAutoScroll 负责。

    // 新消息平滑滑到底；键盘弹起 / 收起、人格区展开等视口变化时保持贴底（不再跳到上面的对话）。
    // 2026-10-26 改用 ChatAutoScroll（原 animateScrollToItem 只对齐最后一条的顶部，收起输入法会跳）
    // 流式回答每多出一截就跟随到底（按 40 字分桶，避免每个字都触发一次滚动动画）
    val streamText = AssistantHub.streaming.collectAsState().value[ChatHistory.KEY_STUDENT]
    // 2026-11-04：biliLoadedTick —— AI 回答下方的 B 站视频卡异步加载完成时 +1，作为 forceKey 强制滚到底
    //（用户翻上去看回答时 stick=false，单靠「内容长高」监听不会滚；视频就是给现在看的，必须拉下去）
    var biliLoadedTick by remember { mutableIntStateOf(0) }
    ChatAutoScroll(listState, (messages.lastOrNull()?.id ?: 0L) to (streamText?.length?.div(40) ?: -1), forceKey = biliLoadedTick)

    // 上传 / 识别进度回到页面：把补丁合进对应消息
    LaunchedEffect(mediaPatches) {
        if (mediaPatches.isNotEmpty() && messages.any { it.id in mediaPatches }) {
            messages = messages.map { m -> mediaPatches[m.id]?.let { ChatMediaHub.apply(m, it) } ?: m }
        }
    }
    // 面板被关掉（返回键 / 切到附件面板）时还在录 → 丢弃这段录音
    LaunchedEffect(panel) {
        if (panel != AssistantPanel.VOICE && recorder.recording && !finishingVoice) recorder.cancel()
    }

    // ★ W1（2026-11-04 晚 3）：人格区与上下文 chip 整块删除，原「错峰入场动画」状态一并移除。

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
                // ★ V7：优先用已落盘的标记，payload 仅作旧链路兜底
                val kw = inc.entry.biliKeyword
                    ?: (inc.payload as? BiliSuggest)?.keyword?.takeIf { it.isNotBlank() }
                // ★ X2（2026-11-05）：修复语音通话后气泡角色错乱——按 entry.role 判定，
                // 否则经 deliverVoiceTurn→incoming 广播的「用户说的话」被当成 AI 气泡。
                messages = messages + ChatMsg(inc.entry.text, isUser = inc.entry.role == "user", id = inc.entry.id, ts = inc.entry.ts, biliKeyword = kw)
            }
        }
    }
    /**
     * 把 [current]（刚发出的用户消息，已完成上传 / 识别）连同最近对话交给小悟。[images] 为这条消息的图片（OBS 链接）。
     * 2026-11-03 从 doSend 抽出：文字、图片、语音三种提问共用。
     */
    fun askAi(current: ChatMsg, images: List<String>) {
        // 多轮上下文：最近 8 条已完成的消息（引用拼进正文；语音用识别文字，图片用配文）
        fun body(m: ChatMsg): String {
            val t = if (m.image != null) m.text.ifBlank { "（发了一张图片）" } else m.text
            return if (m.quote != null) "（引用：${m.quote}）$t" else t
        }
        val earlier = messages.filter {
            it.id != current.id && it.mediaState == ChatMediaState.DONE && !(it.isVoice && it.text.isBlank())
        }.takeLast(7)
        val history = earlier.map { m ->
            AiClient.Msg(if (m.isUser) "user" else "assistant", body(m).ifBlank { "…" })
        } + AiClient.Msg("user", body(current).ifBlank { "…" }, images)
        run {
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
                append(BILI_TAG_RULE)
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
                    // B 站视频只在模型判断「学生在问不懂的知识点」时才推（回答末尾的 [[BILI:关键词]] 标记）
                    val (body, biliKw) = splitBiliTag(parsed.text)
                    AssistantHub.Reply(
                        listOfNotNull(body.ifEmpty { null }, note).joinToString("\n"),
                        payload = biliKw?.let { BiliSuggest(it) },
                    )
                }
            }
        }
    }

    // ── 图片 / 语音：上传、识别在进程级协程里跑，完成后再问小悟 ──
    fun startImageJob(m: ChatMsg) {
        ChatMediaHub.run(ChatHistory.KEY_STUDENT) {
            val local = m.image ?: return@run
            val (url, err) = if (local.startsWith("http")) local to null
            else com.kewu.app.KewuApi.uploadChatImage(local, guessImageMime(local))
            if (url == null) {
                ChatMediaHub.patch(ChatMediaHub.Patch(m.id, ChatMediaState.FAILED, error = err ?: "发送失败"))
                snackbar.toast(err ?: "图片没发出去，点图片重试")
                return@run
            }
            val done = m.copy(image = url, mediaState = ChatMediaState.DONE, mediaError = null)
            ChatMediaHub.patch(ChatMediaHub.Patch(m.id, ChatMediaState.DONE, image = url))
            ChatMediaHub.persist(ChatHistory.KEY_STUDENT, done)
            askAi(done, listOf(url))
        }
    }
    /** [liveText]：实时转录已得到的文字（2026-11-03），有就直接问 AI、不再上传整段录音转写；重试 / 实时失败时为 null。 */
    fun startVoiceJob(m: ChatMsg, liveText: String? = null) {
        ChatMediaHub.run(ChatHistory.KEY_STUDENT) {
            val local = m.voice ?: return@run
            if (liveText != null) {
                val text = liveText
                val done = m.copy(text = text, mediaState = ChatMediaState.DONE, mediaError = null)
                ChatMediaHub.patch(ChatMediaHub.Patch(m.id, ChatMediaState.DONE, voice = local, text = text))
                ChatMediaHub.persist(ChatHistory.KEY_STUDENT, done)
                askAi(done, emptyList())
                return@run
            }
            val r = com.kewu.app.KewuApi.sendChatVoice(local)
            val text = r.text.trim()
            if (r.url == null || text.isEmpty()) {
                val why = if (r.url == null) r.error ?: "发送失败" else "没听清"
                ChatMediaHub.patch(ChatMediaHub.Patch(m.id, ChatMediaState.FAILED, error = why))
                return@run
            }
            val done = m.copy(voice = r.url, text = text, mediaState = ChatMediaState.DONE, mediaError = null)
            ChatMediaHub.patch(ChatMediaHub.Patch(m.id, ChatMediaState.DONE, voice = r.url, text = text))
            ChatMediaHub.persist(ChatHistory.KEY_STUDENT, done)
            askAi(done, emptyList())
        }
    }
    val retryMedia: (ChatMsg) -> Unit = { m ->
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

    val doSend: (String) -> Unit = { raw ->
        val text = raw.trim()
        val img = stagedImage
        when {
            aiThinking || mediaBusy -> Unit
            img != null -> {
                // 图片（可带配文 / 引用）：先上屏（本地图 + 转圈），上传完再问
                val m = ChatMsg(text, isUser = true, quote = quotePreview, image = img,
                    mediaState = ChatMediaState.SENDING, id = ChatHistory.nextId(), ts = nowMs())
                messages = messages + m
                ChatMediaHub.persist(ChatHistory.KEY_STUDENT, m)
                input = ""
                quotePreview = null
                stagedImage = null
                haptic(HapticKind.Light)
                startImageJob(m)
            }
            // 允许「只发引用 + 正文为空」的情况（QQ 同款）；两者都空则不动
            text.isNotEmpty() || quotePreview != null -> {
                val userMsg = ChatMsg(text, isUser = true, quote = quotePreview,
                    id = ChatHistory.nextId(), ts = nowMs())
                messages = messages + userMsg
                persistHistory(messages)
                input = ""
                quotePreview = null // 发出后清掉引用预览
                haptic(HapticKind.Light)
                askAi(userMsg, emptyList())
            }
        }
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
                        val m = ChatMsg("", isUser = true, isVoice = true, voiceSecs = r.second, voice = r.first,
                            mediaState = ChatMediaState.SENDING, id = ChatHistory.nextId(), ts = nowMs())
                        messages = messages + m
                        ChatMediaHub.persist(ChatHistory.KEY_STUDENT, m)
                        haptic(HapticKind.Light)
                        startVoiceJob(m, recorder.lastText)
                    }
                }
            } finally {
                panel = AssistantPanel.NONE
                finishingVoice = false
            }
        }
    }

    // 返回键优先级：文字选择（P4）> 关面板 > 导航（BackHandler 后组合者优先）
    BackHandler(showAttach || isRecording) {
        panel = AssistantPanel.NONE
    }
    BackHandler(textSelect.active) { textSelect.exit() }

    // ★ W1：人格区/chip 删除后，personaAlpha / personaScale / personaY / chipsY 一并移除。

    // 根盒原点：供长按菜单把「气泡窗口坐标」换算成「相对本页坐标」（贴气泡定位用）
    Box(
        Modifier
            .fillMaxSize()
            // 2026-10-26：输入法避让改由主界面内容区统一 imePadding（这里再加会与之叠加）
            .onGloballyPositioned { rootOrigin = it.positionInWindow() },
    ) {
        // 背景采样层：P8 微信风格浅灰底（#EDEDED，仅浅色主题；深色保持 coui surface）
        // 主界面内容区 imePadding 让整页（含采样层）随键盘变小，weight 对话区自动让位。
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(pageBackdrop)
                .background(
                    if (cs.surface.isLight()) Color(0xFFEDEDED) else cs.surface
                ),
        )
        Column(Modifier.fillMaxSize()) {
            // ── 顶部细横幅（★ W1 + W2，2026-11-04 晚 3）──
            // 用户「把占一定高度的头像+名字+本学期九门课程+待交作业这个卡片直接去掉，整个界面都拿来聊天」、
            // 「顶部给我做一个横幅然后显示名字就可以了，不用显示头像在顶部」。
            // 原人格区（76dp 圆头像 / 小悟 / 电话钮 / KEWU AI）与两颗上下文 chip 整块删除，
            // LazyColumn 直接占满剩余空间；这里只留一条 36dp 细横幅显示名字，不显示头像。
            // ★ 语音通话入口原在人格区电话钮，按 W10 迁到「+」面板。
            // ★ X1（2026-11-05）：横幅加高（36→52dp），加底部分隔线 + 微妙底色差，和聊天列区分
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(cs.surface),
            ) {
                Text(
                    "小悟",
                    color = cs.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.Center),
                )
                // 底部 1dp 分隔线：让横幅与聊天列边界清晰（深色/浅色主题下都可见）
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(cs.onSurface.copy(alpha = 0.12f)),
                )
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

                    // 松手即归零：旧版松手后 pullAcc 留在半路，提示文字在两种状态间来回跳
                    override suspend fun onPreFling(available: Velocity): Velocity {
                        pullAcc = 0f
                        return Velocity.Zero
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().nestedScroll(pullConnection),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "history-tip") { PullHistoryHint(hasMoreHistory, pullAcc / pullLoadPx) }
                // P8：日期/时间由每条消息上方的 chatTimeLabel 承担（5 分钟规则），不再单独插日期行
                // 防御：同 id 只渲染一条（LazyColumn key 重复会直接崩溃，历史合并 / 回补时可能出现重复）
                val visibleMsgs = messages.distinctBy { it.id }
                visibleMsgs.forEachIndexed { idx, msg ->
                    // P8 微信时间条：与上一条间隔 >5 分钟或跨天时，本条上方插一条居中灰字时间
                    val prevMsg = visibleMsgs.getOrNull(idx - 1)
                    if (chatNeedTimeLabel(prevMsg?.ts ?: 0L, msg.ts)) {
                        item(key = "time-${msg.id}") {
                            Text(
                                chatTimeLabel(msg.ts),
                                color = cs.onSurfaceSecondary,
                                fontSize = 11.sp,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    // 2026-10-24：稳定 key + animateItem —— 新消息滑入，聊天更丝滑
                    item(key = msg.id, contentType = msg.isUser) {
                        Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) { // 气泡是玻璃采样者：只做位移动画，不做淡入淡出（祖先禁 alpha）
                            androidx.compose.runtime.CompositionLocalProvider(LocalChatMediaRetry provides retryMedia) {
                                // 2026-11-04：本条回答的 B 站视频卡加载完成 → 强制滚到底（只对最后一条生效，
                                // 避免滚动浏览历史时旧卡片的重新加载把视图拽下去）
                                ChatMessageItem(msg, pageBackdrop, menu, onBiliLoaded = {
                                    if (messages.lastOrNull()?.id == msg.id) biliLoadedTick++
                                }, selectFor = textSelect,
                                    // P8 weui 规则：连续 AI 消息只第一条带头像
                                    showAvatar = prevMsg?.isUser != false)
                            }
                        }
                    }
                }
                // 流式回答草稿：思考中显示占位，吐字后逐段增长；结束后由正式消息替换
                if (streamText != null) {
                    item(key = "ai-streaming") {
                        Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) { // 气泡是玻璃采样者：只做位移动画，不做淡入淡出（祖先禁 alpha）
                            ChatStreamingBubble(streamText, pageBackdrop, showAvatar = visibleMsgs.lastOrNull()?.isUser != false)
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
            // ★ V3（2026-11-04 晚 2）：+ / 录音面板已改挂在 [ChatInputDock] 的 `panelBelow` 槽位，
            //   渲染在输入框**下方**（微信键盘位），进退场动画一并带过去，见下方调用。

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
                    // B 站学习视频入口（2026-11-02）：打开手动浏览面板，B 站粉浅底强调
                    Chip(
                        selected = false,
                        onClick = { haptic(HapticKind.Tick); showBiliPanel = true },
                        label = "B站视频",
                        colors = ChipDefaults.chipColors(containerColor = BILI_PINK_SOFT),
                    )
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

            // ── 液态玻璃输入栏：共用组件（键盘间距 / 引用条 / 入场动画全 App 一致） ──
            // 2026-11-04（P6，InputView 语言重构）：[语音/键盘切换][+][玻璃输入框…][发送]，
            // 左侧 mic 钮开/关录音面板（开着时变 Edit 笔提示切回文字），「+」面板含相册/拍照（真实可用）。
            ChatInputDock(
                backdrop = pageBackdrop,
                input = input,
                onInput = { input = it },
                onSend = { doSend(input) },
                sendEnabled = !aiThinking && !mediaBusy,
                quote = quotePreview,
                onCancelQuote = { quotePreview = null },
                stagedImage = stagedImage,
                onRemoveImage = { stagedImage = null },
                // U4 InputView：左侧语音/键盘切换 + 功能面板入口，改微信小图标钮（去掉液态玻璃圆钮）
                leading = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChatWeuiIconBtn(
                            icon = if (panel == AssistantPanel.VOICE) COUIIcons.Edit else COUIIcons.Mic,
                            contentDescription = if (panel == AssistantPanel.VOICE) "切回文字" else "语音",
                            onClick = {
                                when {
                                    panel == AssistantPanel.VOICE -> finishVoice(send = false)
                                    aiThinking || mediaBusy -> scope.launch { snackbar.toast("小悟还在回答，稍等一下") }
                                    else -> scope.launch {
                                        com.kewu.app.AudioPlayback.stop() // 别把正在播放的声音录进去
                                        val err = recorder.start()
                                        if (err != null) snackbar.toast(err) else {
                                            haptic(HapticKind.Light)
                                            panel = AssistantPanel.VOICE
                                        }
                                    }
                                }
                            },
                        )
                        ChatWeuiIconBtn(
                            icon = COUIIcons.Add,
                            contentDescription = "功能面板",
                            onClick = { panel = if (panel == AssistantPanel.ATTACH) AssistantPanel.NONE else AssistantPanel.ATTACH },
                        )
                    }
                },
                trailing = null,
                // 助教页有自己的 ATTACH / VOICE 面板状态机：只换 InputView 视觉
                useBuiltinPanels = false,
                // ★ V3：+ / 录音面板渲染在输入框**下方**，滑入滑出动画保留（微信键盘位）
                panelBelow = {
                    AnimatedVisibility(
                        visible = panel != AssistantPanel.NONE,
                        enter = slideInVertically(
                            animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
                            initialOffsetY = { it },
                        ),
                        exit = slideOutVertically(
                            animationSpec = tween(150),
                            targetOffsetY = { it },
                        ),
                    ) {
                        when (shownPanel) {
                            AssistantPanel.ATTACH -> ChatAttachPanel(
                                backdrop = pageBackdrop,
                                onAlbum = {
                                    panel = AssistantPanel.NONE
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
                                        panel = AssistantPanel.NONE
                                        com.kewu.app.CameraCapture.launcher?.invoke { path ->
                                            if (path != null) stagedImage = path
                                        }
                                    }
                                } else null,
                                // ★ W10：语音通话入口补回「+」面板（原人格区电话钮已在 W1 删除）
                                onVoiceCall = {
                                    panel = AssistantPanel.NONE
                                    showVoiceCall = true
                                },
                            )
                            AssistantPanel.VOICE -> ChatVoicePanel(
                                backdrop = pageBackdrop,
                                recorder = recorder,
                                onCancel = { finishVoice(send = false) },
                                onSend = { finishVoice(send = true) },
                            )
                            AssistantPanel.NONE -> Unit
                        }
                    }
                },
            )
            Spacer(Modifier.height(6.dp))
        }

        // ── 长按气泡的 QQ 式操作菜单（液态玻璃 + coui Card 选项） ──
        ChatBubbleMenu(menu, rootOrigin) { quotePreview = it }
        // V5/V6：文字选择已停用，这里只剩「双击查看整段文字」全屏层；长按菜单 = 复制 / 引用 / 分享
        val clipboardSel = LocalClipboardManager.current
        ChatSelectToolbar(
            state = textSelect,
            rootOrigin = rootOrigin,
            onCopy = { t ->
                clipboardSel.setText(androidx.compose.ui.text.AnnotatedString(t))
                snackbar.toast("已复制")
            },
            // V6：分享直接调系统分享，不再「复制到剪贴板 + 提示」
            onShare = { t -> shareText(t) },
            onQuote = { t -> quotePreview = t },
        )

        // ── B 站学习视频浏览面板（2026-11-02）：页内全屏覆盖层，自带 BackHandler ──
        SlideOverlay(if (showBiliPanel) Unit else null) {
            BilibiliBrowsePanel(onDismiss = { showBiliPanel = false })
        }

        // ── P7 AI 语音通话全屏层：深色通话界面，自带 BackHandler（挂断） ──
        SlideOverlay(if (showVoiceCall) Unit else null) {
            AiVoiceCallScreen(onDismiss = { showVoiceCall = false })
        }

    }
}

// ───────────────────────── 数据与常量 ─────────────────────────

/** 消息模型已抽到共用对话组件（KewuAiChat.kt），本页沿用旧名。 */
private typealias ChatMsg = AiChatMsg

/** B 站粉浅底（「B站视频」入口 chip 用）。 */
private val BILI_PINK_SOFT = Color(0xFFFB7299).copy(alpha = 0.14f)


/** 手写 mm:ss（commonMain 禁 String.format）。 */
private fun two(n: Int) = if (n < 10) "0$n" else "$n"

/** 当前 epoch 毫秒（2026-10-24 聊天记录持久化用）。 */
private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
private fun fmtClock(totalSec: Int) = "${two(totalSec / 60)}:${two(totalSec % 60)}"


// ───────────────────────── 子组件 ─────────────────────────


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



/** 人物 icon（头 + 肩，实心白） —— AI 头像同款（P7 起语音通话页也用，故 internal）。 */
@Composable
internal fun PersonGlyph(modifier: Modifier = Modifier, color: Color) {
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



// ───────────────────────── 附件 / 录音面板 ─────────────────────────

/**
 * 对话列表顶部「下拉查看更早对话」提示（2026-11-02 重做）：文字固定不变，只用一个小进度环跟手表示下拉量，
 * 越过阈值直接加载。旧版在「下拉… / 松开…」两句之间随手指抖动来回切换，看起来一直在闪。
 */
@Composable
internal fun PullHistoryHint(hasMore: Boolean, pull: Float) {
    val cs = COUITheme.colorScheme
    if (!hasMore) {
        Spacer(Modifier.height(4.dp))
        return
    }
    val p by androidx.compose.animation.core.animateFloatAsState(pull.coerceIn(0f, 1f), tween(90), label = "pullHint")
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        io.github.suqi8.coui.kmp.basic.CircularProgressIndicator(
            progress = p,
            colors = io.github.suqi8.coui.kmp.basic.ProgressIndicatorDefaults.circularProgressIndicatorColors(foregroundColor = KewuGreen),
            strokeWidth = 1.8.dp,
            size = 13.dp,
        )
        Spacer(Modifier.width(6.dp))
        Text("下拉查看更早对话", color = cs.onSurfaceSecondary, fontSize = 11.sp)
    }
}
