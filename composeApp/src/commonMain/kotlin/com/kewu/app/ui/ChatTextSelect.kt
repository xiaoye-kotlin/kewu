package com.kewu.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.HapticKind
import com.kewu.app.haptic
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlin.math.roundToInt

/**
 * 聊天文字选择（2026-11-04 P4，交互参考 ITxiaoguang/SelectTextHelper，Compose 重写）：
 * 长按文字进入选择模式（默认全选整段纯文本）→ 拖动起/止两个手柄调整选区 →
 * 气泡上方浮出 coui 胶囊操作条（复制 / 全选 / [引用] / 分享）。点空白或返回键退出。
 *
 * - 选择模式下气泡渲染**纯文本**（stripMarkdown 后），高亮用 AnnotatedString 背景色区间，稳定且可拖；
 * - 手柄拖拽经 TextLayoutResult.getOffsetForPosition 换算字符偏移（y 自动吸附到最近行）；
 * - 两端共用：学生端 ChatAiBubble / ChatUserBubble，教师端 TaBubble 传入 [ChatTextSelectState]。
 */

/** 页面级文字选择状态（同一时刻最多一条消息在选择）。 */
internal class ChatTextSelectState {
    /** 消息标识（两端都用消息 id）；null = 未在选择 */
    var msgKey by mutableStateOf<Any?>(null)
    /** 选择模式下的纯文本全文 */
    var fullText by mutableStateOf("")
    /** 字符选区 */
    var selection by mutableStateOf(TextRange.Zero)
    /** 选择文本区的窗口矩形（工具条定位用，进入选择后由SelectableChatText 上报） */
    var textBounds by mutableStateOf<Rect?>(null)

    val active: Boolean get() = msgKey != null

    fun enter(key: Any, text: String) {
        msgKey = key
        fullText = text
        selection = TextRange(0, text.length)
    }

    fun exit() {
        msgKey = null
        fullText = ""
        selection = TextRange.Zero
        textBounds = null
    }

    fun selectedText(): String {
        val s = selection.min.coerceIn(0, fullText.length)
        val e = selection.max.coerceIn(0, fullText.length)
        return if (e > s) fullText.substring(s, e) else ""
    }
}

/**
 * 选择模式的纯文本渲染：选中区间背景高亮 + 两个拖柄。
 * 颜色随气泡传入（AI 气泡暗字浅底 / 用户气泡白字品牌底），选中背景统一半透明品牌蓝。
 */
@Composable
internal fun SelectableChatText(
    text: String,
    state: ChatTextSelectState,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.5.sp,
    lineHeight: androidx.compose.ui.unit.TextUnit = 20.sp,
    /** 选区背景色（调用方按气泡底色给对比度） */
    selectedBg: Color,
) {
    val cs = COUITheme.colorScheme
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var textOrigin by remember { mutableStateOf(Offset.Zero) }
    val sel = state.selection
    val annotated: AnnotatedString = remember(text, sel, color, fontSize, selectedBg) {
        buildAnnotatedString {
            append(text)
            addStyle(SpanStyle(color = color, fontSize = fontSize), 0, text.length)
            val s = sel.min.coerceIn(0, text.length)
            val e = sel.max.coerceIn(0, text.length)
            if (e > s) addStyle(SpanStyle(background = selectedBg), s, e)
        }
    }
    Box(
        Modifier
            // ★ 2026-11-04（U3）：这里原先是 fillMaxWidth() —— 长按进入选择模式时文本区
            //   从「按内容自适应」突然撑满气泡最大宽度，用户看到「自己的气泡突然变长」。
            //   改成 wrapContentSize，与常态 Text 完全同一套测量约束，宽度不再跳。
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
            // 起手柄：插在选区首字符上方；止手柄：插在选区末字符下方（draw 在文本外，不占布局）
            SelectHandle(lr, clampMin, isStart = true, textOrigin = textOrigin, color = cs.primary) { off ->
                state.selection = TextRange(off.coerceAtMost(state.selection.max), state.selection.max)
            }
            SelectHandle(lr, (clampMax - 1).coerceAtLeast(0), isStart = false, textOrigin = textOrigin, color = cs.primary) { off ->
                // 止手柄拖的是末字符本身 → 选区终点 = 该字符右沿
                state.selection = TextRange(state.selection.min, (off + 1).coerceAtLeast(state.selection.min))
            }
        }
    }
}

/** 单个选择手柄：圆点 + 竖线；拖动经窗口坐标换算成文本内坐标再取字符偏移。 */
@Composable
private fun SelectHandle(
    lr: TextLayoutResult,
    offset: Int,
    isStart: Boolean,
    textOrigin: Offset,
    color: Color,
    onDragTo: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val dotR = 4.dp
    val lineH = 20.dp
    val box = lr.getBoundingBox(offset)
    val anchorX = box.left + box.width / 2f
    val anchorY = if (isStart) box.top else box.bottom
    var handleOrigin by remember { mutableStateOf(Offset.Zero) }
    // 手柄整体高度 = 圆点直径 + 竖线；起点手柄在字符上方，止点手柄在字符下方
    Box(
        Modifier
            .offset {
                IntOffset(
                    x = (anchorX - with(density) { dotR.toPx() }).roundToInt(),
                    y = if (isStart) {
                        (anchorY - with(density) { (dotR * 2 + lineH).toPx() }).roundToInt()
                    } else {
                        anchorY.roundToInt()
                    },
                )
            }
            .width(dotR * 2)
            .height(dotR * 2 + lineH)
            .onGloballyPositioned { handleOrigin = it.boundsInWindow().topLeft }
            // 放大触控：手柄热区有限，整片都响应拖动
            .pointerInput(textOrigin, isStart) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val local = change.position + (handleOrigin - textOrigin)
                    onDragTo(lr.getOffsetForPosition(local))
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = dotR.toPx()
            val cx = size.width / 2f
            if (isStart) {
                drawCircle(color, radius = r, center = Offset(cx, r))
                drawLine(color, Offset(cx, r * 2), Offset(cx, r * 2 + lineH.toPx()), strokeWidth = 2.dp.toPx())
            } else {
                drawLine(color, Offset(cx, 0f), Offset(cx, lineH.toPx()), strokeWidth = 2.dp.toPx())
                drawCircle(color, radius = r, center = Offset(cx, lineH.toPx() + r))
            }
        }
    }
}

/**
 * 选择操作条：渲染在页面根（[rootOrigin] 是页面根 Box 的窗口原点），贴选择文本区上方居中，
 * 放不下（顶部空间不足）时翻到下方。点遮罩空白处退出选择。
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
    if (!state.active) return
    val cs = COUITheme.colorScheme
    val density = LocalDensity.current
    val bounds = state.textBounds
    Box(Modifier.fillMaxSize()) {
        // 遮罩：点空白退出（轻微压暗提示选择模式）
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.05f))
                .clickable { state.exit() },
        )
        if (bounds != null) {
            val relTop = bounds.top - rootOrigin.y
            val relBottom = bounds.bottom - rootOrigin.y
            val barH = 46.dp
            val placeBelow = relTop < with(density) { (barH + 60.dp).toPx() }
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset {
                        val y = if (placeBelow) relBottom + with(density) { 10.dp.toPx() }
                        else relTop - with(density) { (barH + 10.dp).toPx() }
                        IntOffset(0, y.roundToInt())
                    },
                cornerRadius = 23.dp,
                colors = kewuCardColors(),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    ToolbarAction("复制") {
                        val t = state.selectedText()
                        if (t.isNotBlank()) onCopy(t)
                        state.exit()
                    }
                    ToolbarAction("全选") { state.selection = TextRange(0, state.fullText.length) }
                    if (onQuote != null) {
                        ToolbarAction("引用") {
                            val t = state.selectedText()
                            if (t.isNotBlank()) onQuote(t)
                            state.exit()
                        }
                    }
                    ToolbarAction("分享") {
                        val t = state.selectedText()
                        if (t.isNotBlank()) onShare(t)
                        state.exit()
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolbarAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = COUITheme.colorScheme.primary,
        fontSize = 13.5.sp,
        modifier = Modifier.clickable { haptic(HapticKind.Tick); onClick() },
    )
}
