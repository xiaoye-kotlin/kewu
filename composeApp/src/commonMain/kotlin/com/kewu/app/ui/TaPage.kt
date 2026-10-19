package com.kewu.app.ui

import com.kewu.app.BackHandler
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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
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
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ───────────────────────── 小悟 · AI 智能体对话页（tab 1） ─────────────────────────

/**
 * 「助教」页 = AI 智能体对话页（Ardot 设计稿：`kewu-AI助教界面`）。学生和小悟对话问课堂问题。
 *
 * 视觉语言（浅色 M3 背景上的 coui 玻璃，2026-10-18 晚真机打磨后）：
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
    val cs = MaterialTheme.colorScheme
    val pageBackdrop = rememberGlassBackdrop()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // ── 对话状态（后端接入前：本地 mock 往返，保证 UI 可完整体验） ──
    var messages by remember {
        mutableStateOf(
            listOf(
                ChatMsg(
                    "你好，我是小悟～上课没听懂的地方直接问我：拍张板书、传个课件、发段语音都可以。",
                    isUser = false,
                ),
            ),
        )
    }
    var input by remember { mutableStateOf("") }
    // ★ 引用预览（第三轮 12.8）：引用不再塞进输入框，而是在输入框**上方**单独显示一条灰色小条，
    //   发送时随消息带上（ChatMsg.quote），× 可一键取消。
    var quotePreview by remember { mutableStateOf<String?>(null) }
    var courseCount by remember { mutableIntStateOf(2) }
    var pendingCount by remember { mutableIntStateOf(2) }
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
    var hint by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(1400)
            hint = null
        }
    }

    // 上下文 chip + 建议提问：真实接口（/api/courses、/api/assignments），失败保留 mock
    LaunchedEffect(Unit) {
        KewuApi.getCourses()?.let { courseCount = it.size }
        KewuApi.getAssignments()?.let { list ->
            // AssignmentInfo 无 done 字段，待交数先用作业总数近似（提交状态接口细化后再改）
            pendingCount = list.size
            list.firstOrNull()?.let { first ->
                suggestions = listOf("「${first.title}」怎么做？") + suggestions
            }
        }
    }

    // 下滑收起底部导航栏，回顶/上滑展开（与课堂/任务/我的同款增量判定）
    LaunchedEffect(listState) {
        var last = 0
        snapshotFlow { listState.firstVisibleItemIndex * 100_000 + listState.firstVisibleItemScrollOffset }
            .collect { cur ->
                if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 0) {
                    onNavHide(false)
                } else {
                    val delta = cur - last
                    if (delta > 6) onNavHide(true) else if (delta < -6) onNavHide(false)
                }
                last = cur
            }
    }

    // 键盘弹起检测：人格区/上下文 chip 自动收起，把空间让给对话列（否则键盘占 830px 对话区被压没）
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    // 新消息自动滚到底；键盘弹起也滚到底（保证最新消息贴着输入框可见）
    LaunchedEffect(messages.size, imeVisible) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

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

    // 发送：用户消息立即上屏；AI 回答服务接入前用本地 mock 回复占位（不崩、不空屏）
    var replyRound by remember { mutableIntStateOf(0) }
    val doSend: (String) -> Unit = { raw ->
        val text = raw.trim()
        // 允许「只发引用 + 正文为空」的情况（QQ 同款）；两者都空则不动
        if (text.isNotEmpty() || quotePreview != null) {
            val quoted = quotePreview
            messages = messages + ChatMsg(text, isUser = true, quote = quoted)
            input = ""
            quotePreview = null // 发出后清掉引用预览
            haptic(HapticKind.Light)
            scope.launch {
                delay(650)
                replyRound += 1
                messages = messages + ChatMsg(AI_REPLY_MOCKS[replyRound % AI_REPLY_MOCKS.size], isUser = false)
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
        if (imeVisible) 14.dp else 110.dp,
        spring(dampingRatio = 0.6f, stiffness = 380f),
        label = "dockBottomPad",
    )

    // 根盒原点：供长按菜单把「气泡窗口坐标」换算成「相对本页坐标」（贴气泡定位用）
    Box(
        Modifier
            .fillMaxSize()
            .imePadding()
            .onGloballyPositioned { rootOrigin = it.positionInWindow() },
    ) {
        // 背景采样层：M3 语义色，与玻璃内容平级 ✓
        // imePadding 加在页面根：窗口收缩时整页（含采样层）一起变小，weight 对话区自动让位。
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(pageBackdrop)
                .background(cs.background),
        )
        Column(Modifier.fillMaxSize()) {
            // ── AI 人格区 + 上下文 chip：键盘弹出时自动收起，把空间让给对话列 ──
            AnimatedVisibility(
                visible = !imeVisible,
                enter = fadeIn(tween(200)) + slideInVertically { -it / 3 },
                exit = fadeOut(tween(150)) + slideOutVertically { -it / 3 },
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
                        Text("KEWU AI", color = cs.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(12.dp))

                    // ── 上下文 chip（原概览卡元素的新呈现；placement 上浮入场） ──
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
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "今天",
                        color = cs.onSurfaceVariant,
                        fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
                messages.forEach { msg ->
                    item {
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
                ) + fadeIn(tween(150)),
                exit = slideOutVertically(
                    animationSpec = tween(150),
                    targetOffsetY = { it },
                ) + fadeOut(tween(110)),
            ) {
                when (shownPanel) {
                    AssistantPanel.ATTACH -> AttachPanel(backdrop = pageBackdrop) {
                        panel = AssistantPanel.NONE
                    }
                    AssistantPanel.VOICE -> VoiceRecordPanel(
                        backdrop = pageBackdrop,
                        voiceSecs = voiceSecs,
                        onCancel = { panel = AssistantPanel.NONE },
                        onSend = {
                            messages = messages + ChatMsg("", isUser = true, isVoice = true, voiceSecs = voiceSecs)
                            panel = AssistantPanel.NONE
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
                    suggestions.forEachIndexed { i, s ->
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(17.dp))
                                .background(cs.surfaceVariant)
                                .border(
                                    1.dp,
                                    if (i == 0) KewuGreen.copy(alpha = 0.5f) else cs.outlineVariant,
                                    RoundedCornerShape(17.dp),
                                )
                                .pressBounce { doSend(s) }
                                .padding(horizontal = 13.dp, vertical = 8.dp),
                        ) {
                            Text(s, color = cs.onSurface, fontSize = 12.5.sp, maxLines = 1)
                        }
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
                                }) { PlusGlyph() }
                                Box(
                                    Modifier.weight(1f).padding(horizontal = 9.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    if (input.isEmpty()) {
                                        Text("问点什么…", color = cs.onSurfaceVariant, fontSize = 14.sp)
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
                                }) { MicGlyph() }
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
                        hint = "已复制"
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

        // ── 轻提示（复制成功等）：中央胶囊，1.4s 自动消失 ──
        val activeHint = hint
        if (activeHint != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(cs.onSurface.copy(alpha = 0.82f))
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) {
                    Text(activeHint, color = cs.surface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
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

/** AI 回答服务接入前的本地占位回复（后端后补：走对话接口替换这里即可）。 */
private val AI_REPLY_MOCKS = listOf(
    "收到！这个问题我记下了，回答服务接入后给你完整展开。",
    "好问题～我先整理一下思路，稍后把步骤发你。",
    "这部分我在同步课程资料，接通后第一时间回复你。",
)

/** 手写 mm:ss（commonMain 禁 String.format）。 */
private fun two(n: Int) = if (n < 10) "0$n" else "$n"
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
    val cs = MaterialTheme.colorScheme
    Canvas(
        Modifier
            .fillMaxSize()
            .border(1.dp, cs.outlineVariant, shape),
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

/** 顶部上下文 chip：小玻璃胶囊（描边 + 圆点 + 文字，内容可 Q 弹按压）。 */
@Composable
private fun ContextChip(
    backdrop: LayerBackdrop,
    dot: Color,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(15.dp)),
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
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(topStart = 8.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 22.dp)
    val p = rememberEnterProgress(0, baseDelayMs = 60L)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                .border(1.dp, cs.outlineVariant, shape)
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
                        Text("小悟", color = cs.onSurfaceVariant, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(msg.text, color = cs.onSurface, fontSize = 13.5.sp, lineHeight = 20.sp)
                    msg.formula?.let { f ->
                        Spacer(Modifier.height(7.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(cs.surfaceVariant)
                                .border(1.dp, KewuGreen.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(f, color = cs.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

/** 用户气泡：M3 primary 填充 / 语音气泡带 mic + 时长，右对齐；同支持长按菜单。 */
@Composable
private fun UserBubble(
    msg: ChatMsg,
    /** 被长按选中：持续上报气泡矩形（窗口坐标）供菜单贴脸定位；滚动时也跟着上报 */
    reportBounds: Boolean,
    onBounds: (Rect) -> Unit,
    onLongPress: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 8.dp, bottomEnd = 22.dp, bottomStart = 22.dp)
    val p = rememberEnterProgress(0, baseDelayMs = 60L)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .widthIn(max = 280.dp)
                .riseEnter(p, 26f)
                .onGloballyPositioned { if (reportBounds) onBounds(it.boundsInWindow()) }
                .clip(shape)
                .background(cs.primary)
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
                    MicGlyph(color = cs.onPrimary, glyphSize = 16.dp)
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

/**
 * QQ 式引用预览条（第三轮 12.8）：输入框**上方**单独显示「引用了什么」，
 * 灰色填充质地 + 小字 + 左侧品牌色竖条，右侧 × 一键取消引用。
 * （旧版是把原文包「」塞进输入框，效果差且污染输入内容。）
 */
@Composable
private fun QuotePreviewBar(text: String, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cs.surfaceVariant)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(12.dp))
            .padding(start = 11.dp, top = 7.dp, bottom = 7.dp, end = 6.dp),
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
            Text("引用", color = cs.onSurfaceVariant, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
            Text(
                text, color = cs.onSurfaceVariant,
                fontSize = 11.5.sp, lineHeight = 15.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        // × 取消（复用本文件统一按压反馈，带轻震动）
        Box(
            Modifier
                .size(28.dp)
                .pressBounce { onCancel() },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(12.dp)) {
                val ws = size.width * 0.13f
                drawLine(
                    cs.onSurfaceVariant, Offset(ws, ws), Offset(size.width - ws, size.height - ws),
                    ws, StrokeCap.Round,
                )
                drawLine(
                    cs.onSurfaceVariant, Offset(size.width - ws, ws), Offset(ws, size.height - ws),
                    ws, StrokeCap.Round,
                )
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
    val cs = MaterialTheme.colorScheme
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
        Box(Modifier.fillMaxSize().border(1.dp, cs.outlineVariant, CircleShape)) {
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

/** 「+」照片/文件 icon。 */
@Composable
private fun PlusGlyph() {
    val cs = MaterialTheme.colorScheme
    Canvas(Modifier.size(19.dp)) {
        val s = size.minDimension
        val c = center
        val w = s * 0.11f
        val r = s * 0.34f
        drawLine(cs.onSurfaceVariant, Offset(c.x - r, c.y), Offset(c.x + r, c.y), w, StrokeCap.Round)
        drawLine(cs.onSurfaceVariant, Offset(c.x, c.y - r), Offset(c.x, c.y + r), w, StrokeCap.Round)
    }
}

/** 麦克风 icon（圆头竖条 + 弧线底座）。 */
@Composable
private fun MicGlyph(
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    glyphSize: androidx.compose.ui.unit.Dp = 19.dp,
) {
    Canvas(Modifier.size(glyphSize)) {
        val s = size.minDimension
        val c = center
        drawRoundRect(
            color,
            topLeft = Offset(c.x - s * 0.11f, c.y - s * 0.34f),
            size = Size(s * 0.22f, s * 0.42f),
            cornerRadius = CornerRadius(s * 0.11f),
        )
        drawArc(
            color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(c.x - s * 0.24f, c.y - s * 0.16f),
            size = Size(s * 0.48f, s * 0.44f),
            style = Stroke(width = s * 0.09f, cap = StrokeCap.Round),
        )
        drawLine(color, Offset(c.x, c.y + s * 0.28f), Offset(c.x, c.y + s * 0.38f), s * 0.09f, StrokeCap.Round)
    }
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
private fun AttachPanel(backdrop: LayerBackdrop, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 6.dp)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(20.dp)),
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
                AttachOption("相册") { PhotoGlyph() }
                AttachOption("拍照") { CameraGlyph() }
                AttachOption("文件") { FileGlyph() }
            }
        }
    }
}

@Composable
private fun AttachOption(label: String, glyph: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.pressBounce { /* 相册/拍照/文件选择器：后端接入后补 */ },
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(cs.surfaceVariant)
                .border(1.dp, cs.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) { glyph() }
        Spacer(Modifier.height(6.dp))
        Text(label, color = cs.onSurfaceVariant, fontSize = 11.sp)
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
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 6.dp)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(20.dp)),
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
                // 取消
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(cs.surfaceVariant)
                        .border(1.dp, cs.outlineVariant, CircleShape)
                        .pressBounce(onCancel),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(14.dp)) {
                        val s = size.minDimension
                        val w = s * 0.16f
                        drawLine(cs.onSurfaceVariant, Offset(s * 0.1f, s * 0.1f), Offset(s * 0.9f, s * 0.9f), w, StrokeCap.Round)
                        drawLine(cs.onSurfaceVariant, Offset(s * 0.9f, s * 0.1f), Offset(s * 0.1f, s * 0.9f), w, StrokeCap.Round)
                    }
                }
                Spacer(Modifier.width(14.dp))
                // 声纹波纹
                VoiceWave(active = true)
                Spacer(Modifier.width(10.dp))
                Text(fmtClock(voiceSecs), color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                // 发送
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(KewuGreen)
                        .pressBounce(onSend),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(15.dp)) {
                        val s = size.minDimension
                        val w = s * 0.18f
                        drawLine(Color.White, Offset(s * 0.15f, s * 0.52f), Offset(s * 0.45f, s * 0.82f), w, StrokeCap.Round)
                        drawLine(Color.White, Offset(s * 0.45f, s * 0.82f), Offset(s * 0.88f, s * 0.18f), w, StrokeCap.Round)
                    }
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

// ───────────────────────── 附件面板小图标 ─────────────────────────

@Composable
private fun PhotoGlyph() {
    val cs = MaterialTheme.colorScheme
    Canvas(Modifier.size(24.dp)) {
        val s = size.minDimension
        val c = cs.onSurfaceVariant
        val stroke = Stroke(width = s * 0.08f, cap = StrokeCap.Round)
        drawRoundRect(c, topLeft = Offset(s * 0.1f, s * 0.18f), size = Size(s * 0.8f, s * 0.64f), cornerRadius = CornerRadius(s * 0.12f), style = stroke)
        drawCircle(c, radius = s * 0.09f, center = Offset(s * 0.32f, s * 0.38f))
        val m = Path().apply {
            moveTo(s * 0.18f, s * 0.74f)
            lineTo(s * 0.42f, s * 0.5f)
            lineTo(s * 0.6f, s * 0.66f)
            lineTo(s * 0.74f, s * 0.52f)
            lineTo(s * 0.86f, s * 0.74f)
        }
        drawPath(m, c, style = stroke)
    }
}

@Composable
private fun CameraGlyph() {
    val cs = MaterialTheme.colorScheme
    Canvas(Modifier.size(24.dp)) {
        val s = size.minDimension
        val c = cs.onSurfaceVariant
        val stroke = Stroke(width = s * 0.08f, cap = StrokeCap.Round)
        drawRoundRect(c, topLeft = Offset(s * 0.08f, s * 0.26f), size = Size(s * 0.84f, s * 0.56f), cornerRadius = CornerRadius(s * 0.12f), style = stroke)
        drawCircle(c, radius = s * 0.16f, center = center.copy(y = s * 0.54f), style = stroke)
        drawRoundRect(c, topLeft = Offset(s * 0.36f, s * 0.14f), size = Size(s * 0.28f, s * 0.12f), cornerRadius = CornerRadius(s * 0.04f), style = stroke)
    }
}

@Composable
private fun FileGlyph() {
    val cs = MaterialTheme.colorScheme
    Canvas(Modifier.size(24.dp)) {
        val s = size.minDimension
        val c = cs.onSurfaceVariant
        val stroke = Stroke(width = s * 0.08f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        val p = Path().apply {
            moveTo(s * 0.12f, s * 0.22f)
            lineTo(s * 0.42f, s * 0.22f)
            lineTo(s * 0.52f, s * 0.34f)
            lineTo(s * 0.88f, s * 0.34f)
            lineTo(s * 0.88f, s * 0.78f)
            lineTo(s * 0.12f, s * 0.78f)
            close()
        }
        drawPath(p, c, style = stroke)
    }
}
