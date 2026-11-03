package com.kewu.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.time.Clock

/**
 * 通知系统数据层（2026-10-26；2026-11-03 重写）。
 *
 * - 按账号存（`kewu_notifications_<用户名>`），换账号不串。
 * - **永久去重**：通知过的 id 另记在 `kewu_notif_seen_<用户名>`（只增不删，最多 600 条）。旧版只靠列表去重，
 *   列表读失败 / 被覆盖时会把老的「课堂纪要已生成」重新推一遍。
 * - 排序按**事件时间**（课堂纪要用出纪要的时间，批改用批改时间），不是写入时间：同时补进来的几条不会乱序。
 * - 首次在本机 / 本账号运行时为「静默基线」：已有的旧纪要、旧批改只记为已读，不弹不响。
 *
 * ★★★ 本对象是「首页右上角铃铛」的系统通知。AI（小悟）的消息走 [ChatHistory]，红点在底部「助教」上，两套互不相通。
 */
object Notifications {

    // ── 学生端通知类型 ──
    const val TYPE_NEW_ASSIGNMENT = "new_assignment"          // 新作业
    const val TYPE_RECORDING_DONE = "recording_transcribed"   // 录音转写成功（课堂纪要）
    const val TYPE_GRADE_PUBLISHED = "grade_published"        // 作业已批改
    const val TYPE_COURSE_REMINDER = "course_reminder"        // 上课提醒
    const val TYPE_BILI_NOTE = "bili_note"                    // B 站视频 AI 总结完成
    const val TYPE_SYSTEM = "system"                          // 系统通知

    // ── 教师端通知类型 ──
    const val TYPE_NEW_SUBMISSION = "new_submission"          // 收到新提交
    const val TYPE_STUDENT_QUESTION = "student_question"      // 学生提问（助教对话里）
    const val TYPE_ATTENDANCE_DONE = "attendance_done"        // 签到结束汇总

    data class Info(
        val id: String,
        val type: String,
        val title: String,
        val body: String,
        val ts: Long,
        var read: Boolean,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    private fun user() = KewuApi.username.orEmpty()
    private fun listKey() = "kewu_notifications_" + user()
    private fun seenKey() = "kewu_notif_seen_" + user()

    fun load(): List<Info> = try {
        val raw = kvGet(listKey()) ?: return emptyList()
        json.parseToJsonElement(raw).jsonArray.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            Info(
                id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                type = o["type"]?.jsonPrimitive?.content ?: "",
                title = o["title"]?.jsonPrimitive?.content ?: "",
                body = o["body"]?.jsonPrimitive?.content ?: "",
                ts = o["ts"]?.jsonPrimitive?.longOrNull ?: 0L,
                read = o["read"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            )
        }.distinctBy { it.id }.sortedByDescending { it.ts }
    } catch (_: Exception) {
        emptyList()
    }

    private fun save(list: List<Info>) {
        val arr = buildJsonArray {
            list.distinctBy { it.id }.sortedByDescending { it.ts }.take(200).forEach { n ->
                add(buildJsonObject {
                    put("id", n.id)
                    put("type", n.type)
                    put("title", n.title)
                    put("body", n.body)
                    put("ts", n.ts)
                    put("read", n.read)
                })
            }
        }
        kvSet(listKey(), arr.toString())
        refreshUnread()
    }

    /** 本账号通知过的全部 id；null = 从没记录过（首次运行 → 静默基线）。 */
    private fun seen(): MutableSet<String>? = kvGet(seenKey())?.let { raw ->
        runCatching { json.parseToJsonElement(raw).jsonArray.mapNotNull { (it as? JsonPrimitive)?.content }.toMutableSet() }
            .getOrNull()
    }

    private fun saveSeen(set: Collection<String>) {
        kvSet(seenKey(), buildJsonArray { set.toList().takeLast(600).forEach { add(JsonPrimitive(it)) } }.toString())
    }

    private val _unread = MutableStateFlow(0)
    /** 铃铛未读数（全 App 共享，各首页 collect）。 */
    val unread: StateFlow<Int> = _unread.asStateFlow()
    fun refreshUnread() { _unread.value = load().count { !it.read } }

    fun unreadCount(): Int = load().count { !it.read }

    /** 打开通知中心即全部已读（红点消失），同时收起系统通知栏里的课悟通知。 */
    fun markAllRead() {
        val list = load()
        if (list.any { !it.read }) save(list.map { it.copy(read = true) })
        SystemNotifier.cancelAll?.invoke()
        refreshUnread()
    }

    fun markRead(id: String) {
        val list = load()
        val target = list.firstOrNull { it.id == id } ?: return
        if (target.read) return
        save(list.map { if (it.id == id) it.copy(read = true) else it })
    }

    /** 待写入的一条。[silent] = 只记为已读、不提醒（旧事件补录）。 */
    data class Draft(val id: String, val type: String, val title: String, val body: String, val ts: Long, val silent: Boolean = false)

    /**
     * 批量写入（加锁）：已经通知过的 id 一律跳过。返回需要提醒用户的新通知。
     * 首次运行（seen 记录不存在）全部静默。
     */
    suspend fun addAll(drafts: List<Draft>): List<Info> = lock.withLock {
        val seenSet = seen()
        // 没有任何事件：首次运行也要落一份空基线，否则以后第一条真通知会被当成旧事件静默
        if (drafts.isEmpty()) {
            if (seenSet == null) saveSeen(load().map { it.id })
            return@withLock emptyList()
        }
        val baseline = seenSet == null
        val seen = seenSet ?: mutableSetOf()
        val list = load().toMutableList()
        seen += list.map { it.id }
        val fresh = mutableListOf<Info>()
        drafts.distinctBy { it.id }.forEach { d ->
            if (d.id in seen) return@forEach
            seen += d.id
            val quiet = baseline || d.silent
            val info = Info(d.id, d.type, d.title, d.body, d.ts, read = quiet)
            list += info
            if (!quiet) fresh += info
        }
        saveSeen(seen)
        save(list)
        fresh.sortedByDescending { it.ts }
    }

    /** 单条写入（兼容旧调用）：返回是否真的新增并需要提醒。 */
    suspend fun add(id: String, type: String, title: String, body: String): Boolean =
        addAll(listOf(Draft(id, type, title, body, nowMs()))).isNotEmpty()

    internal fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    // ───────────────────────── 各类事件的轮询 ─────────────────────────

    /** 课堂纪要：每门课每天一条（id `notes-<课程>-<日期>`，与通知跳转约定一致）。超过 3 天的旧纪要只补录不提醒。 */
    internal suspend fun pollClassNotes(): List<Draft> {
        val now = nowMs()
        val out = mutableListOf<Draft>()
        val ready = mutableListOf<Pair<String, ClassRecordingInfo>>()
        for (c in KewuApi.myCourses().orEmpty()) {
            val recs = KewuApi.getClassRecordings(c.id).orEmpty()
                .filter { it.transcriptStatus == "DONE" && (it.summary.isNotBlank() || it.transcript.isNotBlank()) }
            recs.forEach { ready += c.name to it }
            recs.groupBy { it.date }.forEach { (date, sessions) ->
                val ts = sessions.mapNotNull { parseTs(it.decidedAt) }.maxOrNull() ?: dateTs(date) ?: now
                out += Draft(
                    id = "notes-${c.id}-$date",
                    type = TYPE_RECORDING_DONE,
                    title = "${c.name.ifBlank { "课程" }} $date 课堂纪要已生成",
                    body = "课堂纪要已生成",
                    ts = ts,
                    silent = now - ts > 3 * 86_400_000L,
                )
            }
        }
        readyNotes = ready
        return out
    }

    /** 最近一轮轮询到的已出纪要（课程名 + 节次），供学生端「今日复习」后台预生成。 */
    var readyNotes: List<Pair<String, ClassRecordingInfo>> = emptyList()
        private set

    /** 作业批改（学生端）：老师批改 / 打回后一条（同一版本只提醒一次；重交再批会再提醒）。 */
    internal suspend fun pollGrades(): List<Draft> {
        val now = nowMs()
        val items = AssignmentRepo.refresh(maxAgeMs = 30_000) ?: return emptyList()
        return items.mapNotNull { it ->
            val sub = it.mine ?: return@mapNotNull null
            val reviewed = sub.reviewedAt?.takeIf { r -> r.isNotBlank() }
                ?: return@mapNotNull null
            if (sub.status != "GRADED" && sub.status != "RETURNED" && sub.score == null) return@mapNotNull null
            val a = it.assignment
            val ts = parseTs(reviewed) ?: now
            val returned = sub.status == "RETURNED"
            val score = sub.score?.let { s -> if (s % 1.0 == 0.0) s.toInt().toString() else s.toString() }
            Draft(
                id = "grade-${sub.id}-${sub.version}-$reviewed",
                type = TYPE_GRADE_PUBLISHED,
                title = if (returned) "《${a.title}》被老师打回，请修改后重交"
                else "《${a.title}》已批改" + (score?.let { s -> "：$s / ${a.totalScore.toInt()} 分" } ?: ""),
                body = sub.comment?.takeIf { c -> c.isNotBlank() }?.let { c -> "老师评语：$c" } ?: "点开作业查看批改详情",
                ts = ts,
                silent = now - ts > 3 * 86_400_000L,
            )
        }
    }

    // ───────────────────────── 时间解析 ─────────────────────────

    /** ISO 时间（带或不带时区；不带按东八区）→ epoch 毫秒。 */
    internal fun parseTs(s: String?): Long? {
        val t = s?.trim()?.replace(' ', 'T')?.takeIf { it.length >= 16 } ?: return null
        val withZone = if (t.endsWith("Z") || Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(t.substring(10))) t else "$t+08:00"
        return runCatching { kotlin.time.Instant.parse(withZone).toEpochMilliseconds() }.getOrNull()
    }

    /** yyyy-MM-dd（东八区当天 20:00，排在当天其它事件之后）→ epoch 毫秒。 */
    internal fun dateTs(date: String): Long? {
        val p = date.split('-').mapNotNull { it.toIntOrNull() }
        if (p.size != 3) return null
        // days from civil（Howard Hinnant）
        val y = if (p[1] <= 2) p[0] - 1 else p[0]
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (p[1] + 9) % 12
        val doy = (153 * mp + 2) / 5 + p[2] - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146097L + doe - 719468
        return days * 86_400_000L + 12 * 3_600_000L
    }
}

/**
 * 系统通知桥（2026-11-03）：commonMain 只声明能力，Android 在 MainActivity 注册实现（不加 expect，避免动 iOS）。
 * App 在后台时新通知走系统通知栏；在前台时只响提示音 + 应用内提示。
 */
object SystemNotifier {
    /** App 是否在前台（Android 由 MainActivity onStart / onStop 维护）。 */
    var foreground by mutableStateOf(true)
    /** 发系统通知：(通知 id, 标题, 正文)。点开后 App 把 id 写到 [opened]。 */
    var post: ((String, String, String) -> Unit)? = null
    var cancelAll: (() -> Unit)? = null
    /** 前台新通知提示音。 */
    var playSound: (() -> Unit)? = null
    /** 用户点了系统通知：待处理的通知 id（首页消费后置 null）。 */
    val opened = MutableStateFlow<String?>(null)
}

/**
 * 通知中心（2026-11-03）：登录后进程级常驻轮询（前台每 60 秒、后台每 2 分钟），不依赖哪个页面开着。
 * 新通知：前台 → 提示音 + [incoming]（首页弹应用内提示）；后台 → 系统通知。
 */
object NoticeCenter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val _incoming = MutableSharedFlow<Notifications.Info>(extraBufferCapacity = 8)
    val incoming: SharedFlow<Notifications.Info> = _incoming.asSharedFlow()

    fun start(teacher: Boolean) {
        if (job?.isActive == true) return
        Notifications.refreshUnread()
        job = scope.launch {
            delay(1_500) // 让首页先渲染
            while (true) {
                pollOnce(teacher)
                delay(if (SystemNotifier.foreground) 60_000 else 120_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** 立即查一次（如 B 站总结刚提交后不想等一分钟时）。 */
    fun pollSoon(teacher: Boolean) {
        scope.launch { pollOnce(teacher) }
    }

    private suspend fun pollOnce(teacher: Boolean) {
        if (KewuApi.token == null) return
        val drafts = mutableListOf<Notifications.Draft>()
        suspend fun collect(block: suspend () -> List<Notifications.Draft>) {
            try { drafts += block() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        collect { Notifications.pollClassNotes() }
        if (!teacher) collect { Notifications.pollGrades() }
        collect { BiliNoteWatcher.poll() }
        // 学生：顺手把近几天纪要的「今日复习」提前生成好（后台进行，不阻塞通知）
        if (!teacher) com.kewu.app.ui.DailyReview.prefetch(Notifications.readyNotes)
        val fresh = Notifications.addAll(drafts)
        if (fresh.isEmpty()) return
        withContext(Dispatchers.Main) {
            if (SystemNotifier.foreground) {
                SystemNotifier.playSound?.invoke()
                fresh.take(3).forEach { _incoming.tryEmit(it) }
            } else {
                fresh.take(5).forEach { SystemNotifier.post?.invoke(it.id, it.title, it.body) }
            }
        }
    }
}

/**
 * B 站视频 AI 总结的后台跟踪（2026-11-03）：用户点了「AI 总结」后退出播放页，任务在服务器继续跑；
 * 这里记下视频，轮询到完成 / 失败就发通知，点通知回到该视频（可直接问小悟）。
 */
object BiliNoteWatcher {
    private val json = Json { ignoreUnknownKeys = true }
    private fun watchKey() = "kewu_bili_watch_" + KewuApi.username.orEmpty()
    private fun infoKey(bvid: String) = "kewu_bili_info_$bvid"

    private fun watching(): List<Pair<String, Long>> = runCatching {
        (json.parseToJsonElement(kvGet(watchKey()) ?: return emptyList()) as JsonArray).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val b = (o["b"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            b to ((o["t"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L)
        }
    }.getOrDefault(emptyList())

    private fun saveWatching(list: List<Pair<String, Long>>) {
        kvSet(watchKey(), buildJsonArray {
            list.distinctBy { it.first }.forEach { (b, t) -> add(buildJsonObject { put("b", b); put("t", t) }) }
        }.toString())
    }

    /** 开始跟踪（生成中）。同时记住视频信息，点通知时用来重新打开播放页。 */
    fun watch(v: BilibiliVideoInfo) {
        kvSet(infoKey(v.bvid), buildJsonObject {
            put("b", v.bvid); put("t", v.title); put("c", v.cover); put("d", v.duration)
        }.toString())
        if (watching().none { it.first == v.bvid }) saveWatching(watching() + (v.bvid to Notifications.nowMs()))
    }

    /** 用户就在播放页上看着它完成了：不用再通知。 */
    fun unwatch(bvid: String) {
        saveWatching(watching().filter { it.first != bvid })
    }

    /** 通知里记下的视频（直链已过期，播放页会按标题重新取）。 */
    fun video(bvid: String): BilibiliVideoInfo? = runCatching {
        val o = json.parseToJsonElement(kvGet(infoKey(bvid)) ?: return null) as JsonObject
        fun f(k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty()
        BilibiliVideoInfo(f("b"), f("t"), f("c"), null, null, f("d"))
    }.getOrNull()

    internal suspend fun poll(): List<Notifications.Draft> {
        val list = watching()
        if (list.isEmpty()) return emptyList()
        val now = Notifications.nowMs()
        val out = mutableListOf<Notifications.Draft>()
        val keep = mutableListOf<Pair<String, Long>>()
        for ((bvid, since) in list) {
            val title = video(bvid)?.title?.ifBlank { null } ?: bvid
            val n = KewuApi.getBilibiliNote(bvid)
            when {
                n?.status == "FINISHED" -> out += Notifications.Draft(
                    "bili-$bvid-$since", Notifications.TYPE_BILI_NOTE, "视频 AI 总结已生成", title, now,
                )
                n?.status == "FAILED" -> out += Notifications.Draft(
                    "bili-$bvid-$since", Notifications.TYPE_BILI_NOTE, "视频 AI 总结没生成成功，可重新生成", title, now,
                )
                now - since > 2 * 3_600_000L -> Unit // 超过 2 小时还没好，放弃跟踪
                else -> keep += bvid to since
            }
        }
        saveWatching(keep)
        return out
    }

    /** 通知 id → bvid。 */
    fun bvidOf(notificationId: String): String? =
        notificationId.removePrefix("bili-").takeIf { notificationId.startsWith("bili-") }?.substringBeforeLast('-')
}
