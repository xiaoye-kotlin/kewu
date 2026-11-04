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
 * AI 推荐视频的进程级结果缓存（2026-11-04 修复状态消失）：key = 搜索词。
 * 旧版结果存在 composable 的 remember 里 —— 列表项滑出屏幕（LazyColumn 销毁重组）或
 * 切走页面再回来，状态就丢：卡片重新显示「正在找视频…」，且 onLoaded 再次触发强制滚到底。
 * 现在已加载结果（含空列表=无结果）常驻这里，重建即复用、不再重复请求 / 不再误触发滚动。
 */
internal object BiliSuggestCache {
    val results = androidx.compose.runtime.mutableStateMapOf<String, List<BilibiliVideoInfo>>()
}

/**
 * 应用内播放请求总线：视频卡在列表（LazyColumn）里，覆盖层必须渲染到页面根部才能全屏，
 * 各卡片只负责设置它，App 根部唯一的 [BilibiliPlayerHost] 消费（页面里不要再挂，否则同一视频会被建两个播放器）。
 */
internal object VideoPlayerHub {
    var current by mutableStateOf<BilibiliVideoInfo?>(null)
}

/**
 * 合集 / 分P 分集面板总线（2026-11-04）：带分集的视频卡被点击时设置它，
 * App 根部唯一的 [BiliEpisodesHost] 渲染全屏分集列表面板（与播放层同一套「页面只管设置、根部统一渲染」模式）。
 */
internal object BiliEpisodesHub {
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
    /** 自定义点击（如知识点弹窗：先关弹窗再播放）；为空时：有分集→打开分集面板，没有→直接播放 */
    onClick: (() -> Unit)? = null,
    /** 列表中的序号：错峰上浮入场（2026-11-02 用户：选视频时缺少动画） */
    index: Int = 0,
) {
    val cs = COUITheme.colorScheme
    val p = rememberEnterProgress(index, baseDelayMs = 40L, stepMs = 55L)
    // 2026-11-04：合集卡显示合集名；分P 视频显示「全 N 集」标记。两种都带 episodes，点击进分集面板。
    val isSeason = video.seasonId != 0L && video.seasonTitle.isNotBlank()
    val displayTitle = if (isSeason) video.seasonTitle else video.title
    Card(
        modifier = modifier.fillMaxWidth().riseEnter(p, 36f),
        cornerRadius = 14.dp,
        insideMargin = PaddingValues(10.dp),
        colors = kewuCardColors(),
        onClick = {
            haptic(HapticKind.Tick)
            onClick?.invoke() ?: run {
                if (video.episodes.isNotEmpty()) BiliEpisodesHub.current = video
                else VideoPlayerHub.current = video
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 封面（网络图走共享 ImageLoader；失败也给占位底）
            Box(
                Modifier
                    .size(width = 104.dp, height = 66.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(cs.secondaryContainer),
            ) {
                RemoteImage(if (isSeason && video.seasonCover.isNotBlank()) video.seasonCover else video.cover, Modifier.fillMaxSize())
                // 播放角标
                Box(
                    Modifier.align(Alignment.Center).size(26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(COUIIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp)) }
                // 时长角标（右下角，B 站同款半透明黑底）
                if (video.duration.isNotBlank()) {
                    Text(
                        video.duration, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                            .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
                // 合集/分P 标记（左上角粉色小胶囊，B 站同款语言）
                val badge = when {
                    isSeason && video.seasonCount > 0 -> "合集 · ${video.seasonCount}"
                    video.pageCount > 1 -> "全 ${video.pageCount} 集"
                    else -> ""
                }
                if (badge.isNotBlank()) {
                    Text(
                        badge, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                            .clip(RoundedCornerShape(4.dp)).background(BILI_PINK.copy(alpha = 0.92f))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    displayTitle, color = cs.onSurface, fontSize = 13.sp,
                    fontWeight = FontWeight.Medium, lineHeight = 18.sp, maxLines = 2,
                )
                if (video.episodes.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (isSeason) "合集 · 点我看全部 ${video.episodes.size} 个视频"
                        else "共 ${video.episodes.size} 个分集 · 点我查看",
                        color = BILI_PINK, fontSize = 11.sp, maxLines = 1,
                    )
                }
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
 * 2026-11-04：同一合集的多个搜索结果只发一张合集卡（distinctBy seasonId）；
 * [onLoaded] 在视频加载完成时回调（页面用来触发自动滚到底，见 ChatAutoScroll forceKey）。
 */
@Composable
internal fun BilibiliVideoSuggest(
    keyword: String,
    maxCount: Int = 3,
    modifier: Modifier = Modifier,
    onLoaded: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    // null=加载中 / 空列表或失败=空 / 非空=结果。
    // 2026-11-04：优先读进程级缓存（滑出屏幕 / 切页再回来不丢、不重新请求）；
    // 缓存未命中才真正请求，且只有真正请求完成那一次才触发 onLoaded（避免回到列表被强行拽到底）。
    var videos by remember(keyword) { mutableStateOf(BiliSuggestCache.results[keyword]) }
    LaunchedEffect(keyword) {
        if (videos == null) {
            val loaded = KewuApi.getBilibiliVideos(keyword).orEmpty().take(maxCount)
            BiliSuggestCache.results[keyword] = loaded
            videos = loaded
            onLoaded?.invoke()
        }
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
        // 同一合集只出一张卡（后端会给同合集的多个结果都带 seasonId）
        val display = list.distinctBy { if (it.seasonId != 0L) "s${it.seasonId}" else it.bvid }
        Column(modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            display.forEachIndexed { i, v -> BilibiliVideoCard(v, index = i) }
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
        Text("没找到「$keyword」相关视频", color = cs.onSurfaceSecondary, fontSize = 12.sp)
    }
}

// ───────────────────────── 手动浏览面板 ─────────────────────────

/** 顶部标签的「推荐」项（选中时显示为你推荐列表）。 */
private const val BILI_TAB_REC = "推荐"

private val BILI_SUBJECTS = listOf(
    "高等数学", "线性代数", "概率论", "大学物理", "C 语言", "数据结构", "英语四级", "思修", "近代史",
)

/**
 * 「B 站学习视频」全屏面板（2026-11-02 重做）：液态玻璃搜索栏 + 顶部标签（「推荐」+ 学科）+ 推荐列表 / 搜索结果。
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
    // 当前选中的顶部标签：「推荐」/ 某学科 / ""（搜索框自定义搜索）
    var tab by remember { mutableStateOf(BILI_TAB_REC) }

    // 切标签 / 再搜时取消上一次搜索，免得旧结果晚到盖掉当前标签
    var searchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun search(subject: String) {
        val s = subject.trim()
        if (s.isEmpty()) return
        haptic(HapticKind.Light)
        searchJob?.cancel()
        searching = true
        results = null
        searchJob = scope.launch {
            results = KewuApi.getBilibiliVideos(s).orEmpty()
            searching = false
            searchSeq++
        }
    }
    fun showRecommend() {
        searchJob?.cancel()
        searching = false
        results = null
        query = ""
        tab = BILI_TAB_REC
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
                            keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) tab = ""; search(query) }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    AnimatedVisibility(
                        visible = query.isNotEmpty(),
                        enter = scaleIn(spring(dampingRatio = 0.6f, stiffness = 500f)) + fadeIn(),
                        exit = scaleOut(tween(120)) + fadeOut(tween(120)),
                    ) {
                        IconButton(onClick = { haptic(HapticKind.Tick); showRecommend() }, minWidth = 32.dp, minHeight = 32.dp, cornerRadius = 16.dp) {
                            Icon(COUIIcons.Close, contentDescription = "清空", tint = cs.onSurfaceSecondary, modifier = Modifier.size(14.dp))
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    // 绿芯搜索钮（与发送钮同语言）：有输入才亮
                    val active = query.isNotBlank()
                    Box(
                        Modifier.size(42.dp).clip(CircleShape)
                            .background(if (active) KewuGreen else cs.onSurface.copy(alpha = 0.08f))
                            .clickable(enabled = active) { tab = ""; search(query) },
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
                Chip(
                    selected = tab == BILI_TAB_REC,
                    onClick = { haptic(HapticKind.Tick); showRecommend() },
                    label = BILI_TAB_REC,
                )
                BILI_SUBJECTS.forEach { s ->
                    Chip(
                        selected = tab == s,
                        onClick = { haptic(HapticKind.Tick); tab = s; query = s; search(s) },
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
                        Text("正在搜索…", color = cs.onSurfaceSecondary, fontSize = 13.sp)
                    }
                    results!!.isEmpty() -> KewuEmpty("没有相关视频，换个词试试")
                    else -> {
                        Text("「${query.trim()}」的搜索结果", color = cs.onSurfaceSecondary, fontSize = 12.5.sp, modifier = Modifier.padding(start = 4.dp))
                        // key 跟随搜索次数：每次新结果都重新播一遍错峰入场
                        androidx.compose.runtime.key(searchSeq) {
                            results!!.forEachIndexed { i, v -> BilibiliVideoCard(v, index = i) }
                        }
                    }
                }
                // 底部给悬浮导航栏让位（2026-11-02 用户报「换一批推荐」被导航栏挡住点不到）
                Spacer(Modifier.height(KewuNavClearance))
            }
        }
    }
}

// ───────────────────────── 合集 / 分P 分集面板（2026-11-04） ─────────────────────────

/**
 * 分集列表面板宿主：全 App 根部唯一（App.kt 与 [BilibiliPlayerHost] 并列挂载）。
 * 带分集的视频卡（合集卡 / 多分P 卡）点击后经 [BiliEpisodesHub] 打开；
 * 点某集现取直链播放，播放层盖在面板之上，退出播放回到面板继续选集。
 */
@Composable
internal fun BiliEpisodesHost() {
    SlideOverlay(BiliEpisodesHub.current) { video ->
        BiliEpisodesPanel(video, onDismiss = { BiliEpisodesHub.current = null })
    }
}

@Composable
private fun BiliEpisodesPanel(video: BilibiliVideoInfo, onDismiss: () -> Unit) {
    val cs = COUITheme.colorScheme
    val scope = rememberCoroutineScope()
    val isSeason = video.seasonId != 0L && video.seasonTitle.isNotBlank()
    val title = if (isSeason) video.seasonTitle else video.title
    val cover = if (isSeason && video.seasonCover.isNotBlank()) video.seasonCover else video.cover
    // 正在取直链的集（bvid:cid，分P 多集共享 bvid 时也能区分）；同一时刻只允许一集在取
    var fetchingKey by remember { mutableStateOf<String?>(null) }
    // ★ V8：取直链失败时给可见反馈，别再静默打开一个没有直链的空白播放层（用户说「点了没效果」）
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var lastFailedEp by remember { mutableStateOf<com.kewu.app.BiliEpisodeInfo?>(null) }

    fun play(ep: com.kewu.app.BiliEpisodeInfo) {
        val key = "${ep.bvid}:${ep.cid}"
        if (fetchingKey != null) return
        haptic(HapticKind.Tick)
        errorMsg = null
        scope.launch {
            fetchingKey = key
            // 1) 新后端：现取这一集直链（/api/bilibili/play）
            var info = KewuApi.getBilibiliPlay(ep.bvid, ep.cid)
            // 2) 兼容旧后端（该端点旧 jar 因 mapOf 序列化器 bug 返回 code:1）：
            //    退回学科搜索结果里按 bvid 找直链，让每一集点下去都真的能播
            if (info?.videoUrl == null) {
                val kw = (if (isSeason) video.seasonTitle else video.title).ifBlank { ep.title }
                val list = KewuApi.getBilibiliVideos(kw).orEmpty()
                val fallback = list.firstOrNull { it.bvid == ep.bvid && it.videoUrl != null }
                    ?: list.firstOrNull { it.videoUrl != null }
                if (fallback != null) info = fallback
            }
            fetchingKey = null
            if (info?.videoUrl != null) {
                VideoPlayerHub.current = info
            } else {
                lastFailedEp = ep
                errorMsg = "这一集暂时取不到播放地址（服务器可能还是旧版本），点重试再试一次"
            }
        }
    }

    BackHandler { onDismiss() }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().layerBackdrop(rememberGlassBackdrop()).background(cs.surface))
        Column(Modifier.fillMaxSize()) {
            HubTopBar(title, onDismiss)
            // 头部信息卡：封面 + 来源视频名 + 分集数
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                cornerRadius = 14.dp,
                insideMargin = PaddingValues(10.dp),
                colors = kewuCardColors(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(width = 104.dp, height = 66.dp).clip(RoundedCornerShape(10.dp)).background(cs.secondaryContainer),
                    ) { RemoteImage(cover, Modifier.fillMaxSize()) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            title, color = cs.onSurface, fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold, lineHeight = 19.sp, maxLines = 2,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            (if (isSeason) "合集" else "分P") + " · 共 ${video.episodes.size} 集",
                            color = BILI_PINK, fontSize = 12.sp, maxLines = 1,
                        )
                    }
                }
            }
            // ★ V8：取直链失败给可见反馈 + 一键重试（旧版静默打开空白播放层，看着像「点了没效果」）
            if (errorMsg != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(BILI_PINK.copy(alpha = 0.10f))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(errorMsg.orEmpty(), color = BILI_PINK, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "重试", color = Color.White, fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(BILI_PINK)
                            .clickable {
                                errorMsg = null
                                lastFailedEp?.let { play(it) }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            // 分集列表
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                items(video.episodes.size) { i ->
                    val ep = video.episodes[i]
                    val fetching = fetchingKey == "${ep.bvid}:${ep.cid}"
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 12.dp,
                        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 9.dp),
                        colors = kewuCardColors(),
                        onClick = { if (!fetching) play(ep) },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.clip(RoundedCornerShape(6.dp)).background(BILI_PINK.copy(alpha = 0.14f))
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                            ) {
                                Text("P${i + 1}", color = BILI_PINK, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                ep.title.ifBlank { "第 ${i + 1} 集" },
                                color = cs.onSurface, fontSize = 13.5.sp, maxLines = 2,
                                lineHeight = 18.sp, modifier = Modifier.weight(1f),
                            )
                            if (fetching) {
                                Spacer(Modifier.width(8.dp))
                                InfiniteProgressIndicator(Modifier.size(16.dp))
                            } else if (ep.duration.isNotBlank()) {
                                Spacer(Modifier.width(8.dp))
                                Text(ep.duration, color = cs.onSurfaceSecondary, fontSize = 11.5.sp, maxLines = 1)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
