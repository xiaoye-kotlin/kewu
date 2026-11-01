package com.kewu.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.HapticKind
import com.kewu.app.haptic
import com.kewu.app.openBilibiliSearch
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.ChevronForward
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * B 站相关视频推荐卡（2026-11-02）：AI 回答学生提问后，随回答附一条「去 B 站看相关视频」。
 * 点击优先调起哔哩哔哩 App 搜索页，没装 App 回退浏览器（[openBilibiliSearch]）。
 *
 * 说明：队友后端目前只有 bvid→笔记 的生成/查询（接口文档第 14 节），**没有搜索接口**；
 * B 站公开搜索 API 在手机网络下会被风控（-412），所以首版用「App 深链搜索页」实现，
 * 等后端出 `/api/bilibili/search` 后再换成具体视频卡片列表。
 */

/** AI 回复携带的 B 站推荐载荷（经 AssistantHub.Reply.payload 传递）。 */
internal data class BiliSuggest(val keyword: String)

private val BILI_PINK = Color(0xFFFB7299)

/** 把学生的问题清理成适合当搜索词的文本：去换行/首尾空白，截断。 */
internal fun biliKeywordOf(question: String, maxLen: Int = 30): String =
    question.replace('\n', ' ').trim().take(maxLen).trimEnd()

@Composable
internal fun BilibiliSuggestRow(keyword: String, modifier: Modifier = Modifier) {
    val cs = COUITheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        colors = kewuCardColors(),
        onClick = { haptic(HapticKind.Tick); openBilibiliSearch(keyword) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // B 站粉小徽标
            androidx.compose.foundation.layout.Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(BILI_PINK)
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Text("B站", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("去 B 站看相关视频", color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    keyword, color = cs.onSurfaceSecondary, fontSize = 11.sp, maxLines = 1,
                )
            }
            Icon(
                COUIIcons.ChevronForward, contentDescription = null,
                tint = BILI_PINK, modifier = Modifier.size(16.dp),
            )
        }
    }
}
