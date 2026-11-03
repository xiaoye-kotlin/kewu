package com.kewu.app.ui

import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.Dp

/*
 * 课悟统一 AI 对话界面（2026-11-02，用户要求「写一个和 AI 对话的界面，任何要和 AI 对话的地方都调用它，
 * 不要每个界面单独重写」）。
 *
 * 从助教页（TaPage）抽出：液态玻璃 AI 气泡 / 品牌色用户气泡、入场凝结动画、长按 QQ 式菜单（复制 / 引用 / 分享）、
 * 引用预览条、液态玻璃输入栏（发送钮 / 键盘间距）、B 站视频推荐卡。
 *   - [KewuAiChatScreen]：整页对话（顶栏 + 消息列 + 快捷提问 + 输入栏），课堂纪要「问小悟」、作业「问小悟」等直接调用；
 *   - [ChatMessageItem] / [ChatInputDock] / [ChatBubbleMenu]：助教页这类自带人格区、附件面板的页面拼装用。
 *
 * ★ 键盘：主界面内容区已统一 imePadding（MainScreen），这里**不再**加 imePadding；输入栏底部留白
 *   无键盘时给悬浮导航栏让位（[ChatDockRestingBottom]），键盘弹起时统一收成 [ChatImeGap]——
 *   全 App 每个对话输入框离输入法的距离都一样。
 */

/** 键盘弹起时输入栏与输入法之间的统一间距。 */
internal val ChatImeGap = 8.dp

/** 无键盘时输入栏底部留白：给底部悬浮导航托盘让位（托盘约 90dp 高）。 */
internal val ChatDockRestingBottom = 110.dp

private fun two(n: Int) = if (n < 10) "0$n" else "$n"
private fun fmtClock(totalSec: Int) = "${two(totalSec / 60)}:${two(totalSec % 60)}"
private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

// ───────────────────────── B 站推荐：只在学生问不懂的知识点时出现 ─────────────────────────

/**
 * 给大模型的 B 站推荐规则（2026-11-02 用户要求：只有学生问不懂的知识点时才推 B 站视频，其它时候不要每条都带）。
 * 由模型判断是否「在问知识点」，需要时在回答末尾单独一行输出标记，App 解析后去掉标记、渲染视频卡。
 */
internal const val BILI_TAG_RULE =
    "【你会找 B 站学习视频】课悟 App 给你接了 B 站视频搜索：你在回答最后单独起一行写 [[BILI:关键词]]，App 就会自动去 B 站搜索，" +
        "并把搜到的视频卡片紧跟在你这条回答下面发给学生（学生点开就在 App 里播放）。所以你可以找视频，绝对不要说「我不能搜索视频 / 无法提供链接」。" +
        "以下两种情况要写这一行：① 学生让你找、推荐某个知识点的视频；② 学生在问自己没弄懂的知识点、概念、公式或解题方法。" +
        "写了这一行时，正文里自然地说一句「我给你找了几个讲这个的 B 站视频，就在下面」，不要编造视频标题、UP 主或链接。" +
        "关键词是 2～12 个字、适合在 B 站搜索教学视频的知识点名称（例如 [[BILI:矩阵的秩]]）。" +
        "闲聊、问候、问作业截止时间 / 成绩 / 课表、让你做规划或操作软件等其它情况，一律不要写这一行。"

private val BILI_TAG = Regex("""\[\[\s*BILI\s*[:：]\s*([^\]\n]{1,30}?)\s*]]""")

/** 拆出回答里的 B 站推荐标记：返回（去掉标记的正文, 关键词或 null）。 */
internal fun splitBiliTag(reply: String): Pair<String, String?> {
    val kw = BILI_TAG.find(reply)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    return reply.replace(BILI_TAG, "").trimEnd() to kw
}

/** 气泡显示用：去掉完整标记，以及流式输出时尾部还没写完的 `[[` 半截。 */
internal fun aiVisibleText(text: String): String {
    var t = text.replace(BILI_TAG, "")
    val i = t.lastIndexOf("[[")
    if (i >= 0 && !t.substring(i).contains("]]")) t = t.substring(0, i)
    return t.trimEnd()
}

internal data class AiChatMsg(
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
    /** B 站相关视频推荐（2026-11-02）：仅 AI 回答气泡可能携带，渲染在气泡下方的推荐卡。 */
    val biliKeyword: String? = null,
    /** 图片消息（2026-11-03）：本地路径（上传中）或 OBS 链接；[text] 为配的文字。 */
    val image: String? = null,
    /** 语音消息：本地路径（识别中）或 OBS 链接；[text] 为识别出的文字。 */
    val voice: String? = null,
    /** 图片 / 语音的发送状态。 */
    val mediaState: ChatMediaState = ChatMediaState.DONE,
    val mediaError: String? = null,
) {
    /** 复制/引用/分享用的纯文本（语音给识别文字，图片给配文）。 */
    val displayText: String
        get() = when {
            isVoice -> text.trim().ifEmpty { "语音消息 · ${fmtClock(voiceSecs)}" }
            image != null -> text.trim().ifEmpty { "[图片]" }
            isUser -> aiVisibleText(text)
            else -> stripMarkdown(aiVisibleText(text))
        }
}

// ───────────────────────── 通用按压 Q 弹 ─────────────────────────

/**
 * 按压回弹：按下 1→0.88，低阻尼弹簧带回冲 —— 全组件统一的「液态玻璃 Q 弹」。
 * ⚠️ 只能用于**非采样者**节点；玻璃件请把本修饰符加在玻璃**内部**内容上。
 */
@Composable
internal fun Modifier.pressBounce(onClick: () -> Unit): Modifier {
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
internal fun Modifier.bubblePress(onLongPress: () -> Unit): Modifier {
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

/**
 * 浅色底玻璃描边：外面看得到的「框」。
 * 外圈极淡暗轮廓 + 内圈白高光（SrcOver；Screen 在浅色 M3 底会被洗白 —— 导航托盘同款结论）。
 */
@Composable
internal fun DockStroke(shape: RoundedCornerShape) {
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
 * AI 气泡（2026-10-18 强化）：**整颗液态玻璃包裹** —— 足够大的圆角（尾部小角 + 22dp）、
 * 22dp 毛玻璃 blur + 奶白磨砂 tint + outlineVariant 描边，透出身后列表内容。
 * 长按 → QQ 式操作菜单（复制/引用/分享，LiquidGlassActionMenu）。
 * 入场 = 玻璃凝结（tint/blur 从近零插值）+ placement 上浮 + 内容透明 —— 采样者安全。
 */
@Composable
internal fun ChatAiBubble(
    msg: AiChatMsg,
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
                    MarkdownText(aiVisibleText(msg.text), color = cs.onSurface, fontSize = 13.5.sp, lineHeight = 20.sp)
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
internal fun ChatUserBubble(
    msg: AiChatMsg,
    /** 被长按选中：持续上报气泡矩形（窗口坐标）供菜单贴脸定位；滚动时也跟着上报 */
    reportBounds: Boolean,
    onBounds: (Rect) -> Unit,
    onLongPress: () -> Unit,
) {
    if (msg.isVoice) {
        Box(
            Modifier.fillMaxWidth().onGloballyPositioned { if (reportBounds) onBounds(it.boundsInWindow()) },
            contentAlignment = Alignment.CenterEnd,
        ) { ChatVoiceBubble(msg, onLongPress) }
        return
    }
    if (msg.image != null) {
        Column(
            Modifier.fillMaxWidth().onGloballyPositioned { if (reportBounds) onBounds(it.boundsInWindow()) },
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ChatImageBubble(msg, onLongPress)
            if (msg.text.isNotBlank() || msg.quote != null) {
                ChatUserBubble(msg.copy(image = null), reportBounds = false, onBounds = {}, onLongPress = onLongPress)
            }
        }
        return
    }
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
            if (msg.text.isNotEmpty()) Text(msg.text, color = cs.onPrimary, fontSize = 13.5.sp, lineHeight = 20.sp)
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
internal fun QuotePreviewBar(text: String, onCancel: () -> Unit) {
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
internal fun ChatRoundIconBtn(
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

/** 输入框内小钮的 coui 图标（原自绘 Plus / Mic 已换 coui-icons）。 */
@Composable
internal fun ChatGlyphIcon(icon: ImageVector) {
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
internal fun ChatSendBtn(
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

// ───────────────────────── 组装件 ─────────────────────────

/** 长按菜单状态：被长按的消息 + 它在窗口里的矩形（菜单贴气泡定位）。 */
internal class ChatMenuState {
    var msg by mutableStateOf<AiChatMsg?>(null)
    var anchor by mutableStateOf<Rect?>(null)
}

@Composable
internal fun rememberChatMenuState(): ChatMenuState = remember { ChatMenuState() }

/**
 * 一条消息：AI 走液态玻璃气泡（下方可带 B 站推荐卡），用户走品牌色气泡；长按弹菜单。
 * 放在 LazyColumn 的 item 里（调用方负责 key / animateItem）。
 */
@Composable
internal fun ChatMessageItem(msg: AiChatMsg, backdrop: LayerBackdrop, menu: ChatMenuState) {
    if (msg.isUser) {
        ChatUserBubble(msg, reportBounds = msg == menu.msg, onBounds = { menu.anchor = it }) {
            menu.msg = msg
            haptic(HapticKind.Medium)
        }
    } else {
        Column {
            ChatAiBubble(msg, backdrop, reportBounds = msg == menu.msg, onBounds = { menu.anchor = it }) {
                menu.msg = msg
                haptic(HapticKind.Medium)
            }
            // B 站相关视频：只在模型判断「学生在问不懂的知识点」时出现；点卡片在 App 内直接播放
            msg.biliKeyword?.let { kw ->
                Spacer(Modifier.height(6.dp))
                BilibiliVideoSuggest(kw, modifier = Modifier.padding(end = 48.dp))
            }
        }
    }
}

/** 流式回答草稿气泡（思考中显示占位，吐字后逐段增长）。 */
@Composable
internal fun ChatStreamingBubble(text: String, backdrop: LayerBackdrop, thinkingText: String = "小悟正在思考…") {
    ChatAiBubble(
        AiChatMsg(text.ifEmpty { thinkingText }, isUser = false, id = -1L, ts = nowMs()),
        backdrop, reportBounds = false, onBounds = {},
    ) {}
}

/** 长按菜单（复制 / 引用 / 分享）。放在页面根 Box 的最后，[rootOrigin] 是该根 Box 的窗口原点。 */
@Composable
internal fun ChatBubbleMenu(menu: ChatMenuState, rootOrigin: Offset, onQuote: (String) -> Unit) {
    val active = menu.msg ?: return
    val snackbar = LocalKewuSnackbar.current
    val clipboard = LocalClipboardManager.current
    val relRect = menu.anchor?.let { r ->
        Rect(r.left - rootOrigin.x, r.top - rootOrigin.y, r.right - rootOrigin.x, r.bottom - rootOrigin.y)
    }
    LiquidGlassActionMenu(
        visible = true,
        anchor = if (active.isUser) GlassMenuAnchor.TopEnd else GlassMenuAnchor.TopStart,
        anchorRect = relRect,
        actions = listOf(
            GlassMenuAction("复制", MenuGlyphKind.Copy) {
                clipboard.setText(AnnotatedString(active.displayText))
                snackbar.toast("已复制")
            },
            GlassMenuAction("引用", MenuGlyphKind.Quote) { onQuote(active.displayText) },
            GlassMenuAction("分享", MenuGlyphKind.Share) { shareText(active.displayText) },
        ),
        onDismiss = { menu.msg = null },
    )
}

/**
 * 液态玻璃输入栏（助教页同款）：引用预览条 + 玻璃胶囊（可选左右小钮）+ 绿芯发送钮。
 * 入场凝结动画（上浮 + blur / tint 插值）；底部留白：无键盘 [restingBottom]，键盘弹起 [ChatImeGap]。
 */
@Composable
internal fun ChatInputDock(
    backdrop: LayerBackdrop,
    input: String,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "问点什么…",
    sendEnabled: Boolean = true,
    quote: String? = null,
    onCancelQuote: () -> Unit = {},
    restingBottom: Dp = ChatDockRestingBottom,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** 待发图片（本地路径）：显示在输入栏上方，可单独发送。 */
    stagedImage: String? = null,
    onRemoveImage: () -> Unit = {},
) {
    val cs = COUITheme.colorScheme
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    var enter by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(120); enter = true }
    val dockY by animateDpAsState(if (enter) 0.dp else 84.dp, spring(dampingRatio = 0.52f, stiffness = 330f), label = "dockY")
    val dockBlur by animateDpAsState(if (enter) 22.dp else 4.dp, spring(dampingRatio = 0.52f, stiffness = 330f), label = "dockBlur")
    val dockCorner by animateDpAsState(if (enter) 28.dp else 40.dp, spring(dampingRatio = 0.52f, stiffness = 330f), label = "dockCorner")
    val dockTint by animateFloatAsState(
        if (enter) 0.38f else 0.04f,
        spring(dampingRatio = 0.52f, stiffness = 330f, visibilityThreshold = 0.0005f),
        label = "dockTint",
    )
    // ★ 临界阻尼不过冲：欠阻尼从 110 收到 8 会冲成负数 → Padding must be non-negative 闪退（2026-10-26 小米）
    val bottomPad by animateDpAsState(
        if (imeVisible) ChatImeGap else restingBottom,
        spring(dampingRatio = 1f, stiffness = 420f),
        label = "dockBottomPad",
    )
    val canSend = sendEnabled && (input.isNotBlank() || quote != null || stagedImage != null)
    Column(
        modifier
            .fillMaxWidth()
            .offset(y = dockY) // placement 位移（采样者安全）
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = bottomPad.coerceAtLeast(0.dp)),
    ) {
        AnimatedVisibility(
            visible = quote != null,
            enter = fadeIn(tween(140)) + slideInVertically(tween(180)) { -it / 2 },
            exit = fadeOut(tween(110)) + slideOutVertically(tween(140)) { -it / 2 },
        ) {
            // 退场动画期间 quote 已置空，仍显示最后一条引用
            var last by remember { mutableStateOf("") }
            if (quote != null) last = quote
            Box(Modifier.padding(bottom = 6.dp)) { QuotePreviewBar(text = last) { onCancelQuote() } }
        }
        AnimatedVisibility(
            visible = stagedImage != null,
            enter = fadeIn(tween(160)) + expandVertically(spring(dampingRatio = 0.8f, stiffness = 420f)) +
                androidx.compose.animation.scaleIn(spring(dampingRatio = 0.6f, stiffness = 420f), initialScale = 0.9f),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
        ) {
            var last by remember { mutableStateOf("") }
            if (stagedImage != null) last = stagedImage
            Box(Modifier.padding(bottom = 6.dp)) { ChatStagedImageBar(last, onRemoveImage) }
        }
        KewuGlassCard(
            backdrop = backdrop,
            shape = RoundedCornerShape(dockCorner),
            blurRadius = dockBlur,
            tintAlpha = dockTint,
            modifier = Modifier.height(56.dp).fillMaxWidth(),
        ) {
            DockStroke(RoundedCornerShape(28.dp))
            Row(
                Modifier.fillMaxSize().padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) leading() else Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.weight(1f).padding(horizontal = 9.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (input.isEmpty()) {
                        Text(placeholder, color = cs.onSurfaceSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = onInput,
                        textStyle = TextStyle(color = cs.onSurface, fontSize = 14.sp),
                        cursorBrush = SolidColor(cs.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                trailing?.invoke()
                Spacer(Modifier.width(4.dp))
                ChatSendBtn(backdrop = backdrop, enabled = canSend, onSend = { if (canSend) onSend() })
            }
        }
    }
}

/**
 * 整页 AI 对话（统一外观）：页面底色采样层 + 顶栏 + 消息列（贴底跟随）+ 快捷提问 + 液态玻璃输入栏 +
 * 长按菜单 + 应用内视频播放层。调用方只管消息与发送逻辑。
 *
 * @param messages 已完成的消息（含欢迎语）
 * @param streamText 正在流式生成的回答；null = 当前没有在回答
 * @param onSend 发送（正文, 引用原文或 null）；调用方负责把用户消息加进 [messages] 并发起请求
 * @param header 消息列顶部的说明卡等（可空）
 * @param chips 输入栏上方的快捷提问（可空）
 */
@Composable
internal fun KewuAiChatScreen(
    title: String,
    onBack: () -> Unit,
    messages: List<AiChatMsg>,
    streamText: String?,
    onSend: (text: String, quote: String?) -> Unit,
    placeholder: String = "问点什么…",
    thinkingText: String = "小悟正在思考…",
    restingBottom: Dp = ChatDockRestingBottom,
    header: (@Composable () -> Unit)? = null,
    chips: (@Composable RowScope.() -> Unit)? = null,
) {
    val cs = COUITheme.colorScheme
    val backdrop = rememberGlassBackdrop()
    val listState = rememberLazyListState()
    val menu = rememberChatMenuState()
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var input by remember { mutableStateOf("") }
    var quote by remember { mutableStateOf<String?>(null) }
    val busy = streamText != null

    BackHandler { onBack() }
    ChatAutoScroll(listState, Triple(messages.lastOrNull()?.id ?: 0L, messages.size, streamText?.length?.div(40) ?: -1))

    fun send() {
        val t = input.trim()
        if ((t.isEmpty() && quote == null) || busy) return
        haptic(HapticKind.Light)
        val q = quote
        input = ""
        quote = null
        onSend(t, q)
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { rootOrigin = it.positionInWindow() }) {
        Box(Modifier.matchParentSize().layerBackdrop(backdrop).background(cs.surface))
        Column(Modifier.fillMaxSize()) {
            HubTopBar(title, onBack)
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (header != null) item(key = "chat-header") { header() }
                messages.distinctBy { it.id }.forEach { m ->
                    item(key = m.id, contentType = m.isUser) {
                        // 气泡是玻璃采样者：只做位移动画，不做淡入淡出（祖先禁 alpha）
                        Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) { ChatMessageItem(m, backdrop, menu) }
                    }
                }
                if (streamText != null) {
                    item(key = "ai-streaming") {
                        Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) {
                            ChatStreamingBubble(streamText, backdrop, thinkingText)
                        }
                    }
                }
            }
            if (chips != null) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = chips,
                )
            }
            ChatInputDock(
                backdrop = backdrop,
                input = input,
                onInput = { input = it },
                onSend = { send() },
                placeholder = placeholder,
                sendEnabled = !busy,
                quote = quote,
                onCancelQuote = { quote = null },
                restingBottom = restingBottom,
            )
            Spacer(Modifier.height(6.dp))
        }
        ChatBubbleMenu(menu, rootOrigin) { quote = it }
    }
}

/** 新消息 id（进程内单调递增，供 LazyColumn key 用）。 */
internal fun newChatId(): Long = com.kewu.app.ChatHistory.nextId()

/**
 * 页内全屏层的统一进出场（2026-11-02 用户：切页面 / 点控件都要有足够的动画过渡）：
 * 从右侧滑入、向右滑出（与下钻页同方向）。层里有液态玻璃采样者，所以只做位移不做淡入淡出。
 * [value] 非空即显示；退场动画期间仍用最后一个值渲染。
 */
@Composable
internal fun <T : Any> SlideOverlay(value: T?, content: @Composable (T) -> Unit) {
    var last by remember { mutableStateOf(value) }
    if (value != null) last = value
    AnimatedVisibility(
        visible = value != null,
        enter = slideInHorizontally(spring(dampingRatio = 0.86f, stiffness = 380f)) { it },
        exit = slideOutHorizontally(tween(220)) { it },
    ) {
        last?.let { content(it) }
    }
}
