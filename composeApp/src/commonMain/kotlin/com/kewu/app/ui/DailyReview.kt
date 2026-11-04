package com.kewu.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.AiClient
import com.kewu.app.ClassRecordingInfo
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuBlue
import com.kewu.app.KewuGreen
import com.kewu.app.Notifications
import com.kewu.app.ROLE_TEACHER
import com.kewu.app.currentRole
import com.kewu.app.haptic
import com.kewu.app.kvGet
import com.kewu.app.kvSet
import com.kewu.app.todayCn
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * 每日课堂复习（2026-11-03 用户：三个 AI 工具不要做成入口，学生不会自己点；要提前生成好，进纪要就自动开始）。
 *
 * - 学生每天第一次打开某节课的课堂纪要，自动弹出「今日复习」，依次：① 课后自测 5 题 → ② 记忆卡片 → ③ 讲给小悟听（语音）。
 *   当天再进不再自动弹；纪要页的「今日复习」卡可手动再来一遍 / 换一套。教师端纪要不出现。
 * - 内容提前生成：[NoticeCenter] 每轮轮询拿到近 14 天已出纪要的课（最多 6 节），后台各生成当天的一套（一次 AI 调用
 *   出齐三步内容），所以学生点进来通常不用等；新纪要一出（≤1 分钟被轮询到）就开始准备第一套。
 * - 每天不一样：第 N 次复习按阶段换侧重（框架 → 理解辨析 → 应用 → 综合迁移），并把之前出过的题 / 卡片发给 AI 要求避开，
 *   把学生答错 / 没记住 / 讲得差的知识点作为薄弱点优先考。
 *
 * 存本地 kv（按账号 + 节次）：当天这套 `kewu_lab_daily_*`、历史 `kewu_lab_dhist_*`、当天是否已自动弹过 `kewu_lab_dshown_*`。
 */
internal object DailyReview {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Semaphore(2)
    private val lock = Mutex()
    private val failedAt = mutableMapOf<String, Long>()
    private val _working = MutableStateFlow<Set<String>>(emptySet())
    /** 正在生成的节次。 */
    val working: StateFlow<Set<String>> = _working
    private val _version = MutableStateFlow(0)
    /** 任一套内容 / 进度变化时 +1，界面据此重读。 */
    val version: StateFlow<Int> = _version

    private val user get() = KewuApi.username.orEmpty()
    private fun packKey(sid: String) = "kewu_lab_daily_${user}_$sid"
    private fun histKey(sid: String) = "kewu_lab_dhist_${user}_$sid"
    private fun shownKey(sid: String) = "kewu_lab_dshown_${user}_$sid"
    fun today(): String = todayCn()

    /** 一天的复习内容 + 进度。step：0 自测 / 1 卡片 / 2 讲解 / 3 完成。 */
    data class Pack(
        val day: String, val n: Int, val quizRaw: String,
        val cards: List<Triple<String, String, String>>, val point: String, val lead: String,
        val step: Int, val quizScore: String, val cardsDone: Int, val feynman: Int,
    ) {
        val questions: List<QuizQ> get() = parseQuiz(quizRaw)
    }

    private fun obj(raw: String?): JsonObject? = runCatching { labJson.parseToJsonElement(raw ?: return null) as? JsonObject }.getOrNull()
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content?.trim().orEmpty()
    private fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
    private fun JsonObject.strs(k: String) = (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }

    fun pack(sid: String): Pack? {
        val o = obj(kvGet(packKey(sid))) ?: return null
        val cards = (o["cards"] as? JsonArray).orEmpty().mapNotNull { e ->
            val c = e as? JsonObject ?: return@mapNotNull null
            Triple(c.s("front"), c.s("back"), c.s("point")).takeIf { it.first.isNotBlank() && it.second.isNotBlank() }
        }
        return Pack(
            o.s("day"), o.i("n") ?: 1, buildJsonObject { put("q", o["q"] ?: JsonArray(emptyList())) }.toString(),
            cards, o.s("fp"), o.s("fa"), o.i("step") ?: 0, o.s("qs"), o.i("cd") ?: 0, o.i("fs") ?: -1,
        ).takeIf { it.day.isNotBlank() }
    }

    fun todayPack(sid: String): Pack? = pack(sid)?.takeIf { it.day == today() }

    /** 更新当天进度（只改给出的字段）。 */
    fun saveProgress(sid: String, step: Int? = null, quizScore: String? = null, cardsDone: Int? = null, feynman: Int? = null) {
        val o = obj(kvGet(packKey(sid))) ?: return
        kvSet(packKey(sid), JsonObject(o.toMutableMap().apply {
            step?.let { put("step", JsonPrimitive(it)) }
            quizScore?.let { put("qs", JsonPrimitive(it)) }
            cardsDone?.let { put("cd", JsonPrimitive(it)) }
            feynman?.let { put("fs", JsonPrimitive(it)) }
        }).toString())
        _version.update { it + 1 }
    }

    /** 记薄弱点（答错 / 没记住 / 讲得差），下一套优先考。 */
    fun addWeak(sid: String, points: List<String>) {
        val p = points.map { it.trim() }.filter { it.isNotEmpty() }
        if (p.isEmpty()) return
        val h = obj(kvGet(histKey(sid)))
        val weak = (h?.strs("weak").orEmpty() + p).distinct().takeLast(12)
        kvSet(histKey(sid), buildJsonObject {
            put("seen", h?.get("seen") ?: JsonArray(emptyList()))
            put("weak", buildJsonArray { weak.forEach { add(JsonPrimitive(it)) } })
        }.toString())
    }

    /** 今天还没自动弹过。 */
    fun shouldAutoShow(sid: String): Boolean = kvGet(shownKey(sid)) != today()
    fun markShown(sid: String) = kvSet(shownKey(sid), today())

    /** 确保这节课今天的复习内容已生成（后台进行，不随页面取消）。[force] = 换一套。 */
    fun ensure(sid: String, courseName: String, date: String, summary: String, transcript: String, force: Boolean = false) {
        if (summary.isBlank() && transcript.isBlank()) return
        if (!force && todayPack(sid) != null) return
        if (sid in _working.value) return
        _working.update { it + sid }
        scope.launch {
            try {
                val ok = gate.withPermit { generate(sid, courseName, date, summary, transcript, force) }
                lock.withLock { if (ok) failedAt.remove(sid) else failedAt[sid] = Notifications.nowMs() }
            } finally {
                _working.update { it - sid }
                _version.update { it + 1 }
            }
        }
    }

    /** 后台预生成：近 14 天已出纪要的课，最多 6 节；10 分钟内失败过的先不重试。学生端由 NoticeCenter 每轮调用。 */
    fun prefetch(notes: List<Pair<String, ClassRecordingInfo>>) {
        if (currentRole() == ROLE_TEACHER || KewuApi.username.isNullOrBlank()) return
        scope.launch {
            val now = Notifications.nowMs()
            val todayTs = Notifications.dateTs(today()) ?: return@launch
            val picks = notes
                .filter { (_, r) -> r.transcriptStatus == "DONE" && (r.summary.isNotBlank() || r.transcript.isNotBlank()) }
                .filter { (_, r) -> Notifications.dateTs(r.date)?.let { todayTs - it in 0..14 * 86_400_000L } == true }
                .sortedByDescending { it.second.decidedAt }
                .distinctBy { it.second.sessionId }
                .take(6)
            val skip = lock.withLock { failedAt.filterValues { now - it < 10 * 60_000L }.keys.toSet() }
            picks.filter { it.second.sessionId !in skip }.forEach { (name, r) ->
                ensure(r.sessionId, name, r.date, r.summary, r.transcript)
            }
        }
    }

    private fun stage(n: Int): String = when (n) {
        1 -> "这是第 1 次复习：覆盖本节最核心的概念和结论，由浅入深，先帮学生把整节课的框架搭起来。"
        2 -> "这是第 2 次复习：换一个角度，侧重「为什么」、成立条件和易错辨析，不能和之前的题目雷同。"
        3 -> "这是第 3 次复习：侧重应用，给具体情境或小例子让学生判断 / 计算；卡片侧重易混概念对比。"
        else -> "这是第 $n 次复习：综合与迁移，把本节知识和前后内容联系起来，难度略高于前几次，同时回顾薄弱点。"
    }

    private suspend fun generate(sid: String, courseName: String, date: String, summary: String, transcript: String, force: Boolean): Boolean {
        val day = today()
        if (!force && todayPack(sid) != null) return true
        val old = pack(sid)
        val n = when { old == null -> 1; old.day == day -> old.n; else -> old.n + 1 }
        val h = obj(kvGet(histKey(sid)))
        // 之前出过的题干 / 卡片正面：要求 AI 避开
        val seen = (h?.strs("seen").orEmpty() + old?.questions.orEmpty().map { it.stem } + old?.cards.orEmpty().map { it.first })
            .distinct().takeLast(40)
        val weak = h?.strs("weak").orEmpty()
        val course = courseName.ifBlank { "课程" }
        val system = "你是课悟的课后复习教练小悟。给学生准备《$course》$date 这节课的第 $n 次复习，每天一次，三步：自测 → 记忆卡片 → 讲给小悟听。" +
            stage(n) +
            "要求：① q：5 道单项选择题，每题考一个本节课真正讲到的知识点；4 个选项只有一个正确，干扰项要像学生常见的误解；" +
            "explain 用 1–2 句话讲清为什么；point 为知识点（2–10 字标准术语）；quote 为转写里能支撑答案的老师原话（≤40 字，没有就给空串，不得编造）；answer 为正确选项下标 0–3。" +
            "② cards：6 张问答式记忆卡片，front 是具体问题（≤40 字），back 是简洁准确的答案（≤80 字，可带一个关键例子），point 为知识点，和 q 考的点尽量错开。" +
            "③ feynman：挑 1 个最值得学生用自己的话讲一遍的知识点 point（2–10 字），lead 是一句引导（≤40 字，提示从哪几个方面讲）。" +
            "只用本节课讲到的内容。" +
            (if (seen.isNotEmpty()) "今天的内容必须和之前不一样：不要重复【已经出过的】题目和卡片，换知识点或换角度。" else "") +
            (if (weak.isNotEmpty()) "优先覆盖【学生薄弱点】。" else "") +
            "只输出一个 JSON 对象，不要任何其他文字、不要 markdown 代码块：" +
            "{\"q\":[{\"stem\":\"\",\"options\":[\"\",\"\",\"\",\"\"],\"answer\":0,\"explain\":\"\",\"point\":\"\",\"quote\":\"\"}]," +
            "\"cards\":[{\"front\":\"\",\"back\":\"\",\"point\":\"\"}],\"feynman\":{\"point\":\"\",\"lead\":\"\"}}"
        val userMsg = buildString {
            append(lessonMaterial(summary, transcript, budget = 14_000))
            if (seen.isNotEmpty()) append("\n【已经出过的】\n").append(seen.joinToString("\n") { "- " + it.take(60) })
            if (weak.isNotEmpty()) append("\n【学生薄弱点】\n").append(weak.joinToString("、"))
        }
        val raw = AiClient.chat(system, userMsg, maxTokens = 5_000)
        val o = aiJsonObject(raw) ?: return false
        val qs = parseQuiz(o.toString())
        val cards = (o["cards"] as? JsonArray).orEmpty().mapNotNull { e ->
            val c = e as? JsonObject ?: return@mapNotNull null
            Triple(c.s("front"), c.s("back"), c.s("point").take(16)).takeIf { it.first.isNotBlank() && it.second.isNotBlank() }
        }.take(8)
        val fey = o["feynman"] as? JsonObject
        val point = fey?.s("point")?.take(16)?.ifBlank { null } ?: qs.firstOrNull()?.point?.ifBlank { null } ?: return false
        if (qs.size < 3 || cards.size < 3) return false
        kvSet(packKey(sid), buildJsonObject {
            put("day", day); put("n", n)
            put("q", o["q"] ?: JsonArray(emptyList()))
            put("cards", buildJsonArray {
                cards.forEach { (f, b, p) -> add(buildJsonObject { put("front", f); put("back", b); put("point", p) }) }
            })
            put("fp", point); put("fa", fey?.s("lead").orEmpty().take(60))
            put("step", 0); put("qs", ""); put("cd", 0); put("fs", -1)
        }.toString())
        kvSet(histKey(sid), buildJsonObject {
            put("seen", buildJsonArray { seen.forEach { add(JsonPrimitive(it)) } })
            put("weak", buildJsonArray { weak.forEach { add(JsonPrimitive(it)) } })
        }.toString())
        // 当天的卡片并入这节课的卡组（之后按遗忘曲线继续出现在「今日复习」里）
        StudyLabStore.putDeck(sid, "$course $date".trim(), cards, append = true)
        return true
    }
}

// ───────────────────────── 纪要页的「今日复习」卡 ─────────────────────────

/** 课堂纪要页（学生）里的一张卡：今天这套的状态 + 一键开始 / 再来一遍 / 换一套。 */
@Composable
internal fun DailyReviewCard(
    rec: ClassRecordingInfo,
    courseName: String,
    onOpen: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    val working by DailyReview.working.collectAsState()
    val version by DailyReview.version.collectAsState()
    val sid = rec.sessionId
    val pack = remember(sid, version) { DailyReview.todayPack(sid) }
    val busy = sid in working
    val p = rememberEnterProgress(1, baseDelayMs = 160L)
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).riseEnter(p, 24f),
        cornerRadius = 18.dp,
        insideMargin = PaddingValues(16.dp),
        colors = kewuCardColors(),
        onClick = if (pack != null) ({ haptic(HapticKind.Light); onOpen() }) else null,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("今日复习", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                when {
                    pack == null -> Unit
                    pack.step >= 3 -> KewuTag("今天已完成", KewuGreen.copy(alpha = 0.14f), KewuGreen)
                    else -> KewuTag("第 ${pack.n} 次复习", KewuBlue.copy(alpha = 0.14f), KewuBlue)
                }
            }
            Spacer(Modifier.height(10.dp))
            StepRow(pack?.step ?: -1)
            Spacer(Modifier.height(10.dp))
            when {
                busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                    InfiniteProgressIndicator(size = 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("小悟正在准备今天的复习内容…", color = cs.onSurfaceSecondary, fontSize = 12.5.sp)
                }
                pack == null -> {
                    Text("复习内容还没准备好", color = cs.onSurfaceSecondary, fontSize = 12.5.sp)
                    Spacer(Modifier.height(10.dp))
                    KewuPrimaryButton("现在准备", {
                        DailyReview.ensure(sid, courseName, rec.date, rec.summary, rec.transcript)
                    }, Modifier.fillMaxWidth())
                }
                else -> {
                    Text(
                        if (pack.step >= 3) "明天小悟会换一套新的内容" else "自测 ${pack.questions.size} 题 · 卡片 ${pack.cards.size} 张 · 讲解「${pack.point}」",
                        color = cs.onSurfaceSecondary, fontSize = 12.5.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        KewuPrimaryButton(
                            when (pack.step) { 0 -> "开始复习"; in 1..2 -> "继续复习"; else -> "再复习一遍" },
                            {
                                if (pack.step >= 3) DailyReview.saveProgress(sid, step = 0)
                                onOpen()
                            },
                            Modifier.weight(1f),
                        )
                        KewuSecondaryButton("换一套", {
                            DailyReview.ensure(sid, courseName, rec.date, rec.summary, rec.transcript, force = true)
                        }, Modifier.weight(0.6f))
                    }
                }
            }
        }
    }
}

/** 三步进度：自测 → 卡片 → 讲解。[step] < 0 = 未就绪（全灰）。 */
@Composable
private fun StepRow(step: Int) {
    val cs = COUITheme.colorScheme
    val names = listOf("自测", "记忆卡片", "讲给小悟听")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        names.forEachIndexed { i, name ->
            val state = when { step < 0 -> 0; i < step -> 2; i == step -> 1; else -> 0 }
            val bg by animateColorAsState(
                when (state) { 2 -> KewuGreen; 1 -> KewuBlue; else -> cs.dividerLine }, tween(260), label = "stepBg",
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                    Text(if (state == 2) "✓" else "${i + 1}", color = if (state == 0) cs.onSurfaceSecondary else Color.White,
                        fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(6.dp))
                Text(name, color = if (state == 1) cs.onSurface else cs.onSurfaceSecondary, fontSize = 12.5.sp,
                    fontWeight = if (state == 1) FontWeight.SemiBold else FontWeight.Normal)
            }
            if (i < names.lastIndex) {
                val line by animateColorAsState(if (state == 2) KewuGreen else cs.dividerLine, tween(260), label = "stepLine")
                Box(Modifier.weight(1f).padding(horizontal = 6.dp).height(2.dp).clip(CircleShape).background(line))
            }
        }
    }
}

// ───────────────────────── 复习流程（全屏） ─────────────────────────

/** 今日复习：自测 → 记忆卡片 → 讲给小悟听 → 完成。进度存本地，中途退出再进从当前步继续。 */
@Composable
internal fun DailyReviewFlow(
    rec: ClassRecordingInfo,
    courseName: String,
    onClose: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    val sid = rec.sessionId
    val working by DailyReview.working.collectAsState()
    val version by DailyReview.version.collectAsState()
    val pack = remember(sid, version) { DailyReview.todayPack(sid) }
    val busy = sid in working
    // 当前步只在换套 / 首次拿到内容时从存储同步，之后由本页推进（避免每次存进度都重建子页）
    var step by remember(sid, pack?.n, pack?.quizRaw) { mutableIntStateOf(pack?.step ?: 0) }
    LaunchedEffect(sid) {
        if (pack == null && !busy) DailyReview.ensure(sid, courseName, rec.date, rec.summary, rec.transcript)
    }
    val title = "$courseName ${rec.date}".trim()

    StudyLabPage("今日复习", onClose, subtitle = pack?.let { "第 ${it.n} 次复习 · $title" } ?: title) { backdrop ->
        when {
            busy -> LabWorking(
                "小悟正在为你准备今天的复习…",
                listOf("自测题只考本节课真正讲过的内容", "每天的复习内容都不一样", "你答错的知识点明天会重点再考", "最后用语音讲一遍，讲清楚才算真懂"),
            )
            pack == null -> LabError("复习内容准备失败了，可能是网络不稳定") {
                DailyReview.ensure(sid, courseName, rec.date, rec.summary, rec.transcript)
            }
            else -> {
                LabGlass(backdrop) { StepRow(step) }
                AnimatedContent(
                    step,
                    transitionSpec = {
                        slideInHorizontally(spring(dampingRatio = 0.86f, stiffness = 360f)) { it } togetherWith
                            slideOutHorizontally(tween(200)) { -it }
                    },
                    label = "reviewStep",
                ) { s ->
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (s) {
                            0 -> QuizStep(sid, title, pack.questions) { score, total ->
                                DailyReview.saveProgress(sid, step = 1, quizScore = "$score/$total")
                                step = 1
                            }
                            1 -> CardsStep(backdrop, sid, pack) { n ->
                                DailyReview.saveProgress(sid, step = 2, cardsDone = n)
                                step = 2
                            }
                            2 -> FeynmanStep(backdrop, sid, rec, pack) {
                                DailyReview.saveProgress(sid, step = 3)
                                step = 3
                            }
                            else -> DoneStep(
                                sid,
                                onAgain = { DailyReview.saveProgress(sid, step = 0); step = 0 },
                                onNew = { DailyReview.ensure(sid, courseName, rec.date, rec.summary, rec.transcript, force = true) },
                                onClose = onClose,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuizStep(sid: String, deckTitle: String, questions: List<QuizQ>, onNext: (Int, Int) -> Unit) {
    val cs = COUITheme.colorScheme
    var index by remember(questions) { mutableIntStateOf(0) }
    var picks by remember(questions) { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var finished by remember(questions) { mutableStateOf(false) }
    if (questions.isEmpty()) {
        LabCard { Text("今天这套没有自测题", color = cs.onSurfaceSecondary, fontSize = 13.sp) }
        KewuPrimaryButton("下一步：记忆卡片", { onNext(0, 0) }, Modifier.fillMaxWidth())
        return
    }
    if (finished) {
        val score = questions.indices.count { picks[it] == questions[it].answer }
        val pct = score * 100 / questions.size
        LabCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                ScoreRing(score.toFloat() / questions.size, "$score/${questions.size}", "答对", scoreColor(pct))
                Spacer(Modifier.height(12.dp))
                Text(
                    when {
                        pct == 100 -> "全对！接下来用记忆卡片巩固一下"
                        pct >= 60 -> "大部分听懂了，错的点已经加进记忆卡片"
                        else -> "还有不少没吃透，错题已经加进记忆卡片，马上再过一遍"
                    },
                    color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                )
            }
        }
        KewuPrimaryButton("下一步：记忆卡片", { haptic(HapticKind.Light); onNext(score, questions.size) }, Modifier.fillMaxWidth())
        return
    }
    val q = questions[index]
    val picked = picks[index]
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
        Text("第 ${index + 1} / ${questions.size} 题", color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        if (q.point.isNotBlank()) KewuTag(q.point, KewuBlue.copy(alpha = 0.14f), KewuBlue)
    }
    LabCard { Text(q.stem, color = cs.onSurface, fontSize = 15.5.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium) }
    q.options.forEachIndexed { k, opt ->
        QuizOption(
            letter = "ABCD"[k].toString(), text = opt,
            state = when {
                picked == null -> 0
                k == q.answer -> 1
                k == picked -> 2
                else -> 3
            },
        ) {
            if (picked == null) {
                haptic(if (k == q.answer) HapticKind.Light else HapticKind.Medium)
                picks = picks + (index to k)
            }
        }
    }
    AnimatedVisibility(picked != null, enter = expandVertically(spring(dampingRatio = 0.9f, stiffness = 300f)) + fadeIn()) {
        LabCard {
            val right = picked == q.answer
            Text(if (right) "答对了" else "正确答案是 ${"ABCD"[q.answer]}",
                color = if (right) KewuGreen else cs.error, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (q.explain.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                MarkdownText(q.explain, color = cs.onSurface, fontSize = 13.5.sp, lineHeight = 20.sp)
            }
            if (q.quote.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                QuoteBlock("老师课上原话", q.quote)
            }
        }
    }
    if (picked != null) {
        KewuPrimaryButton(if (index == questions.lastIndex) "查看结果" else "下一题", {
            haptic(HapticKind.Tick)
            if (index < questions.lastIndex) { index++; return@KewuPrimaryButton }
            val score = questions.indices.count { picks[it] == questions[it].answer }
            StudyLabStore.saveQuizBest(sid, score, questions.size)
            StudyLabStore.addQuizSamples(questions.mapIndexed { k, qq -> qq.point to (if (picks[k] == qq.answer) 1.0 else 0.0) })
            val wrong = questions.withIndex().filter { (i, qq) -> picks[i] != qq.answer }.map { it.value }
            DailyReview.addWeak(sid, wrong.map { it.point })
            // 错题直接变成记忆卡片，下一步就会复习到
            StudyLabStore.putDeck(sid, deckTitle, wrong.map { Triple(it.stem, it.options[it.answer] + "\n" + it.explain, it.point) }, append = true)
            finished = true
        }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun CardsStep(backdrop: LayerBackdrop, sid: String, pack: DailyReview.Pack, onNext: (Int) -> Unit) {
    val cs = COUITheme.colorScheme
    // 今天这套卡片 + 本节到期的旧卡 / 错题卡，最多 12 张
    var queue by remember(pack.quizRaw) {
        mutableStateOf(run {
            val now = Notifications.nowMs()
            val fronts = pack.cards.map { it.first }.toSet()
            val deck = StudyLabStore.cards().filter { it.deck == sid }
            (deck.filter { it.front in fronts } + deck.filter { it.due <= now }).distinctBy { it.id }.take(12)
        })
    }
    var done by remember(pack.quizRaw) { mutableIntStateOf(0) }
    var turn by remember(pack.quizRaw) { mutableIntStateOf(0) }
    val q = queue
    if (q.isNotEmpty()) {
        ReviewSession(backdrop, q.first(), done, done + q.size, turn) { remembered ->
            val c = q.first()
            StudyLabStore.review(c.id, remembered)
            if (!remembered) DailyReview.addWeak(sid, listOf(c.point))
            // 没记住：放回队尾，本轮再见一次
            queue = q.drop(1) + if (remembered) emptyList() else listOf(c)
            if (remembered) done++
            turn++
        }
        return
    }
    LabCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            ScoreRing(1f, "$done", "张已记住", KewuGreen, size = 110)
            Spacer(Modifier.height(12.dp))
            Text("卡片过完了，最后一步：讲给小悟听", color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            // ★ D1：艾宾浩斯遗忘曲线——显示这套卡最近的下次复习时间
            val nextDue = remember(sid, done) {
                StudyLabStore.cards().filter { it.deck == sid }.minOfOrNull { it.due }
            }
            Text(
                (nextDue?.let { "下次复习 " + dueText(it) } ?: "这套卡片已全部牢记") +
                    " · 按艾宾浩斯遗忘曲线 1/2/4/7/15/30/60 天拉开间隔",
                color = cs.onSurfaceSecondary, fontSize = 12.sp,
            )
        }
    }
    KewuPrimaryButton("下一步：讲给小悟听", { haptic(HapticKind.Light); onNext(done) }, Modifier.fillMaxWidth())
}

@Composable
private fun FeynmanStep(backdrop: LayerBackdrop, sid: String, rec: ClassRecordingInfo, pack: DailyReview.Pack, onNext: () -> Unit) {
    val material = remember(rec.sessionId) { lessonMaterial(rec.summary, rec.transcript, budget = 8_000) }
    var best by remember(pack.quizRaw) { mutableIntStateOf(pack.feynman) }
    FeynmanSession(backdrop, pack.point, material, lead = pack.lead) { r ->
        if (r.score > best) best = r.score
        if (r.score < 70) DailyReview.addWeak(sid, listOf(pack.point))
        DailyReview.saveProgress(sid, feynman = best)
    }
    if (best >= 0) {
        KewuPrimaryButton("完成今日复习", { haptic(HapticKind.Light); onNext() }, Modifier.fillMaxWidth())
    } else {
        KewuSecondaryButton("现在不方便说话，先完成", onNext, Modifier.fillMaxWidth())
    }
}

@Composable
private fun DoneStep(sid: String, onAgain: () -> Unit, onNew: () -> Unit, onClose: () -> Unit) {
    val cs = COUITheme.colorScheme
    val pack = remember(sid) { DailyReview.todayPack(sid) }
    // ★ D1：这套卡最近的下次复习时间（艾宾浩斯）
    val nextDue = remember(sid) { StudyLabStore.cards().filter { it.deck == sid }.minOfOrNull { it.due } }
    LabCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("今天的复习完成了", color = cs.onSurface, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("明天再打开这节课，小悟会换一套新内容，重点考你今天错的地方", color = cs.onSurfaceSecondary, fontSize = 12.5.sp)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                LabStat("自测", pack?.quizScore?.ifBlank { null } ?: "—", KewuBlue, Modifier.weight(1f))
                LabStat("卡片", "${pack?.cardsDone ?: 0}", KewuGreen, Modifier.weight(1f))
                LabStat("讲解", pack?.feynman?.takeIf { it >= 0 }?.toString() ?: "—", Color(0xFFF5A623), Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "记忆卡片下次复习 " + (nextDue?.let { dueText(it) } ?: "已全部牢记"),
                color = cs.onSurfaceSecondary, fontSize = 12.sp,
            )
        }
    }
    KewuPrimaryButton("完成", onClose, Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        KewuSecondaryButton("再做一遍", onAgain, Modifier.weight(1f))
        KewuSecondaryButton("换一套新内容", onNew, Modifier.weight(1f))
    }
}
