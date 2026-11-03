package com.kewu.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * AI 回答的轻量 Markdown 渲染（2026-11-03）：DeepSeek 即使被要求「不用 Markdown」也常输出 `**加粗**`、
 * `### 标题`、`- 列表`，旧版原样显示一堆星号。这里支持：标题、加粗 / 斜体 / 行内代码、
 * 有序 / 无序列表、引用、代码块、分隔线、表格（按行显示）。流式输出时未闭合的标记会原样留着，闭合后自动变样式。
 */
@Composable
internal fun MarkdownText(
    text: String,
    color: Color,
    fontSize: TextUnit = 13.5.sp,
    lineHeight: TextUnit = 20.sp,
    modifier: Modifier = Modifier,
) {
    val cs = COUITheme.colorScheme
    val blocks = remember(text) { parseMdBlocks(text) }
    val codeBg = cs.onSurface.copy(alpha = 0.06f)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Heading -> Text(
                    mdInline(b.text, cs.primary, codeBg),
                    color = color,
                    fontSize = fontSize * (if (b.level <= 2) 1.15f else 1.05f),
                    lineHeight = lineHeight * 1.1f,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 2.dp),
                )
                is MdBlock.Para -> Text(mdInline(b.text, cs.primary, codeBg), color = color, fontSize = fontSize, lineHeight = lineHeight)
                is MdBlock.Item -> Row(Modifier.padding(start = (b.indent * 12).dp)) {
                    Text(
                        b.marker, color = cs.primary, fontSize = fontSize, lineHeight = lineHeight,
                        fontWeight = FontWeight.Medium, modifier = Modifier.widthIn(min = 14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(mdInline(b.text, cs.primary, codeBg), color = color, fontSize = fontSize, lineHeight = lineHeight)
                }
                is MdBlock.Quote -> Row {
                    Box(Modifier.width(3.dp).height(lineHeight.value.dp).clip(RoundedCornerShape(2.dp)).background(cs.primary.copy(alpha = 0.5f)))
                    Spacer(Modifier.width(8.dp))
                    Text(mdInline(b.text, cs.primary, codeBg), color = color.copy(alpha = 0.8f), fontSize = fontSize, lineHeight = lineHeight)
                }
                is MdBlock.Code -> Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(codeBg)
                        .horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(b.text, color = color, fontSize = fontSize * 0.92f, lineHeight = lineHeight, fontFamily = FontFamily.Monospace)
                }
                is MdBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(cs.onSurface.copy(alpha = 0.12f)))
                is MdBlock.Table -> Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(codeBg).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    b.rows.forEachIndexed { i, cells ->
                        Text(
                            mdInline(cells.joinToString("  ·  "), cs.primary, codeBg),
                            color = color, fontSize = fontSize * 0.95f, lineHeight = lineHeight,
                            fontWeight = if (i == 0 && b.header) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

/** 去掉 Markdown 标记的纯文本（复制 / 分享 / 单行摘要用）。 */
fun stripMarkdown(text: String): String =
    parseMdBlocks(text).joinToString("\n") { b ->
        when (b) {
            is MdBlock.Heading -> b.text
            is MdBlock.Para -> b.text
            is MdBlock.Item -> "  ".repeat(b.indent) + (if (b.marker == "•") "· " else b.marker + " ") + b.text
            is MdBlock.Quote -> b.text
            is MdBlock.Code -> b.text
            is MdBlock.Rule -> ""
            is MdBlock.Table -> b.rows.joinToString("\n") { it.joinToString("  ") }
        }.let(::stripInline)
    }.trim()

private fun stripInline(s: String): String = s
    .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
    .replace(Regex("""__(.+?)__"""), "$1")
    .replace(Regex("""(?<![*\w])\*(?!\s)(.+?)(?<!\s)\*(?!\*)"""), "$1")
    .replace(Regex("""`([^`]+)`"""), "$1")
    .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Para(val text: String) : MdBlock
    data class Item(val marker: String, val text: String, val indent: Int) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data object Rule : MdBlock
    data class Table(val rows: List<List<String>>, val header: Boolean) : MdBlock
}

private val headingRe = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
private val bulletRe = Regex("""^(\s*)[-*+•·]\s+(.*)$""")
private val orderedRe = Regex("""^(\s*)(\d{1,3})[.)、]\s*(.*)$""")
private val ruleRe = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
private val tableSepRe = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

private fun parseMdBlocks(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = src.replace("\r\n", "\n").split('\n')
    var i = 0
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += MdBlock.Para(para.toString().trim())
        para.clear()
    }
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        when {
            t.startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i]); i++
                }
                out += MdBlock.Code(code.toString())
            }
            t.isEmpty() -> flush()
            ruleRe.matches(t) -> { flush(); out += MdBlock.Rule }
            headingRe.matches(t) -> {
                flush()
                val m = headingRe.find(t)!!
                out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
            }
            t.startsWith("|") && i + 1 < lines.size && tableSepRe.matches(lines[i + 1]) -> {
                flush()
                val rows = mutableListOf(splitRow(t))
                i += 2
                while (i < lines.size && lines[i].trim().startsWith("|")) { rows += splitRow(lines[i].trim()); i++ }
                out += MdBlock.Table(rows, header = true)
                continue
            }
            t.startsWith(">") -> { flush(); out += MdBlock.Quote(t.trimStart('>').trim()) }
            bulletRe.matches(line) -> {
                flush()
                val m = bulletRe.find(line)!!
                out += MdBlock.Item("•", m.groupValues[2], (m.groupValues[1].length / 2).coerceAtMost(3))
            }
            orderedRe.matches(line) && orderedRe.find(line)!!.groupValues[3].isNotBlank() -> {
                flush()
                val m = orderedRe.find(line)!!
                out += MdBlock.Item(m.groupValues[2] + ".", m.groupValues[3], (m.groupValues[1].length / 2).coerceAtMost(3))
            }
            else -> {
                // 普通行：保留 AI 的换行（聊天里每行通常就是一句），不像标准 Markdown 那样合并成一段
                flush()
                out += MdBlock.Para(t)
            }
        }
        i++
    }
    flush()
    return out
}

private fun splitRow(row: String): List<String> =
    row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

/** 行内样式：**加粗**、__加粗__、*斜体*、`代码`、[文字](链接)。 */
private fun mdInline(s: String, accent: Color, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if ((s.startsWith("**", i) || s.startsWith("__", i))) {
            val mark = s.substring(i, i + 2)
            val end = s.indexOf(mark, i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    append(mdInline(s.substring(i + 2, end), accent, codeBg))
                }
                i = end + 2
                continue
            }
        }
        if (c == '`') {
            val end = s.indexOf('`', i + 1)
            if (end > i + 1) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, color = accent)) {
                    append(s.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        if (c == '*' && i + 1 < s.length && !s[i + 1].isWhitespace() && s[i + 1] != '*') {
            val end = s.indexOf('*', i + 1)
            if (end > i + 1 && !s[end - 1].isWhitespace()) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }
        if (c == '[') {
            val close = s.indexOf("](", i + 1)
            val paren = if (close > 0) s.indexOf(')', close + 2) else -1
            if (close > i && paren > close) {
                withStyle(SpanStyle(color = accent)) { append(s.substring(i + 1, close)) }
                i = paren + 1
                continue
            }
        }
        append(c)
        i++
    }
}
