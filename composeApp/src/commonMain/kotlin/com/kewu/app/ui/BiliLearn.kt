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
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * 视频播放直链带过期参数，所以视频列表只放内存（会话内复用），关键词可以落盘。
 */

internal object BiliRecommend {
    private val videoCache = mutableMapOf<String, List<BilibiliVideoInfo>>()
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

    /** 「为你推荐」：AI 关键词 × 每个关键词 3 条视频。30 分钟内复用（直链有过期时间，不落盘）。 */
    suspend fun recommendations(force: Boolean = false): List<Pair<String, List<BilibiliVideoInfo>>> {
        recCache?.let { (ts, list) -> if (!force && nowMs() - ts < 30 * 60_000L && list.isNotEmpty()) return list }
        AssignmentRepo.refresh(maxAgeMs = 5 * 60_000L)
        val kws = extractKeywords(studentContext(), 4)
        val result = coroutineScope {
            kws.map { kw -> async { kw to videos(kw, 3) } }.awaitAll()
        }.filter { it.second.isNotEmpty() }
        if (result.isNotEmpty()) recCache = nowMs() to result
        return result
    }

    /** 某个知识点的视频：AI 先把知识点（可能是作业标题）转成 1–2 个搜索词，再合并去重。关键词按账号落盘。 */
    suspend fun forKnowledgePoint(point: String, course: String = ""): List<BilibiliVideoInfo> {
        val key = "kewu_kp_kw_" + KewuApi.username.orEmpty() + "_" + point
        val kws = kvGet(key)?.split('|')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            ?: extractKeywords("课程：${course.ifBlank { "未知" }}\n薄弱知识点：$point\n请给出 2 个能搜到讲解这个知识点的教学视频的关键词。", 2)
                .ifEmpty { listOf(point.take(16)) }
                .also { kvSet(key, it.joinToString("|")) }
        return coroutineScope { kws.map { kw -> async { videos(kw, 4) } }.awaitAll() }
            .flatten().distinctBy { it.bvid }.take(6)
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

/** B 站学习视频页顶部的「为你推荐」：结合最近提问 / 作业 / 错题，AI 提炼关键词后搜索。 */
@Composable
internal fun BiliRecommendSection(modifier: Modifier = Modifier) {
    val cs = COUITheme.colorScheme
    var recs by remember { mutableStateOf<List<Pair<String, List<BilibiliVideoInfo>>>?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) {
        recs = null
        recs = BiliRecommend.recommendations(force = reload > 0)
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("为你推荐", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("小悟根据你最近问的问题、老师布置的作业和错题挑的", color = cs.onSurfaceSecondary, fontSize = 11.5.sp)
        }
        val list = recs
        when {
            list == null -> Text("小悟正在分析你的学习情况并搜索视频，可能需要十几秒…", color = cs.onSurfaceSecondary, fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 12.dp))
            list.isEmpty() -> Text("暂时没有可推荐的视频：多问问小悟、做几份作业后再来看看，也可以直接在上面搜索", color = cs.onSurfaceSecondary,
                fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(vertical = 12.dp))
            else -> list.forEach { (kw, videos) ->
                Text("# $kw", color = Color(0xFFFB7299), fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                videos.forEach { BilibiliVideoCard(it) }
            }
        }
        if (recs != null) {
            KewuSecondaryButton("换一批推荐", { reload++ }, Modifier.fillMaxWidth())
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
    LaunchedEffect(point, show) {
        if (show && point.isNotBlank() && videos == null) videos = BiliRecommend.forKnowledgePoint(point, course)
    }
    OverlayDialog(
        show = show,
        title = point.ifBlank { "知识点" },
        summary = if (mastered) "已掌握 · 可以随时回看相关视频" else "当前掌握度 $percent% · 看完视频后告诉小悟你掌握了多少",
        onDismissRequest = onDismiss,
        onDismissFinished = { },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MasteryBar(percent / 100f, mastered)
            Spacer(Modifier.height(4.dp))
            val list = videos
            when {
                list == null -> Text("小悟正在提炼关键词并搜索 B 站视频…", color = cs.onSurfaceSecondary, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 16.dp))
                list.isEmpty() -> Text("暂时没搜到相关视频，稍后再试试", color = cs.onSurfaceSecondary, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 16.dp))
                else -> list.forEach { v -> BilibiliVideoCard(v, onClick = { onPlay(v) }) }
            }
            Spacer(Modifier.height(4.dp))
            KewuSecondaryButton("关闭", onDismiss, Modifier.fillMaxWidth())
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
 * 看完视频后的掌握度自评（coui 弹窗 + Slider）：范围 [currentPercent]–100%，只能升不能降。
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
        title = "看完了，现在掌握了多少？",
        summary = "「$point」原来是 $min%，只能往上调；调到 100% 就算掌握，进度条会变绿",
        confirmText = "记录 $value%",
        cancelText = "稍后再说",
        onConfirm = { onConfirm(value) },
        onDismiss = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
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
