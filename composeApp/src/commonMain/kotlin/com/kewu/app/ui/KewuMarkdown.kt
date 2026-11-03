package com.kewu.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * AI 回答的轻量 Markdown 渲染（2026-11-02 夜）：模型常输出 `**加粗**`、`代码`、# 标题、
 * - 列表、> 引用、[链接](url)，旧版原样显示成满屏 `**`。这里解析成 AnnotatedString 样式。
 *
 * 只覆盖 AI 实际会用的子集（加粗 / 斜体 / 行内代码 / 1-4 级标题 / 无序与有序列表 / 引用 / 链接），
 * 不引入第三方库；流式输出时标记没闭合就按原样字符显示，不会闪跳。
 */

/** 解析 [text] 为带样式的 [AnnotatedString]；颜色随主题传入，[baseSize] 为正文字号。 */
internal fun parseKewuMarkdown(
    text: String,
    base: Color,
    secondary: Color,
    primary: Color,
    codeBg: Color,
    baseSize: TextUnit,
): AnnotatedString = buildAnnotatedString {
    val boldStyle = SpanStyle(fontWeight = FontWeight.Bold)
    val italicStyle = SpanStyle(fontStyle = FontStyle.Italic)
    val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace, fontSize = baseSize * 0.92f,
        color = primary, background = codeBg,
    )
    val linkStyle = SpanStyle(color = primary)

    fun appendInline(s: String) {
        var i = 0
        while (i < s.length) {
            when {
                // **加粗**（先于单 * 判断）
                s.startsWith("**", i) -> {
                    val j = s.indexOf("**", i + 2)
                    if (j > i + 2) {
                        withStyle(boldStyle) { append(s.substring(i + 2, j)) }
                        i = j + 2
                    } else { append("**"); i += 2 }
                }
                // __加粗__
                s.startsWith("__", i) -> {
                    val j = s.indexOf("__", i + 2)
                    if (j > i + 2) {
                        withStyle(boldStyle) { append(s.substring(i + 2, j)) }
                        i = j + 2
                    } else { append("__"); i += 2 }
                }
                // `行内代码`
                s[i] == '`' -> {
                    val j = s.indexOf('`', i + 1)
                    if (j > i + 1) {
                        withStyle(codeStyle) { append(s.substring(i + 1, j)) }
                        i = j + 1
                    } else { append('`'); i++ }
                }
                // [文字](链接)：只显示文字，主题色提示可点（不内嵌浏览器，保持轻量）
                s[i] == '[' -> {
                    val close = s.indexOf("](", i)
                    val end = if (close > i) s.indexOf(')', close + 2) else -1
                    if (close > i + 1 && end > close) {
                        withStyle(linkStyle) { append(s.substring(i + 1, close)) }
                        i = end + 1
                    } else { append('['); i++ }
                }
                // *斜体* / _斜体_（单个，且内部非空白才认，避免误吃乘号 3*4*5）
                s[i] == '*' || s[i] == '_' -> {
                    val c = s[i]
                    val j = s.indexOf(c, i + 1)
                    val inner = if (j > i + 1) s.substring(i + 1, j) else ""
                    if (j > i + 1 && inner.isNotBlank() && !inner.endsWith(c)) {
                        withStyle(italicStyle) { append(inner) }
                        i = j + 1
                    } else { append(c); i++ }
                }
                else -> { append(s[i]); i++ }
            }
        }
    }

    text.lines().forEachIndexed { idx, raw ->
        if (idx > 0) append('\n')
        val line = raw.trimEnd()
        if (line.isBlank()) return@forEachIndexed
        // ── 块级：标题 / 引用 / 列表 / 分割线 ──
        val heading = Regex("^(#{1,4})\\s+(.+)$").find(line)
        if (heading != null) {
            val level = heading.groupValues[1].length
            withStyle(SpanStyle(
                fontSize = baseSize * (1.06f + (4 - level) * 0.08f),
                fontWeight = FontWeight.Bold, color = base,
            )) { appendInline(heading.groupValues[2]) }
            return@forEachIndexed
        }
        if (Regex("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$").matches(line)) {
            withStyle(SpanStyle(color = secondary)) { append("———") }
            return@forEachIndexed
        }
        val quote = Regex("^>\\s?(.*)$").find(line)
        if (quote != null) {
            withStyle(SpanStyle(color = secondary)) { append("│ ") }
            appendInline(quote.groupValues[1])
            return@forEachIndexed
        }
        val bullet = Regex("^(\\s*)([-*·]|\\d+[.、)])\\s+(.+)$").find(line)
        if (bullet != null) {
            val marker = bullet.groupValues[2]
            withStyle(SpanStyle(color = secondary)) {
                append(if (marker.first().isDigit()) "$marker " else "· ")
            }
            appendInline(bullet.groupValues[3])
            return@forEachIndexed
        }
        appendInline(line.trim())
    }
}

/**
 * Markdown 渲染文本（AI 气泡用）：主题色随 [COUITheme] 取，正文样式由调用方传。
 * 纯展示——链接不内嵌浏览器，行内代码不换底色块以外的容器。
 */
@Composable
internal fun KewuMarkdownText(
    text: String,
    color: Color,
    fontSize: TextUnit = 13.5.sp,
    lineHeight: TextUnit = 20.sp,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    val cs = COUITheme.colorScheme
    val annotated = remember(text, color, fontSize, cs.onSurfaceSecondary, cs.primary, cs.secondaryContainer) {
        parseKewuMarkdown(text, color, cs.onSurfaceSecondary, cs.primary, cs.secondaryContainer, fontSize)
    }
    Text(annotated, color = color, fontSize = fontSize, lineHeight = lineHeight, modifier = modifier, maxLines = maxLines)
}
