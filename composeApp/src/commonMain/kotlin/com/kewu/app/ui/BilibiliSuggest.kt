package com.kewu.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.BackHandler
import com.kewu.app.BilibiliVideoInfo
import com.kewu.app.DashVideoSources
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuBlue
import com.kewu.app.VideoPlayerView
import com.kewu.app.haptic
import com.kewu.app.openBilibiliSearch
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.ChevronForward
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch

/**
 * B 站相关视频推荐（2026-11-02 重做）：后端学科搜索接口 `/api/bilibili/videos?subject=…`
 * （接口文档 14.6，裸数组无壳，最多 10 条）。
 *
 * - [BilibiliVideoSuggest]：AI 回答后自动用提问关键词拉视频，插在回答下方（最多 3 条）；
 *   加载中给占位、没结果 / 失败回退成 [BilibiliSuggestRow] 深链卡（永不空窗）。
 * - [BilibiliSuggestRow]：兜底入口，直接跳 B 站 App 搜索页（未装 App 回退浏览器）。
 * - [BilibiliBrowsePanel]：助教页手动浏览面板（搜索框 + 学科快捷词 + 结果列表）。
 *
 * 点击视频卡 → 应用内直接播放（2026-11-02 用户明确要求，不跳 B 站 App）：
 * [VideoPlayerHub] 记录播放请求，[BilibiliPlayerHost] 在页面根部渲染
 * [BilibiliPlayOverlay] 全屏覆盖层，ExoPlayer 合并后端返回的 DASH video+audio 直链。
 * videoUrl 为空 / 直链过期 → 覆盖层给「重试 / 返回」，不回退成跳转外部 App。
 */

/** AI 回复携带的 B 站推荐载荷（经 AssistantHub.Reply.payload 传递）。 */
internal data class BiliSuggest(val keyword: String)

/**
 * 应用内播放请求总线：视频卡在列表（LazyColumn）里，覆盖层必须渲染到页面根部才能全屏，
 * 各卡片只负责设置它，能显示卡片的页面根部统一调用 [BilibiliPlayerHost] 消费。
 */
internal object VideoPlayerHub {
    var current by mutableStateOf<BilibiliVideoInfo?>(null)
}

private val BILI_PINK = Color(0xFFFB7299)

/** 把学生的问题清理成适合当搜索词的文本：去换行/首尾空白，截断。 */
internal fun biliKeywordOf(question: String, maxLen: Int = 30): String =
    question.replace('\n', ' ').trim().take(maxLen).trimEnd()

// ───────────────────────── 单条视频卡 ─────────────────────────

@Composable
internal fun BilibiliVideoCard(video: BilibiliVideoInfo, modifier: Modifier = Modifier) {
    val cs = COUITheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 14.dp,
        insideMargin = PaddingValues(10.dp),
        colors = kewuCardColors(),
        onClick = { haptic(HapticKind.Tick); VideoPlayerHub.current = video },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 封面（网络图走共享 ImageLoader；失败也给占位底）
            Box(
                Modifier
                    .size(width = 104.dp, height = 66.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(cs.secondaryContainer),
            ) {
                RemoteImage(video.cover, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    video.title, color = cs.onSurface, fontSize = 13.sp,
                    fontWeight = FontWeight.Medium, lineHeight = 18.sp, maxLines = 2,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "在课悟内直接播放",
                    color = BILI_PINK, fontSize = 11.sp,
                )
            }
            Icon(
                COUIIcons.ChevronForward, contentDescription = null,
                tint = BILI_PINK, modifier = Modifier.size(16.dp),
            )
        }
    }
}

// ───────────────────────── AI 回答后的自动推荐 ─────────────────────────

/**
 * AI 回答后自动推荐：用 [keyword] 拉学科视频，最多展示 [maxCount] 条。
 * 加载中显示占位行；无结果 / 接口失败回退 [BilibiliSuggestRow]。
 */
@Composable
internal fun BilibiliVideoSuggest(keyword: String, maxCount: Int = 3, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    // null=加载中 / 空列表或失败=空 / 非空=结果
    var videos by remember(keyword) { mutableStateOf<List<BilibiliVideoInfo>?>(null) }
    LaunchedEffect(keyword) {
        videos = KewuApi.getBilibiliVideos(keyword).orEmpty().take(maxCount)
    }
    val list = videos
    if (list == null) {
        // 加载占位：保持轻量，不阻塞阅读回答
        Row(modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("正在找相关 B 站视频…", color = COUITheme.colorScheme.onSurfaceSecondary, fontSize = 12.sp)
        }
    } else if (list.isEmpty()) {
        BilibiliSuggestRow(keyword, modifier)
    } else {
        Column(modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            list.forEach { BilibiliVideoCard(it) }
        }
    }
}

/** 兜底深链卡（无搜索结果时仍给学生一个入口）。 */
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
            Box(
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

// ───────────────────────── 手动浏览面板 ─────────────────────────

private val BILI_SUBJECTS = listOf(
    "数学", "语文", "英语", "物理", "化学", "生物", "历史", "地理", "政治",
)

/** 助教页手动浏览 B 站视频的全屏面板：搜索框 + 学科快捷词 + 结果列表（点击跳 B 站）。 */
@Composable
internal fun BilibiliBrowsePanel(onDismiss: () -> Unit) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    // null=未搜索 / 空=加载中 / else=结果（可能空列表 = 无结果）
    var results by remember { mutableStateOf<List<BilibiliVideoInfo>?>(null) }
    var searching by remember { mutableStateOf(false) }

    fun search(subject: String) {
        val s = subject.trim()
        if (s.isEmpty() || searching) return
        searching = true
        results = null
        scope.launch {
            results = KewuApi.getBilibiliVideos(s).orEmpty()
            searching = false
        }
    }

    BackHandler { onDismiss() }

    Box(Modifier.fillMaxSize().background(cs.surface)) {
        Column(Modifier.fillMaxSize()) {
            HubTopBar("B 站学习视频", onDismiss)
            Spacer(Modifier.height(4.dp))

            // 搜索输入行
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                cornerRadius = 20.dp,
                insideMargin = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                colors = kewuCardColors(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.weight(1f).padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (query.isEmpty()) {
                            Text("输入学科或知识点，如「高中数学」", color = cs.onSurfaceSecondary, fontSize = 13.sp)
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            textStyle = TextStyle(color = cs.onSurface, fontSize = 13.sp),
                            cursorBrush = SolidColor(cs.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { search(query) }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    IconButton(
                        onClick = { search(query) },
                        enabled = query.isNotBlank() && !searching,
                        backgroundColor = if (query.isNotBlank() && !searching) KewuBlue else cs.secondaryContainer,
                        cornerRadius = 16.dp,
                        minWidth = 36.dp,
                        minHeight = 36.dp,
                    ) {
                        Text("搜索", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // 学科快捷词
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                BILI_SUBJECTS.forEach { s ->
                    Card(
                        cornerRadius = 14.dp,
                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        colors = kewuCardColors(),
                        onClick = { haptic(HapticKind.Tick); query = s; search(s) },
                    ) {
                        Text(s, color = cs.onSurface, fontSize = 12.sp)
                    }
                }
            }

            // 结果区
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            ) {
                when {
                    searching || results == null -> Text(
                        if (searching) "正在搜索，可能需要十几秒…" else "搜一搜你想补的学科或知识点",
                        color = cs.onSurfaceSecondary, fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 20.dp),
                    )
                    results!!.isEmpty() -> Text(
                        "暂时没有相关视频，换个关键词试试",
                        color = cs.onSurfaceSecondary, fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 20.dp),
                    )
                    else -> results!!.forEach { BilibiliVideoCard(it) }
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        // 应用内播放覆盖层宿主：浏览面板里的视频卡点击后在此全屏播放
        BilibiliPlayerHost()
    }
}

// ───────────────────────── 应用内播放覆盖层 ─────────────────────────

/**
 * 页面根部消费 [VideoPlayerHub]：有播放请求时渲染全屏播放覆盖层。
 * TaPage / BilibiliBrowsePanel / ClassNoteTutorScreen 三个能出视频卡的页面根部各调一次。
 */
@Composable
internal fun BilibiliPlayerHost() {
    val video = VideoPlayerHub.current
    if (video != null) {
        BilibiliPlayOverlay(video) { VideoPlayerHub.current = null }
    }
}

/**
 * 应用内全屏播放层（2026-11-02）：主题色底 + 顶栏（标题/关闭）+ 16:9 播放器区。
 * videoUrl 为空或直链过期（B 站直链带过期参数）→ 错误面板给「重试 / 返回」，
 * 不回退成跳转外部 App（用户明确要求软件内观看）。
 */
@Composable
private fun BilibiliPlayOverlay(video: BilibiliVideoInfo, onDismiss: () -> Unit) {
    val cs = COUITheme.colorScheme
    var retryToken by remember { mutableStateOf(0) }
    var error by remember(retryToken) { mutableStateOf<String?>(null) }
    val sources = remember(video) {
        DashVideoSources(video.title, video.cover, video.videoUrl, video.audioUrl)
    }

    BackHandler { onDismiss() }

    Box(Modifier.fillMaxSize().background(cs.surface)) {
        Column(Modifier.fillMaxSize()) {
            HubTopBar(video.title, onDismiss)
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
            ) {
                if (sources.playable && error == null) {
                    VideoPlayerView(
                        sources = sources,
                        retryToken = retryToken,
                        onError = { error = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            if (sources.playable) "播放链接已失效，请重试" else "该视频暂时无法播放",
                            color = Color(0xFFBBBBBB), fontSize = 14.sp,
                        )
                        if (sources.playable) {
                            Spacer(Modifier.height(12.dp))
                            Card(
                                cornerRadius = 16.dp,
                                insideMargin = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                                colors = kewuCardColors(),
                                onClick = { haptic(HapticKind.Tick); retryToken++ },
                            ) {
                                Text("重试", color = cs.onSurface, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
