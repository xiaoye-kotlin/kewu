package com.kewu.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.HapticKind
import com.kewu.app.haptic
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Copy
import io.github.suqi8.coui.kmp.icon.extended.Ok
import io.github.suqi8.coui.kmp.icon.extended.Reply
import io.github.suqi8.coui.kmp.icon.extended.Share
import io.github.suqi8.coui.kmp.icon.extended.Undo
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/*
 * 聊天文字选择 —— ITxiaoguang/SelectTextHelper 1:1 复刻（2026-11-04 U2）。
 *
 * 用户明确要求「完全引用我那个库」。该库是 Android 原生 View 库（TextView + RecyclerView + EventBus，
 * jitpack com.github.ITxiaoguang:SelectTextHelper:1.1.0），本项目是 Compose Multiplatform，
 * commonMain 无法直接依赖 Android View，因此这里把它的**全部公开配置项 / 监听回调 / 交互**逐一映射到
 * commonMain（三端一致、不引入 2021 年的 AppCompat/EventBus 依赖）。对照表见《项目交接文档》67.3。
 *
 *   Builder(TextView)                          → [SelectTextConfig]
 *   setCursorHandleColor / SizeInDp(22)        → cursorHandleColor / cursorHandleSizeInDp
 *   setSelectedColor                           → selectedColor
 *   setSelectAll(true)                         → selectAll（初次长按默认全选）
 *   setSelectTextLength(2)                     → selectTextLength（不全选时首次选 N 字）
 *   setScrollShow(true)                        → scrollShow（工具条挂页面根，不随 item 回收）
 *   setMagnifierShow(true)                     → magnifierShow（拖手柄时圆形放大文字）
 *   setSelectedAllNoPop(true)                  → selectedAllNoPop + onSelectAllShowCustomPop
 *   setPopDelay(100)                           → popDelayMs
 *   setPopSpanCount(5)                         → popSpanCount（每行 5 项网格）
 *   setPopStyle(#4C4C4C radius8, arrow)        → popBgColor / popCornerRadiusDp / popArrowShow
 *   setPopAnimationStyle                       → popAnimationStyle（缩放淡入）
 *   addItem(icon, label, cb)                   → [SelectPopItem]
 *   OnSelectListener 9 个回调                  → [SelectTextListener]
 *   SelectTextDialog 双击查看                  → [SelectTextDialog] + [ChatTextSelectEventBus.openViewer]
 *   reset() / SelectTextEventBus               → [ChatTextSelectState.reset] / [ChatTextSelectEventBus]
 */

/** 对应 `SelectTextHelper.Builder` 的全部公开配置项；默认值取自库 README 的 default 说明。 */
internal data class SelectTextConfig(
    /** setCursorHandleColor —— 游标（手柄）颜色，微信绿 */
    val cursorHandleColor: Color = Color(0xFF07C160),
    /** setCursorHandleSizeInDp(22f) */
    val cursorHandleSizeInDp: Float = 22f,
    /** setSelectedColor —— 选中文本高亮色 */
    val selectedColor: Color = Color(0xFF3D7BEF).copy(alpha = 0.26f),
    /** setSelectAll(true) —— 初次选中是否全选 */
    val selectAll: Boolean = true,
    /** setScrollShow(true) —— 列表滚动时工具条继续显示 */
    val scrollShow: Boolean = true,
    /** setSelectedAllNoPop(true) —— 已全选时不弹默认窗，改回调 onSelectAllShowCustomPop */
    val selectedAllNoPop: Boolean = false,
    /** setMagnifierShow(true) —— 拖手柄时显示放大镜 */
    val magnifierShow: Boolean = true,
    /** setSelectTextLength(2) —— 非全选时首次选中长度 */
    val selectTextLength: Int = 2,
    /** setPopDelay(100) —— 弹窗延迟毫秒 */
    val popDelayMs: Long = 100L,
    /** setPopSpanCount(5) —— 操作弹窗每行个数 */
    val popSpanCount: Int = 5,
    /** setPopStyle(shape_color_4c4c4c_radius_8, ic_arrow) */
    val popBgColor: Color = Color(0xFF4C4C4C),
    val popCornerRadiusDp: Float = 8f,
    val popArrowShow: Boolean = true,
    val popItemTextColor: Color = Color.White,
    /** setPopAnimationStyle —— 弹窗缩放淡入 */
    val popAnimationStyle: Boolean = true,
)

/** 对应 `addItem(icon, desc, callback)`：图标 + 文字 + 回调（回调收到当前选中文本）。 */
internal data class SelectPopItem(
    val icon: ImageVector?,
    val label: String,
    val onClick: (selected: String) -> Unit,
)

/** 对应 `SelectTextHelper.OnSelectListener` 的全部回调。 */
internal class SelectTextListener {
    /** onClick(v, originalContent) */
    var onClick: ((String) -> Unit)? = null
    /** onLongClick(v) */
    var onLongClick: (() -> Unit)? = null
    /** onTextSelected(content) */
    var onTextSelected: ((String) -> Unit)? = null
    /** onDismiss() */
    var onDismiss: (() -> Unit)? = null
    /** onClickUrl(url) */
    var onClickUrl: ((String) -> Unit)? = null
    /** onSelectAllShowCustomPop() */
    var onSelectAllShowCustomPop: (() -> Unit)? = null
    /** onReset() */
    var onReset: (() -> Unit)? = null
    /** onDismissCustomPop() */
    var onDismissCustomPop: (() -> Unit)? = null
    /** onScrolling() */
    var onScrolling: (() -> Unit)? = null
}

/**
 * 对应 `SelectTextEventBus`：全局派发 `dismissAllPop` / `dismissAllPopDelayed`，
 * 并承载 demo 里 `SelectTextDialog` 的全屏查看文本。
 */
internal object ChatTextSelectEventBus {
    private val receivers = mutableListOf<() -> Unit>()
    var viewerText by mutableStateOf<String?>(null)
        private set

    fun register(reset: () -> Unit) {
        if (reset !in receivers) receivers.add(reset)
    }

    fun unregister(reset: () -> Unit) {
        receivers.remove(reset)
    }

    /** `SelectTextEventBus.instance.dispatch(SelectTextEvent(type))` */
    fun dispatch(type: String) {
        if (type == "dismissAllPop" || type == "dismissAllPopDelayed") {
            receivers.toList().forEach { it() }
        }
    }

    fun dismissAllPop() = dispatch("dismissAllPop")
    fun dismissAllPopDelayed() = dispatch("dismissAllPopDelayed")

    /** 双击查看：先收掉所有选择弹窗，再开全屏文本层（dismiss 时 reset，与库 demo 一致）。 */
    fun openViewer(text: String) {
        dismissAllPop()
        viewerText = text
    }

    fun closeViewer() {
        viewerText = null
        dismissAllPop()
    }
}

/** 拖手柄时的放大镜状态（锚点是相对文本区左上角的局部坐标）。 */
internal data class MagnifierInfo(val anchor: Offset, val charOffset: Int)

/** 页面级文字选择状态（同一时刻最多一条消息在选择）。 */
internal class ChatTextSelectState(
    val config: SelectTextConfig = SelectTextConfig(),
    val listener: SelectTextListener = SelectTextListener(),
) {
    /** 消息标识（两端都用消息 id）；null = 未在选择 */
    var msgKey by mutableStateOf<Any?>(null)
    /** 选择模式下的纯文本全文 */
    var fullText by mutableStateOf("")
    /** 字符选区 */
    var selection by mutableStateOf(TextRange.Zero)
    /** 选择文本区的窗口矩形（工具条定位用） */
    var textBounds by mutableStateOf<Rect?>(null)
    /** setPopDelay：进入选择后延迟显示弹窗 */
    var popVisible by mutableStateOf(false)
    /** addItem 累积的操作项；空 = 用工具条默认项 */
    var popItems by mutableStateOf<List<SelectPopItem>>(emptyList())
    /** setMagnifierShow：拖手柄时的放大镜 */
    var magnifier by mutableStateOf<MagnifierInfo?>(null)
    /** setSelectedAllNoPop：全选且配置了自定义弹窗 */
    var customPop by mutableStateOf(false)

    val active: Boolean get() = msgKey != null
    val isSelectAll: Boolean get() = fullText.isNotEmpty() && selection.min == 0 && selection.max == fullText.length

    /** 对应长按后 Helper 建立选区。默认按 setSelectAll / setSelectTextLength 决定初始选区。 */
    fun enter(key: Any, text: String, items: List<SelectPopItem> = emptyList(), selectAll: Boolean = config.selectAll) {
        msgKey = key
        fullText = text
        selection = if (selectAll) {
            TextRange(0, text.length)
        } else {
            TextRange(0, config.selectTextLength.coerceIn(1, text.length.coerceAtLeast(1)))
        }
        popItems = items
        popVisible = false
        magnifier = null
        customPop = config.selectedAllNoPop && isSelectAll
        listener.onLongClick?.invoke()
        listener.onTextSelected?.invoke(selectedText())
        if (customPop) listener.onSelectAllShowCustomPop?.invoke()
    }

    /** 对应「全选」操作项。 */
    fun selectAll() {
        selection = TextRange(0, fullText.length)
        listener.onTextSelected?.invoke(selectedText())
        if (config.selectedAllNoPop) {
            customPop = true
            listener.onSelectAllShowCustomPop?.invoke()
        }
    }

    fun selectedText(): String {
        val s = selection.min.coerceIn(0, fullText.length)
        val e = selection.max.coerceIn(0, fullText.length)
        return if (e > s) fullText.substring(s, e) else ""
    }

    /** 对应 `mSelectableTextHelper.reset()`：清选区 + 回调 onDismiss / onDismissCustomPop / onReset。 */
    fun reset() {
        msgKey = null
        fullText = ""
        selection = TextRange.Zero
        textBounds = null
        popVisible = false
        popItems = emptyList()
        magnifier = null
        customPop = false
        listener.onDismiss?.invoke()
        listener.onDismissCustomPop?.invoke()
        listener.onReset?.invoke()
    }

    fun exit() = reset()

    /** setScrollShow(false) 时滚动即收起；true 时只回调 onScrolling。 */
    fun scrolling() {
        if (!config.scrollShow) reset() else listener.onScrolling?.invoke()
    }
}

/**
 * 选择模式的纯文本渲染：选中区间背景高亮 + 两个拖柄（颜色/尺寸来自 [SelectTextConfig]）+ 放大镜。
 *
 * ★ U3 修复：Box 与 Text 都用 wrapContent 约束，和常态文本同一套测量，长按不再让气泡突然变长。
 */
@Composable
internal fun SelectableChatText(
    text: String,
    state: ChatTextSelectState,
    color: Color,
    fontSize: TextUnit = 13.5.sp,
    lineHeight: TextUnit = 20.sp,
    /** 选区背景色：默认取 setSelectedColor（[SelectTextConfig.selectedColor]），气泡可按底色覆盖对比度 */
    selectedBg: Color = state.config.selectedColor,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var textOrigin by remember { mutableStateOf(Offset.Zero) }
    val sel = state.selection
    val annotated = remember(text, sel, color, fontSize, lineHeight, selectedBg) {
        buildAnnotatedString {
            append(text)
            // lineHeight 属于 ParagraphStyle，SpanStyle 不接受；行高由 Text 组件自身排版
            addStyle(SpanStyle(color = color, fontSize = fontSize), 0, text.length)
            val s = sel.min.coerceIn(0, text.length)
            val e = sel.max.coerceIn(0, text.length)
            if (e > s) addStyle(SpanStyle(background = selectedBg), s, e)
        }
    }
    Box(
        Modifier
            .wrapContentSize()
            .onGloballyPositioned { coords ->
                textOrigin = coords.boundsInWindow().topLeft
                state.textBounds = coords.boundsInWindow()
            },
    ) {
        Text(
            annotated,
            modifier = Modifier.wrapContentWidth(),
            onTextLayout = { layout = it },
        )
        val lr = layout
        if (lr != null && text.isNotEmpty()) {
            val clampMin = sel.min.coerceIn(0, text.length)
            val clampMax = sel.max.coerceIn(0, text.length)
            val onDragPos: (Offset, Int) -> Unit = { local, off ->
                if (state.config.magnifierShow) state.magnifier = MagnifierInfo(local, off)
                listenerSelected(state)
            }
            SelectHandle(
                lr, clampMin, isStart = true, textOrigin = textOrigin, config = state.config,
                onDragTo = { off ->
                    state.selection = TextRange(off.coerceAtMost(state.selection.max), state.selection.max)
                },
                onDragPos = onDragPos,
                onDragEnd = { state.magnifier = null },
            )
            SelectHandle(
                lr, (clampMax - 1).coerceAtLeast(0), isStart = false, textOrigin = textOrigin, config = state.config,
                onDragTo = { off ->
                    state.selection = TextRange(state.selection.min, (off + 1).coerceAtLeast(state.selection.min))
                },
                onDragPos = onDragPos,
                onDragEnd = { state.magnifier = null },
            )
        }
        // setMagnifierShow(true)：拖手柄时在光标上方形似微信的圆形放大文字
        state.magnifier?.let { m ->
            SelectMagnifier(text, m.charOffset, m.anchor, color)
        }
    }
}

private fun listenerSelected(state: ChatTextSelectState) {
    state.listener.onTextSelected?.invoke(state.selectedText())
}

/** 单个选择手柄：圆点 + 竖线；尺寸/颜色由 config.cursorHandleSizeInDp / cursorHandleColor 决定。 */
@Composable
private fun SelectHandle(
    lr: TextLayoutResult,
    offset: Int,
    isStart: Boolean,
    textOrigin: Offset,
    config: SelectTextConfig,
    onDragTo: (Int) -> Unit,
    onDragPos: (Offset, Int) -> Unit,
    onDragEnd: () -> Unit,
) {
    val dotR = (config.cursorHandleSizeInDp * 0.18f).dp
    val lineH = (config.cursorHandleSizeInDp * 0.82f).dp
    val box = lr.getBoundingBox(offset.coerceIn(0, lr.layoutInput.text.length))
    val anchorX = box.left + box.width / 2f
    val anchorY = if (isStart) box.top else box.bottom
    var handleOrigin by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .offset {
                IntOffset(
                    x = (anchorX - dotR.toPx()).roundToInt(),
                    y = if (isStart) {
                        (anchorY - (dotR * 2 + lineH).toPx()).roundToInt()
                    } else {
                        anchorY.roundToInt()
                    },
                )
            }
            .width(dotR * 2)
            .height(dotR * 2 + lineH)
            .onGloballyPositioned { handleOrigin = it.boundsInWindow().topLeft }
            .pointerInput(textOrigin, isStart) {
                detectDragGestures(
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                ) { change, _ ->
                    change.consume()
                    val local = change.position + (handleOrigin - textOrigin)
                    val off = lr.getOffsetForPosition(local)
                    onDragTo(off)
                    onDragPos(local, off)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = dotR.toPx()
            val cx = size.width / 2f
            val c = config.cursorHandleColor
            if (isStart) {
                drawCircle(c, radius = r, center = Offset(cx, r))
                drawLine(c, Offset(cx, r * 2), Offset(cx, r * 2 + lineH.toPx()), strokeWidth = 2.dp.toPx())
            } else {
                drawLine(c, Offset(cx, 0f), Offset(cx, lineH.toPx()), strokeWidth = 2.dp.toPx())
                drawCircle(c, radius = r, center = Offset(cx, lineH.toPx() + r))
            }
        }
    }
}

/** setMagnifierShow：胶囊形放大层，显示光标附近片段并放大 1.5 倍。 */
@Composable
private fun SelectMagnifier(text: String, charOffset: Int, anchor: Offset, color: Color) {
    val from = (charOffset - 5).coerceAtLeast(0)
    val to = (charOffset + 6).coerceAtMost(text.length)
    val snippet = if (to > from) text.substring(from, to) else text
    Box(
        Modifier
            .offset {
                IntOffset(
                    (anchor.x - 60.dp.toPx()).roundToInt(),
                    (anchor.y - 96.dp.toPx()).roundToInt(),
                )
            }
            .size(width = 120.dp, height = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White)
            .border(1.dp, Color(0x33000000), RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            snippet,
            color = color,
            fontSize = 13.sp,
            maxLines = 1,
            modifier = Modifier.graphicsLayer { scaleX = 1.5f; scaleY = 1.5f },
        )
    }
}

/**
 * 选择操作条：渲染在页面根 Box，贴选区上方（放不下翻下方）。
 *
 * U2 按 `setPopStyle(#4C4C4C 圆角 8dp + 箭头)` + `setPopSpanCount(5)` 重做：
 * 深色网格弹窗（图标 + 文字），不再是 P4 的 coui 胶囊条。同时承载 `setPopDelay`、
 * `SelectTextEventBus` 注册与 `SelectTextDialog` 双击查看层。
 */
@Composable
internal fun ChatSelectToolbar(
    state: ChatTextSelectState,
    rootOrigin: Offset,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    /** 学生端有引用输入框则传入；null 隐藏引用钮 */
    onQuote: ((String) -> Unit)? = null,
) {
    // SelectTextEventBus：点空白 / 切页面统一 dismissAllPop
    DisposableEffect(Unit) {
        val r = { state.reset() }
        ChatTextSelectEventBus.register(r)
        onDispose { ChatTextSelectEventBus.unregister(r) }
    }
    // setPopDelay(100)
    LaunchedEffect(state.msgKey) {
        state.popVisible = false
        if (state.msgKey != null) {
            delay(state.config.popDelayMs)
            state.popVisible = true
        }
    }
    // setPopAnimationStyle：弹窗缩放淡入（关掉时直接显示）
    val popAppear = remember { Animatable(0f) }
    LaunchedEffect(state.popVisible) {
        if (state.popVisible) popAppear.animateTo(1f, tween(140)) else popAppear.snapTo(0f)
    }
    val density = LocalDensity.current
    val bounds = state.textBounds
    Box(Modifier.fillMaxSize()) {
        if (state.active) {
            // 遮罩：点空白退出（轻微压暗提示选择模式）
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.05f))
                    .clickable { ChatTextSelectEventBus.dismissAllPop() },
            )
            if (bounds != null && state.popVisible && !state.customPop) {
                val items = state.popItems.ifEmpty {
                    defaultPopItems(state, onCopy, onShare, onQuote)
                }
                val span = state.config.popSpanCount.coerceAtLeast(1)
                val rows = items.chunked(span)
                val maxCols = rows.maxOf { it.size }
                val popW = (56f * maxCols + 12f).dp
                val popH = (56f * rows.size + 12f).dp
                val placeBelow = (bounds.top - rootOrigin.y) < with(density) { (popH + 24.dp).toPx() }
                val shape = RoundedCornerShape(state.config.popCornerRadiusDp.dp)
                Box(
                    Modifier
                        .width(popW)
                        .height(popH + 6.dp)
                        .graphicsLayer {
                            val v = if (state.config.popAnimationStyle) popAppear.value else 1f
                            alpha = v
                            scaleX = 0.88f + 0.12f * v
                            scaleY = 0.88f + 0.12f * v
                        }
                        .offset {
                            val topPx = bounds.top - rootOrigin.y
                            val bottomPx = bounds.bottom - rootOrigin.y
                            val popWPx = popW.toPx()
                            val popHPx = popH.toPx()
                            val y = if (placeBelow) bottomPx + 12.dp.toPx() else topPx - popHPx - 18.dp.toPx()
                            val centerPx = (bounds.left + bounds.right) / 2f - rootOrigin.x
                            IntOffset((centerPx - popWPx / 2f).roundToInt(), y.roundToInt())
                        },
                ) {
                    Box(
                        Modifier
                            .width(popW)
                            .height(popH)
                            .clip(shape)
                            .background(state.config.popBgColor)
                            .padding(6.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.Center) {
                            rows.forEach { row ->
                                Row {
                                    row.forEach { item ->
                                        SelectPopCell(item, state.config.popItemTextColor) {
                                            item.onClick(state.selectedText())
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // setPopStyle 的箭头：贴在弹窗靠选区一侧
                    if (state.config.popArrowShow) {
                        Canvas(
                            Modifier
                                .size(width = 12.dp, height = 6.dp)
                                .align(if (placeBelow) Alignment.TopCenter else Alignment.BottomCenter)
                                .offset(y = if (placeBelow) (-5).dp else 5.dp),
                        ) {
                            val p = Path().apply {
                                if (placeBelow) {
                                    moveTo(0f, size.height); lineTo(size.width / 2f, 0f); lineTo(size.width, size.height)
                                } else {
                                    moveTo(0f, 0f); lineTo(size.width / 2f, size.height); lineTo(size.width, 0f)
                                }
                                close()
                            }
                            drawPath(p, state.config.popBgColor)
                        }
                    }
                }
            }
        }
        // 双击查看文本内容（库 demo 的 SelectTextDialog）：全屏纯文本层
        ChatTextSelectEventBus.viewerText?.let { full ->
            SelectTextDialog(
                text = full,
                onCopyAll = { onCopy(full); ChatTextSelectEventBus.closeViewer() },
                onSelect = {
                    ChatTextSelectEventBus.closeViewer()
                    state.enter("viewer", full, state.popItems)
                },
                onDismiss = { ChatTextSelectEventBus.closeViewer() },
            )
        }
    }
}

/** addItem 默认项（库 demo 只 add 了「复制」，这里按用户要的 复制/全选/引用/分享 补齐）。 */
private fun defaultPopItems(
    state: ChatTextSelectState,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onQuote: ((String) -> Unit)?,
): List<SelectPopItem> = mutableListOf<SelectPopItem>().apply {
    add(SelectPopItem(COUIIcons.Copy, "复制") { t -> if (t.isNotBlank()) onCopy(t); state.reset() })
    add(SelectPopItem(COUIIcons.Ok, "全选") { state.selectAll() })
    if (onQuote != null) add(SelectPopItem(COUIIcons.Reply, "引用") { t -> if (t.isNotBlank()) onQuote(t); state.reset() })
    add(SelectPopItem(COUIIcons.Share, "分享") { t -> if (t.isNotBlank()) onShare(t); state.reset() })
    add(SelectPopItem(COUIIcons.Undo, "取消") { state.reset() })
}

@Composable
private fun SelectPopCell(item: SelectPopItem, color: Color, onClick: () -> Unit) {
    Column(
        Modifier
            .size(width = 56.dp, height = 56.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable { haptic(HapticKind.Tick); onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        item.icon?.let {
            Icon(it, contentDescription = item.label, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(item.label, color = color, fontSize = 11.sp, maxLines = 1)
    }
}

/**
 * 双击查看文本内容：库 demo 的 `SelectTextDialog`，全屏深色纯文本层，
 * dismiss 时调 reset()（这里由 closeViewer → dismissAllPop 完成）。
 */
@Composable
private fun SelectTextDialog(
    text: String,
    onCopyAll: () -> Unit,
    onSelect: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.88f))
                .clickable { onDismiss() },
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 48.dp)
                // 吞掉点击，避免看文字时误触关闭
                .pointerInput(Unit) { detectTapGestures { } },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text,
                color = Color.White,
                fontSize = 19.sp,
                lineHeight = 29.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            )
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DialogAction("复制全文", onCopyAll)
                DialogAction("选择文字", onSelect)
                DialogAction("关闭", onDismiss)
            }
        }
    }
}

@Composable
private fun DialogAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.16f))
            .clickable { haptic(HapticKind.Tick); onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 12.5.sp)
    }
}
