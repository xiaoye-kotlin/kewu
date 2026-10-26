package com.kewu.app

import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 助教对话聊天记录持久化（2026-10-24）。
 *
 * 结构：kv 里存一个 JSON 数组（每条：id / role / text / ts / quote），学生端与教师端
 * 各用一个 key。进入页面只加载「今天」的消息；用户在列表顶部下拉再按天向前加载。
 * 容量上限 [CAP] 条，超出丢弃最旧的（先存后裁）。
 *
 * 时区按 UTC+8（与 greetingByHour 的口径一致）；dayStart = 当天 00:00 的 epoch 毫秒。
 * 项目无 serialization 编译插件，照旧手工拼/解析 JSON。
 */
object ChatHistory {

    const val KEY_STUDENT = "kewu_chat_student"
    const val KEY_TEACHER = "kewu_chat_teacher"

    private const val CAP = 400
    // 「已读游标」：记录该会话已读到的时间戳（epoch ms）；晚于此时间戳的 assistant 消息即未读。
    private const val KEY_READ_PREFIX = "kewu_chat_read_"
    // 一键种入欢迎语的只跑一次标记。
    private const val KEY_SEED_FLAG = "kewu_chat_seeded_"
    private val json = Json { ignoreUnknownKeys = true }

    // ── 2026-10-26 性能 + 隐私 ──
    // ① 内存缓存：旧版每次切 tab / 每 60s 红点重算 / 每发一条消息都在主线程把整份记录（≤400 条）
    //    从 kv 读出来重新解析，正好压在切页动画上。现在首次解析后常驻内存，写盘丢到后台串行执行。
    // ② 按账号隔离：旧版只按角色分 key，同一台手机换账号能看到上一个人的对话。
    private val cache = mutableMapOf<String, List<Entry>>()
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writeLock = Mutex()

    /** 实际存储 key = 逻辑 key + 当前账号；未登录时退回逻辑 key。 */
    private fun storeKey(key: String): String {
        val user = KewuApi.username?.takeIf { it.isNotBlank() } ?: return key
        val sk = "${key}_u_$user"
        // 一次性迁移：老版本（不分账号）的记录归给升级后第一个登录的账号
        if (kvGet(sk) == null && kvGet("${sk}_migrated") == null) {
            kvSet("${sk}_migrated", "1")
            kvGet(key)?.let { legacy ->
                kvSet(sk, legacy)
                kvSet(key, null)
                kvGet(KEY_READ_PREFIX + key)?.let { kvSet(KEY_READ_PREFIX + sk, it) }
                kvGet(KEY_SEED_FLAG + key)?.let { kvSet(KEY_SEED_FLAG + sk, it) }
            }
        }
        return sk
    }

    /** 退出登录时调用：清内存缓存（磁盘上按账号分开存，不必删）。 */
    fun clearCache() = cache.clear()

    data class Entry(
        val id: Long,
        val role: String,   // "user" / "assistant"
        val text: String,
        val ts: Long,
        val quote: String? = null,
    )

    private var idSeq = 0L

    /** 生成单调递增的本地消息 id（恢复历史用存的 id，不冲突即可）。 */
    fun nextId(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds() * 1000 + (++idSeq % 1000)

    /** UTC+8 当天 00:00 的 epoch 毫秒。 */
    fun dayStart(ts: Long): Long {
        val tz8 = 8L * 3_600_000L
        return (ts + tz8) / 86_400_000L * 86_400_000L - tz8
    }

    fun loadAll(key: String): List<Entry> {
        val sk = storeKey(key)
        return cache.getOrPut(sk) { parseStored(sk) }
    }

    private fun parseStored(sk: String): List<Entry> = try {
        val raw = kvGet(sk) ?: return emptyList()
        val arr = json.parseToJsonElement(raw).jsonArray
        arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            Entry(
                id = o["id"]?.jsonPrimitive?.longOrNull ?: 0L,
                role = o["role"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                text = o["text"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                ts = o["ts"]?.jsonPrimitive?.longOrNull ?: 0L,
                quote = o["quote"]?.jsonPrimitive?.content,
            )
        }.sortedBy { it.ts }
    } catch (_: Exception) {
        emptyList()
    }

    /** 全量重写（先存后裁到 [CAP] 条）。
     *  desktop 端 kv 是 java.util.prefs（Windows 注册表单值上限 8KB），超长会抛异常 ——
     *  这里渐进丢弃最旧消息直到能写下，绝不把异常抛给页面（2026-10-24 desktop 崩溃修复）。 */
    fun saveAll(key: String, entries: List<Entry>) {
        val sk = storeKey(key)
        val sorted = entries.sortedBy { it.ts }.takeLast(CAP)
        cache[sk] = sorted
        // 序列化 + 写 kv 放后台串行执行；后台线程只碰不可变快照 + 锁内的序号表，
        // 并发的多次保存按序号「新者覆盖旧者」，乱序到达的旧快照直接跳过
        val seq = ++writeSeq
        writeScope.launch {
            writeLock.withLock {
                if (seq < (writtenSeq[sk] ?: 0L)) return@withLock
                writtenSeq[sk] = seq
                writeStored(sk, sorted)
            }
        }
    }
    private var writeSeq = 0L
    private val writtenSeq = mutableMapOf<String, Long>() // 仅在 writeLock 内访问

    private fun writeStored(sk: String, entries: List<Entry>) {
        var kept = entries
        while (true) {
            val arr = buildJsonArray {
                kept.forEach { e ->
                    add(buildJsonObject {
                        put("id", e.id)
                        put("role", e.role)
                        put("text", e.text)
                        put("ts", e.ts)
                        if (e.quote != null) put("quote", e.quote)
                    })
                }
            }
            try {
                kvSet(sk, arr.toString())
                return
            } catch (_: Exception) {
                if (kept.size <= 10) {
                    try { kvSet(sk, "[]") } catch (_: Exception) { }
                    return
                }
                kept = kept.takeLast(kept.size / 2)
            }
        }
    }

    /** 追加一条（[AssistantHub.deliver] 用：页面不在前台时也能落盘）。 */
    fun append(key: String, entry: Entry) = upsert(key, listOf(entry))

    /**
     * 按 id 合并写入：已有的替换、新的追加，**不删除**存档里其它消息。
     * 页面落盘统一走这里（2026-10-26）：旧版 saveAll(页面内存里的消息) 会把「页面没加载的更早几天」
     * 以及「页面不在时 AssistantHub 追加的回复」一并覆盖掉。
     */
    fun upsert(key: String, entries: List<Entry>) {
        if (entries.isEmpty()) return
        // 老数据可能 id=0，退回用 时间戳+角色+正文 作合并键，避免多条 id=0 被并成一条
        fun mk(e: Entry): String = if (e.id != 0L) "i${e.id}" else "t${e.ts}|${e.role}|${e.text.hashCode()}"
        val merged = LinkedHashMap<String, Entry>()
        loadAll(key).forEach { merged[mk(it)] = it }
        entries.forEach { merged[mk(it)] = it }
        saveAll(key, merged.values.toList())
    }

    /**
     * 未读的 AI（小悟，role=="assistant"）消息数：晚于「已读游标」的 assistant 消息。
     * ★ 仅用于底部导航栏第二个「助教」tab 的红点；与首页铃铛通知完全独立。
     */
    fun getUnreadAssistantCount(key: String): Int {
        val readTs = kvGet(KEY_READ_PREFIX + storeKey(key))?.toLongOrNull() ?: 0L
        if (readTs == 0L) {
            // 从未打开过助教：全部历史 assistant 消息都算未读
            return loadAll(key).count { it.role == "assistant" }
        }
        return loadAll(key).count { it.role == "assistant" && it.ts > readTs }
    }

    /** 进入助教页时调用：把已读游标推到当前时刻 → 清空未读红点。 */
    fun markAllAssistantRead(key: String) {
        kvSet(KEY_READ_PREFIX + storeKey(key), Clock.System.now().toEpochMilliseconds().toString())
    }

    /**
     * 首次运行种入一条小悟欢迎语（仅一次），使「助教」红点天然可见、且助教对话非空。
     * 已存在历史则不覆盖。
     */
    fun seedWelcomeIfEmpty(key: String) {
        val flag = KEY_SEED_FLAG + storeKey(key)
        if (kvGet(flag) != null) return
        kvSet(flag, "1")
        if (loadAll(key).isNotEmpty()) return
        val now = Clock.System.now().toEpochMilliseconds()
        saveAll(key, listOf(
            Entry(nextId(), "assistant",
                "你好，我是小悟，你的 AI 学习助教。有作业、知识点或课堂录音相关的问题，都可以问我～", now)
        ))
    }
}
