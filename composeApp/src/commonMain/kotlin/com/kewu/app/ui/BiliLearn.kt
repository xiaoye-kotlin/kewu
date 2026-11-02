package com.kewu.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.AiClient
import com.kewu.app.AssignmentRepo
import com.kewu.app.BilibiliVideoInfo
import com.kewu.app.ChatHistory
import com.kewu.app.KewuApi
import com.kewu.app.KewuGreen
import com.kewu.app.kvGet
import com.kewu.app.kvSet
import com.kewu.app.todayCn
import io.github.suqi8.coui.kmp.basic.Slider
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.overlay.OverlayDialog
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import com.kewu.app.HapticKind
import com.kewu.app.haptic
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * B 站学习辅助（2026-11-02）：
 *   - [BiliRecommend]：让 AI 从学生最近的提问、老师布置的作业、错题里提炼搜索关键词，再用后端 B 站搜索接口找视频；
 *   - [BiliRecommendSection]：「B 站学习视频」页顶部的「为你推荐」；
 *   - [KnowledgeVideoDialog]：点知识点弹出的 coui 弹窗（AI 关键词 → B 站视频，点卡片在 App 内播放）；
 *   - [MasteryStore] + [MasteryRateDialog]：看完视频后学生自评掌握度（1–100%，只能升不能降），
 *     到 100% 记为「已掌握」并留下记录（曾经薄弱的知识点）。
 * 「为你推荐」提速（2026-11-02 用户：要等约一分钟）：上次结果落盘、进页先秒出；AI 抽词限时 8 秒，
 * 超时改用作业知识点等本地关键词；各关键词并行搜索、搜到一个出一批。直链过期时播放层会按标题重新取。
 */

internal object BiliRecommend {
    private val videoCache = mutableMapOf<String, List<BilibiliVideoInfo>>()
    private val shownForPoint = mutableMapOf<String, Set<String>>()
    private var recCache: Pair<Long, List<Pair<String, List<BilibiliVideoInfo>>>>? = null
    private fun nowMs() = kotlin.time.Clock.System.now().toEpochMilliseconds()

    /** 让 AI 从 [context] 提炼最多 [n] 个 B 站搜索关键词（每个 2–12 字）；AI 不可用时返回空。 */
    suspend fun extractKeywords(context: String, n: Int): List<String> {
        val r = AiClient.chat(
            system = "你是学习视频检索助手。根据给出的学生学习情况，提炼最值得补习的 $n 个知识点，作为在 B 站搜索教学视频的关键词。" +
                "每个关键词 2～12 个字，是具体的知识点或方法名称（如「矩阵的秩」「定积分换元法」），不要写课程名、作业标题或泛泛的词。" +
                "每行一个关键词，只输出关键词本身，不要编号、不要解释。",
            user = context,
            maxTokens = 256,
        ) ?: return emptyList()
        return r.lines()
            .map { it.trim().trimStart('-', '·', '•', '*', ' ').replace(Regex("^\\d+[.、)]\\s*"), "").trim('「', '」', '"', ' ') }
            .filter { it.length in 2..16 }
            .distinct()
            .take(n)
    }

    /** 搜某个关键词的视频（会话内缓存；失败 / 无结果返回空）。 */
    suspend fun videos(keyword: String, count: Int = 4): List<BilibiliVideoInfo> {
        videoCache[keyword]?.let { return it.take(count) }
        val list = KewuApi.getBilibiliVideos(keyword).orEmpty()
        if (list.isNotEmpty()) videoCache[keyword] = list
        return list.take(count)
    }

    /** 学生画像文本：最近向小悟提的问题 + 作业（标题 / 知识点）+ 错题题干。 */
    fun studentContext(): String = buildString {
        val asked = ChatHistory.loadAll(ChatHistory.KEY_STUDENT)
            .filter { it.role == "user" && it.text.isNotBlank() }
            .takeLast(15).map { it.text.take(80) }
        appendLine("学生最近向 AI 提的问题：" + asked.joinToString("；").ifBlank { "无" })
        val items = AssignmentRepo.items.value.orEmpty()
        appendLine(
            "老师布置的作业：" + items.take(10).joinToString("；") { i ->
                val kp = i.assignment.knowledgePoints.joinToString("、")
                "《${i.assignment.title}》" + (if (kp.isNotBlank()) "（知识点：$kp）" else "") + (if (i.pending) "未完成" else "")
            }.ifBlank { "无" },
        )
        val wrong = collectMistakes(items).take(6).map { it.question.stem.take(60) }
        appendLine("最近做错的题：" + wrong.joinToString("；").ifBlank { "无" })
    }

    /** 不靠 AI 的兜底关键词：作业知识点 → 作业标题 → 通用学科。 */
    private fun localKeywords(n: Int): List<String> {
        val items = AssignmentRepo.items.value.orEmpty()
        val kp = items.flatMap { it.assignment.knowledgePoints }.map { it.trim() }.filter { it.length in 2..16 }
        val titles = items.map { it.assignment.title.replace(Regex("[《》【】\\[\\]()（）]"), " ").trim() }.filter { it.length in 2..16 }
        return (kp + titles + listOf("高等数学 重点", "线性代数 入门", "大学物理 基础")).distinct().take(n)
    }

    private fun recDiskKey() = "kewu_bili_rec_" + KewuApi.username.orEmpty()
    private val json = Json { ignoreUnknownKeys = true }

    /** 上次推荐（落盘）：进页先秒出，后台再按需刷新。 */
    fun diskRecommendations(): List<Pair<String, List<BilibiliVideoInfo>>> = runCatching {
        (json.parseToJsonElement(kvGet(recDiskKey()) ?: return emptyList()) as JsonArray).mapNotNull { g ->
            val o = g as? JsonObject ?: return@mapNotNull null
            val kw = (o["k"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val vs = (o["v"] as? JsonArray).orEmpty().mapNotNull { e ->
                val v = e as? JsonObject ?: return@mapNotNull null
                fun f(k: String) = (v[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
                BilibiliVideoInfo(f("b") ?: return@mapNotNull null, f("t").orEmpty(), f("c").orEmpty(), f("vu"), f("au"))
            }
            if (vs.isEmpty()) null else kw to vs
        }
    }.getOrDefault(emptyList())

    private fun saveDisk(list: List<Pair<String, List<BilibiliVideoInfo>>>) {
        kvSet(recDiskKey(), buildJsonArray {
            list.forEach { (kw, vs) ->
                add(buildJsonObject {
                    put("k", kw)
                    put("v", buildJsonArray {
                        vs.forEach { v ->
                            add(buildJsonObject { put("b", v.bvid); put("t", v.title); put("c", v.cover); put("vu", v.videoUrl); put("au", v.audioUrl) })
                        }
                    })
                })
            }
        }.toString())
    }

    /**
     * 「为你推荐」：关键词 × 每个关键词 3 条视频，**逐批回调** [onUpdate]（搜到一个关键词就刷新一次）。
     * 30 分钟内复用内存结果；AI 抽词限时 8 秒，超时用 [localKeywords]。
     */
    suspend fun recommendations(
        force: Boolean = false,
        onUpdate: (List<Pair<String, List<BilibiliVideoInfo>>>) -> Unit = {},
    ): List<Pair<String, List<BilibiliVideoInfo>>> {
        recCache?.let { (ts, list) -> if (!force && nowMs() - ts < 30 * 60_000L && list.isNotEmpty()) return list }
        withTimeoutOrNull(3_000) { AssignmentRepo.refresh(maxAgeMs = 5 * 60_000L) }
        val kws = withTimeoutOrNull(8_000) { extractKeywords(studentContext(), 4) }?.takeIf { it.isNotEmpty() } ?: localKeywords(4)
        val mutex = Mutex()
        val acc = mutableListOf<Pair<String, List<BilibiliVideoInfo>>>()
        coroutineScope {
            kws.forEach { kw ->
                launch {
                    val vs = videos(kw, 3)
                    if (vs.isNotEmpty()) mutex.withLock {
                        acc += kw to vs
                        // 按关键词原顺序展示，避免先到先排导致列表跳动
                        onUpdate(acc.sortedBy { kws.indexOf(it.first) })
                    }
                }
            }
        }
        val result = acc.sortedBy { kws.indexOf(it.first) }
        if (result.isNotEmpty()) { recCache = nowMs() to result; saveDisk(result) }
        return result
    }

    /** 某个知识点的视频：AI 先把知识点（可能是作业标题）转成 1–2 个搜索词，再合并去重。关键词按账号落盘。 */
    suspend fun forKnowledgePoint(point: String, course: String = "", refresh: Boolean = false): List<BilibiliVideoInfo> {
        val key = "kewu_kp_kw_" + KewuApi.username.orEmpty() + "_" + point
        val kws = kvGet(key)?.takeIf { !refresh }?.split('|')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            ?: extractKeywords("课程：${course.ifBlank { "未知" }}\n薄弱知识点：$point\n请给出 2 个能搜到讲解这个知识点的教学视频的关键词。", 2)
                .ifEmpty { listOf(point.take(16)) }
                .also { kvSet(key, it.joinToString("|")) }
        val all = coroutineScope { kws.map { kw -> async { videos(kw, 8) } }.awaitAll() }.flatten().distinctBy { it.bvid }
        // 换一批：跳过上一批已经展示过的
        val shown = shownForPoint[point].orEmpty()
        val pick = all.filter { it.bvid !in shown }.ifEmpty { all }.take(6)
        shownForPoint[point] = if (refresh) shown + pick.map { it.bvid } else pick.map { it.bvid }.toSet()
        return pick
    }
}

// ───────────────────────── 掌握度自评 ─────────────────────────

/** 学生自评掌握度（按账号落盘）：知识点 → 百分比（1–100，只升不降）；以及达到 100% 的记录。 */
internal object MasteryStore {
    data class Record(val point: String, val fromPercent: Int, val masteredAt: Long)

    private val json = Json { ignoreUnknownKeys = true }
    private fun rateKey() = "kewu_mastery_self_" + KewuApi.username.orEmpty()
    private fun histKey() = "kewu_mastery_hist_" + KewuApi.username.orEmpty()

    fun selfRates(): Map<String, Int> = runCatching {
        (json.parseToJsonElement(kvGet(rateKey()) ?: return emptyMap()) as JsonObject)
            .mapValues { (_, v) -> v.jsonPrimitive.intOrNull ?: 0 }
    }.getOrDefault(emptyMap())

    fun history(): List<Record> = runCatching {
        (json.parseToJsonElement(kvGet(histKey()) ?: return emptyList()) as JsonArray).mapNotNull {
            val o = it as? JsonObject ?: return@mapNotNull null
            Record(
                (o["p"] as? JsonPrimitive)?.content ?: return@mapNotNull null,
                (o["f"] as? JsonPrimitive)?.intOrNull ?: 0,
                (o["t"] as? JsonPrimitive)?.longOrNull ?: 0L,
            )
        }
    }.getOrDefault(emptyList())

    /** 记录自评；低于已记录值不生效（只升不降）。达到 100% 时写入「曾经薄弱」记录。 */
    fun setRate(point: String, percent: Int, computedPercent: Int) {
        val all = selfRates().toMutableMap()
        val old = all[point] ?: 0
        val v = percent.coerceIn(1, 100)
        if (v <= old) return
        all[point] = v
        kvSet(rateKey(), buildJsonObject { all.forEach { (k, n) -> put(k, n) } }.toString())
        if (v == 100 && history().none { it.point == point }) {
            val h = history() + Record(point, maxOf(old, computedPercent), kotlin.time.Clock.System.now().toEpochMilliseconds())
            kvSet(histKey(), buildJsonArray {
                h.forEach { r -> add(buildJsonObject { put("p", r.point); put("f", r.fromPercent); put("t", r.masteredAt) }) }
            }.toString())
        }
    }
}

// ───────────────────────── 「为你推荐」区块 ─────────────────────────

/**
 * B 站学习视频页的「推荐」列表：先显示上次结果（落盘），再后台刷新、逐批替换。
 * 结合最近提问 / 作业 / 错题，AI 提炼关键词后搜索。
 */
@Composable
internal fun BiliRecommendSection(modifier: Modifier = Modifier) {
    val cs = COUITheme.colorScheme
    var recs by remember { mutableStateOf<List<Pair<String, List<BilibiliVideoInfo>>>?>(BiliRecommend.diskRecommendations().ifEmpty { null }) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) {
        loading = true
        if (reload > 0) recs = null
        // 没有旧结果就逐批出；有上次的结果先留着，新结果全部到齐再整体替换（免得列表先缩后涨）
        val progressive = recs == null
        val r = BiliRecommend.recommendations(force = reload > 0) { if (progressive) recs = it }
        if (r.isNotEmpty() || recs == null) recs = r
        loading = false
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val list = recs
        when {
            list == null -> Text("正在挑选视频…", color = cs.onSurfaceSecondary, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
            list.isEmpty() -> Text("暂无推荐，试试上方搜索", color = cs.onSurfaceSecondary,
                fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
            else -> list.forEach { (kw, videos) ->
                androidx.compose.runtime.key(kw) {
                    Text("# $kw", color = Color(0xFFFB7299), fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                    videos.forEachIndexed { i, v -> BilibiliVideoCard(v, index = i) }
                }
            }
        }
        if (recs != null && !loading) {
            KewuSecondaryButton("换一批推荐", { haptic(HapticKind.Tick); reload++ }, Modifier.fillMaxWidth())
        }
    }
}

// ───────────────────────── 知识点视频弹窗 ─────────────────────────

/**
 * 点知识点弹出的 coui 弹窗（OverlayDialog 自带缩放淡入动画）：当前掌握度 + AI 搜到的 B 站视频。
 * 点视频 → [onPlay]（调用方关弹窗、在页面根部播放，看完再问掌握度）。
 */
@Composable
internal fun KnowledgeVideoDialog(
    show: Boolean,
    point: String,
    percent: Int,
    mastered: Boolean,
    course: String,
    onPlay: (BilibiliVideoInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    var videos by remember(point) { mutableStateOf<List<BilibiliVideoInfo>?>(null) }
    var refreshSeq by remember(point) { mutableStateOf(0) }
    LaunchedEffect(point, show, refreshSeq) {
        if (show && point.isNotBlank() && videos == null) videos = BiliRecommend.forKnowledgePoint(point, course, refresh = refreshSeq > 0)
    }
    OverlayDialog(
        show = show,
        title = point.ifBlank { "知识点" },
        summary = if (mastered) "已掌握" else "掌握度 $percent%",
        onDismissRequest = onDismiss,
        onDismissFinished = { },
    ) {
        Column(Modifier.fillMaxWidth()) {
        Column(
            // 2026-11-02：coui 弹窗内容区不自带左右边距，进度条 / 提示语 / 卡片统一留 24dp
            Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MasteryBar(percent / 100f, mastered)
            Spacer(Modifier.height(4.dp))
            val list = videos
            when {
                list == null -> Text("正在搜索视频…", color = cs.onSurfaceSecondary, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 16.dp))
                list.isEmpty() -> Text("暂时没搜到相关视频", color = cs.onSurfaceSecondary, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 16.dp))
                else -> list.forEachIndexed { i, v -> BilibiliVideoCard(v, onClick = { onPlay(v) }, index = i) }
            }
        }
        // 2026-11-02：底部按钮改用 coui 弹窗原生按钮栏（与确认弹窗同款间距），旧版整宽按钮贴底没有留白
        DialogButtonBar(
            negative = DialogButtonBarAction("关闭") { haptic(HapticKind.Tick); onDismiss() },
            positive = DialogButtonBarAction("换一批") {
                haptic(HapticKind.Tick)
                videos = null
                refreshSeq++
            },
            hasContentAbove = true,
        )
        }
    }
}

/** 掌握度进度条：100%（已掌握）为绿色。 */
@Composable
internal fun MasteryBar(fraction: Float, mastered: Boolean, modifier: Modifier = Modifier) {
    val cs = COUITheme.colorScheme
    val color = when {
        mastered || fraction >= 0.995f -> KewuGreen
        fraction >= 0.6f -> com.kewu.app.KewuBlue
        else -> Color(0xFFE5484D)
    }
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.onSurface.copy(alpha = 0.08f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0.03f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
    }
}

/**
 * 看完视频后的掌握度自评（coui 弹窗 + Slider）：范围 [currentPercent]–100%，只能升不能降；100% 算掌握（进度条变绿）。
 */
@Composable
internal fun MasteryRateDialog(
    show: Boolean,
    point: String,
    currentPercent: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    val min = currentPercent.coerceIn(1, 100)
    var frac by remember(point, show) { mutableFloatStateOf(0f) }
    val value = (min + (100 - min) * frac).toInt().coerceIn(min, 100)
    KewuConfirmDialog(
        show = show,
        title = "现在掌握了多少？",
        summary = "「$point」· 只能往上调",
        confirmText = "记录 $value%",
        cancelText = "稍后再说",
        onConfirm = { onConfirm(value) },
        onDismiss = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("$value%", color = if (value == 100) KewuGreen else cs.onSurface, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Slider(value = frac, onValueChange = { frac = it }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("$min%", color = cs.onSurfaceSecondary, fontSize = 11.5.sp)
                Text("100%", color = cs.onSurfaceSecondary, fontSize = 11.5.sp)
            }
        }
    }
}
