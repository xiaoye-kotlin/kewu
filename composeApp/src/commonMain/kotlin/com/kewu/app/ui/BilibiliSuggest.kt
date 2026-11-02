package com.kewu.app.ui

import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import com.kewu.app.KewuGreen
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.Play
import io.github.suqi8.coui.kmp.icon.extended.Search
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
 * - [BilibiliVideoSuggest]：AI 回答带 [[BILI:关键词]] 标记时（只在学生问不懂的知识点时，见 BILI_TAG_RULE）拉视频，插在回答下方（最多 3 条）；
 *   加载中给占位、没结果 / 失败显示 [BilibiliSuggestRow] 提示行。
 * - [BilibiliSuggestRow]：没搜到视频的提示（不跳外部 App / 浏览器）。
 * - [BilibiliBrowsePanel]：助教页手动浏览面板（搜索框 + 学科快捷词 + 结果列表）。
 *
 * 点击视频卡 → 应用内直接播放（2026-11-02 用户明确要求，不跳 B 站 App）：
 * [VideoPlayerHub] 记录播放请求，[BilibiliPlayerHost]（App 根部唯一一个，见 BiliPlayerOverlay.kt）渲染
 * 全屏播放层（含 AI 视频总结与提问），ExoPlayer 合并后端返回的 DASH video+audio 直链。
 * videoUrl 为空 / 直链过期 → 覆盖层给「重试 / 返回」，不回退成跳转外部 App。
 */

/** AI 回复携带的 B 站推荐载荷（经 AssistantHub.Reply.payload 传递）。 */
internal data class BiliSuggest(val keyword: String)

/**
 * 应用内播放请求总线：视频卡在列表（LazyColumn）里，覆盖层必须渲染到页面根部才能全屏，
 * 各卡片只负责设置它，App 根部唯一的 [BilibiliPlayerHost] 消费（页面里不要再挂，否则同一视频会被建两个播放器）。
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
internal fun BilibiliVideoCard(
    video: BilibiliVideoInfo,
    modifier: Modifier = Modifier,
    /** 自定义点击（如知识点弹窗：先关弹窗再播放）；为空时直接交给 App 根部播放层 */
    onClick: (() -> Unit)? = null,
    /** 列表中的序号：错峰上浮入场（2026-11-02 用户：选视频时缺少动画） */
    index: Int = 0,
) {
    val cs = COUITheme.colorScheme
    val p = rememberEnterProgress(index, baseDelayMs = 40L, stepMs = 55L)
    Card(
        modifier = modifier.fillMaxWidth().riseEnter(p, 36f),
        cornerRadius = 14.dp,
        insideMargin = PaddingValues(10.dp),
        colors = kewuCardColors(),
        onClick = { haptic(HapticKind.Tick); onClick?.invoke() ?: run { VideoPlayerHub.current = video } },
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
                // 播放角标
                Box(
                    Modifier.align(Alignment.Center).size(26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(COUIIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp)) }
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
            list.forEachIndexed { i, v -> BilibiliVideoCard(v, index = i) }
        }
    }
}

/** 没搜到视频时的提示行（2026-11-02：不再跳 B 站 App / 浏览器，用户要求只在课悟内看）。 */
@Composable
internal fun BilibiliSuggestRow(keyword: String, modifier: Modifier = Modifier) {
    val cs = COUITheme.colorScheme
    Row(modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(BILI_PINK)
                .padding(horizontal = 5.dp, vertical = 2.dp),
        ) {
            Text("B站", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Text("暂时没找到「$keyword」的相关视频，可在助教页「B站视频」里换个词搜", color = cs.onSurfaceSecondary, fontSize = 12.sp)
    }
}

// ───────────────────────── 手动浏览面板 ─────────────────────────

private val BILI_SUBJECTS = listOf(
    "高等数学", "线性代数", "概率论", "大学物理", "C 语言", "数据结构", "英语四级", "思修", "近代史",
)

/**
 * 「B 站学习视频」全屏面板（2026-11-02 重做）：液态玻璃搜索栏 + 学科快捷词 + 「为你推荐」/ 搜索结果。
 * 视频卡错峰上浮入场；底部留出导航栏空间（[KewuNavClearance]），最后的按钮不会被挡。
 */
@Composable
internal fun BilibiliBrowsePanel(onDismiss: () -> Unit) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    val backdrop = rememberGlassBackdrop()
    var query by remember { mutableStateOf("") }
    // null = 未搜索（显示为你推荐）/ 非空 = 结果（可能空列表 = 无结果）
    var results by remember { mutableStateOf<List<BilibiliVideoInfo>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchSeq by remember { mutableStateOf(0) }

    fun search(subject: String) {
        val s = subject.trim()
        if (s.isEmpty() || searching) return
        haptic(HapticKind.Light)
        searching = true
        results = null
        scope.launch {
            results = KewuApi.getBilibiliVideos(s).orEmpty()
            searching = false
            searchSeq++
        }
    }

    BackHandler { onDismiss() }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().layerBackdrop(backdrop).background(cs.surface))
        Column(Modifier.fillMaxSize()) {
            HubTopBar("B 站学习视频", onDismiss)

            // ── 液态玻璃搜索胶囊（与对话输入栏同一套外观） ──
            val p = rememberEnterProgress(0, baseDelayMs = 80L)
            KewuGlassCard(
                backdrop = backdrop,
                shape = RoundedCornerShape(26.dp),
                blurRadius = glassCondenseBlur(p, 22f),
                tintAlpha = 0.38f * p,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp).glassEnter(p, 30f),
            ) {
                DockStroke(RoundedCornerShape(26.dp))
                Row(Modifier.fillMaxSize().padding(start = 16.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(COUIIcons.Search, contentDescription = null, tint = cs.onSurfaceSecondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text("搜知识点，如「定积分换元法」", color = cs.onSurfaceSecondary, fontSize = 14.sp, maxLines = 1)
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(color = cs.onSurface, fontSize = 14.sp),
                            cursorBrush = SolidColor(cs.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { search(query) }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    AnimatedVisibility(
                        visible = query.isNotEmpty(),
                        enter = scaleIn(spring(dampingRatio = 0.6f, stiffness = 500f)) + fadeIn(),
                        exit = scaleOut(tween(120)) + fadeOut(tween(120)),
                    ) {
                        IconButton(onClick = { haptic(HapticKind.Tick); query = ""; results = null }, minWidth = 32.dp, minHeight = 32.dp, cornerRadius = 16.dp) {
                            Icon(COUIIcons.Close, contentDescription = "清空", tint = cs.onSurfaceSecondary, modifier = Modifier.size(14.dp))
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    // 绿芯搜索钮（与发送钮同语言）：有输入才亮
                    val active = query.isNotBlank() && !searching
                    Box(
                        Modifier.size(42.dp).clip(CircleShape)
                            .background(if (active) KewuGreen else cs.onSurface.copy(alpha = 0.08f))
                            .clickable(enabled = active) { search(query) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (searching) InfiniteProgressIndicator(Modifier.size(18.dp))
                        else Icon(COUIIcons.Search, contentDescription = "搜索", tint = if (active) Color.White else cs.onSurfaceSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // ── 学科快捷词（coui Chip，选中态跟随当前搜索词） ──
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BILI_SUBJECTS.forEach { s ->
                    Chip(
                        selected = query == s,
                        onClick = { haptic(HapticKind.Tick); query = s; search(s) },
                        label = s,
                    )
                }
            }

            // ── 内容区：为你推荐 / 搜索中 / 结果 ──
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    !searching && results == null -> BiliRecommendSection()
                    searching -> Row(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        InfiniteProgressIndicator(Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("正在搜索，可能需要十几秒…", color = cs.onSurfaceSecondary, fontSize = 13.sp)
                    }
                    results!!.isEmpty() -> KewuEmpty("暂时没有相关视频，换个关键词试试")
                    else -> {
                        Text("「${query.trim()}」的搜索结果", color = cs.onSurfaceSecondary, fontSize = 12.5.sp, modifier = Modifier.padding(start = 4.dp))
                        // key 跟随搜索次数：每次新结果都重新播一遍错峰入场
                        androidx.compose.runtime.key(searchSeq) {
                            results!!.forEachIndexed { i, v -> BilibiliVideoCard(v, index = i) }
                        }
                        KewuSecondaryButton("回到为你推荐", { haptic(HapticKind.Tick); results = null; query = "" }, Modifier.fillMaxWidth())
                    }
                }
                // 底部给悬浮导航栏让位（2026-11-02 用户报「换一批推荐」被导航栏挡住点不到）
                Spacer(Modifier.height(KewuNavClearance))
            }
        }
    }
}
