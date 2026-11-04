package com.kewu.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * 助教（小悟）消息中枢 + 底部「助教」tab 红点（2026-10-26 重做）。
 *
 * 用户口径：红点只在「AI 主动给我发了消息、而我还没看到」时出现——
 *   1. 我问了问题，没等回答就切走了，回答到达时我不在助教页 → 亮；
 *   2. 教师端：小悟当天的教学简报还没看 → 亮；
 *   3. 学生端：老师把我的作业打回，小悟主动发来「该往哪方面改」的提醒 → 亮；
 *   而「我就在助教页里看着它回答」的消息**不算未读**（旧版的 bug：在助教页问答完，切到别的页面反而冒红点）。
 *
 * 实现要点：
 *   - AI 请求跑在本对象的进程级协程里（[ask]），不再挂在页面 rememberCoroutineScope 上——旧版切走页面
 *     请求直接被取消，回答永远到不了，也就谈不上「回来提醒」；
 *   - 回复统一由 [deliver] 落盘（[ChatHistory.append]）并广播 [incoming]，页面在就地追加，不在则计入未读；
 *   - 可见性由主界面按当前 tab 调 [setVisible]：进入、以及**离开**助教页时都把已读游标推到当前时刻
 *     （在页内看过的都算已读），离开之后才到达的消息才是未读。
 */
object AssistantHub {

    /** 教师端「当天简报已播报」标记（存当天 00:00 的 epoch ms），由助教页写、这里读来算红点。 */
    const val KEY_TA_BRIEF_DAY = "kewu_ta_brief_day"

    /**
     * 教师端「今天已进过助教页」标记（当天 00:00 的 epoch ms）。2026-11-03：红点的「简报 3 条」改看这个，
     * 进页即写；旧版看 [KEY_TA_BRIEF_DAY]，而它要等助教页一串网络请求（课程/作业/签到…）都回来才写，
     * 网络慢或老师没等完就切走，标记永远写不上 → 红点 3 一直在。
     */
    private const val KEY_TA_BRIEF_SEEN = "kewu_ta_brief_seen"

    /** 教师简报未播报时在红点上计入的条数（与助教页简报气泡条数一致）。 */
    private const val BRIEF_BUBBLES = 3

    /** 学生端已提醒过的「被打回」提交（submissionId#version，逗号分隔），避免重复提醒。 */
    private const val KEY_RETURN_NOTIFIED = "kewu_ta_return_notified"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val visible = mutableMapOf<String, Boolean>()
    private val unreadFlows = mutableMapOf<String, MutableStateFlow<Int>>()

    private val _pending = MutableStateFlow<Set<String>>(emptySet())

    /** 正在等 AI 回复的会话 key 集合（页面据此显示「小悟正在输入中…」，切走再回来也能接上）。 */
    val pending: StateFlow<Set<String>> = _pending.asStateFlow()

    private val _streaming = MutableStateFlow<Map<String, String>>(emptyMap())

    /** 正在流式输出的回答（会话 key → 已到达的可见文本）。2026-11-05（B1）：key 只在**吐出第一个字**时才出现——
     *  map 里没有 key = 请求已发出但还没吐字（思考中，页面不显示气泡，状态看 [pending]）。 */
    val streaming: StateFlow<Map<String, String>> = _streaming.asStateFlow()

    /**
     * 流式版 [ask]（2026-10-26 打字机效果）：[block] 拿到 emit，每到一段回答就 emit（累积文本由这里维护），
     * 页面订阅 [streaming] 实时显示；结束后照常 [deliver] 落盘并清掉流式草稿。
     *
     * ★ B1（2026-11-05）：不再先塞 `key to ""`——空串会让页面立刻渲染「小悟正在思考…」占位气泡。
     * 用户口径：发消息后先什么都不显示，等流式输出**拿到第一个字**再出现气泡。思考状态由 [pending] 承担。
     */
    fun askStream(key: String, block: suspend (emit: (String) -> Unit) -> Reply) {
        _pending.value = _pending.value + key
        val acc = StringBuilder()
        scope.launch {
            val reply = try {
                block { piece ->
                    acc.append(piece)
                    _streaming.value = _streaming.value + (key to AiAgent.visiblePart(acc.toString()))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Reply("小悟暂时连不上 AI 服务，请稍后再试～")
            } finally {
                _pending.value = _pending.value - key
            }
            deliver(key, reply.text, reply.payload)
            _streaming.value = _streaming.value - key
        }
    }

    /** 一条到达的助手消息：会话 key + 已落盘条目 + 页面自定义的富内容（教师端气泡块等，可空）。 */
    data class Incoming(val key: String, val entry: ChatHistory.Entry, val payload: Any? = null)

    private val _incoming = MutableSharedFlow<Incoming>(extraBufferCapacity = 32)

    /** 新到达的助手消息广播（页面在前台时就地追加）。 */
    val incoming: SharedFlow<Incoming> = _incoming.asSharedFlow()

    /** 助手回复：落盘文本 + 可选富内容。 */
    data class Reply(val text: String, val payload: Any? = null)

    private fun flowOf(key: String) = unreadFlows.getOrPut(key) { MutableStateFlow(0) }

    /** 某会话的未读数（底部「助教」tab 红点）。 */
    fun unread(key: String): StateFlow<Int> = flowOf(key)

    fun isVisible(key: String): Boolean = visible[key] == true

    /**
     * 主界面切 tab 时调用。进入或离开助教页都推进已读游标——在页内看到的消息全部算已读；
     * 两个非助教 tab 之间切换不动游标（否则会把后台到达的未读误清）。
     */
    fun setVisible(key: String, isVisible: Boolean) {
        val was = visible[key] == true
        visible[key] = isVisible
        if (isVisible || was) ChatHistory.markAllAssistantRead(key)
        if (isVisible && key == ChatHistory.KEY_TEACHER) kvSet(KEY_TA_BRIEF_SEEN, todayStart().toString())
        refreshUnread(key)
    }

    /** 重新统计未读（启动、切 tab、跨天时调用）。 */
    fun refreshUnread(key: String) {
        flowOf(key).value = if (isVisible(key)) 0 else ChatHistory.getUnreadAssistantCount(key) + extraUnread(key)
    }

    private fun extraUnread(key: String): Int =
        if (key == ChatHistory.KEY_TEACHER && !teacherBriefSeenToday()) BRIEF_BUBBLES else 0

    private fun todayStart(): Long = ChatHistory.dayStart(Clock.System.now().toEpochMilliseconds())

    fun teacherBriefPlayedToday(): Boolean = kvGet(KEY_TA_BRIEF_DAY)?.toLongOrNull() == todayStart()

    private fun teacherBriefSeenToday(): Boolean =
        teacherBriefPlayedToday() || kvGet(KEY_TA_BRIEF_SEEN)?.toLongOrNull() == todayStart()

    /**
     * 投递一条助手消息：落盘 → 页面在前台就推进已读游标 → 广播 → 重算红点。
     * 页面自己生成并落盘的消息（如教师简报）不走这里。
     */
    fun deliver(key: String, text: String, payload: Any? = null): ChatHistory.Entry {
        // ★ V7：AI 回答里的 B 站推荐关键词必须随 Entry 一起落盘。
        //   旧版只有 _incoming 的 payload 带它，页面不在前台 / 切页重建消息时标记就丢了，
        //   表现为「AI 刚发的 B 站视频卡片消失」。
        val biliKw = (payload as? com.kewu.app.ui.BiliSuggest)?.keyword?.takeIf { it.isNotBlank() }
        val entry = ChatHistory.Entry(
            id = ChatHistory.nextId(), role = "assistant", text = text,
            ts = Clock.System.now().toEpochMilliseconds(),
            biliKeyword = biliKw,
        )
        ChatHistory.append(key, entry)
        if (isVisible(key)) ChatHistory.markAllAssistantRead(key)
        _incoming.tryEmit(Incoming(key, entry, payload))
        refreshUnread(key)
        return entry
    }

    /**
     * ★ W8（2026-11-04 晚 3）：AI 语音通话的一轮对话同步到普通聊天记录。
     * 用户「我虽然是用语音说的话，但最后还是要以气泡的方式呈现在聊天界面」——
     * 落盘 user + assistant 两条，并通过 [incoming] 广播让前台页面立即上屏成气泡；
     * 页面不在前台时下次进入 `ChatHistory.loadAll` 也会带上。
     * 通话由用户主动发起，顺手推已读游标，避免挂断后助教 tab 冒出未读红点。
     */
    fun deliverVoiceTurn(key: String, userText: String, aiText: String) {
        val t = Clock.System.now().toEpochMilliseconds()
        val u = ChatHistory.Entry(id = ChatHistory.nextId(), role = "user", text = userText, ts = t)
        val a = ChatHistory.Entry(id = ChatHistory.nextId(), role = "assistant", text = aiText, ts = t + 1)
        ChatHistory.upsert(key, listOf(u, a))
        if (isVisible(key)) ChatHistory.markAllAssistantRead(key)
        _incoming.tryEmit(Incoming(key, u))
        _incoming.tryEmit(Incoming(key, a))
        refreshUnread(key)
    }

    /**
     * 发起一次 AI 回复（进程级协程，切走页面也会继续）。[block] 返回要投递的回复；
     * 抛异常时投递一条「暂时连不上」的说明，保证不会无声无息。
     */
    fun ask(key: String, block: suspend () -> Reply) {
        _pending.value = _pending.value + key
        scope.launch {
            val reply = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Reply("小悟暂时连不上 AI 服务，请稍后再试～")
            } finally {
                _pending.value = _pending.value - key
            }
            deliver(key, reply.text, reply.payload)
        }
    }

    // ───────────────────────── 学生端：作业被打回的主动提醒 ─────────────────────────

    private val returnCheckLock = Mutex()

    /**
     * 扫一遍「我的提交」，发现新的被打回（status=RETURNED，按 提交id#版本 去重）就让小悟主动发一条
     * 修改建议（真实大模型，失败用模板），进而点亮学生端助教红点。由学生主界面启动时与定时调用。
     */
    suspend fun checkReturnedAssignments() {
        if (!returnCheckLock.tryLock()) return
        try {
            if (KewuApi.username.isNullOrBlank()) return
            // 与任务页 / 首页共用 AssignmentRepo（并行查询，1 分钟内复用），不再自己逐个串行请求
            val items = AssignmentRepo.refresh(maxAgeMs = 60_000) ?: return
            val notified = kvGet(KEY_RETURN_NOTIFIED).orEmpty().split(',').filter { it.isNotBlank() }.toMutableSet()
            for (item in items) {
                val a = item.assignment
                val sub = item.mine ?: continue
                if (sub.status != "RETURNED") continue
                val mark = "${sub.id}#${sub.version}"
                if (mark in notified) continue
                notified += mark
                kvSet(KEY_RETURN_NOTIFIED, notified.toList().takeLast(200).joinToString(","))
                deliver(ChatHistory.KEY_STUDENT, returnAdvice(a, sub))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 网络异常：下一轮再查
        } finally {
            returnCheckLock.unlock()
        }
    }

    private suspend fun returnAdvice(a: AssignmentInfo, sub: SubmissionInfo): String {
        val head = "📌 老师把你的作业《${a.title}》打回了，需要修改后重新提交。"
        val comment = sub.comment?.takeIf { it.isNotBlank() }
        val missed = sub.ai?.missedKnowledgePoints.orEmpty()
        val system = "你是「小悟」，课悟 App 里学生的 AI 学习助手。老师刚把学生的作业打回重做，" +
            "请根据老师评语和作业要求，用中文给出 3 条以内、每条一行、以「·」开头的具体修改方向，" +
            "语气鼓励、直接可执行；不要代写答案；可用简洁 markdown（加粗关键词），总长 150 字以内。"
        val user = buildString {
            appendLine("作业：${a.title}")
            if (a.description.isNotBlank()) appendLine("要求：${a.description.take(400)}")
            if (a.knowledgePoints.isNotEmpty()) appendLine("知识点：${a.knowledgePoints.joinToString("、")}")
            appendLine("老师评语：${comment ?: "（无）"}")
            if (missed.isNotEmpty()) appendLine("AI 预批阅认为薄弱的知识点：${missed.joinToString("、")}")
            append("学生原提交（节选）：${sub.content.take(600)}")
        }
        val ai = AiClient.chat(system, user, maxTokens = 3072)
        val body = ai?.let { AiAgent.parse(it).text.trim() }?.takeIf { it.isNotEmpty() } ?: buildString {
            comment?.let { append("老师的意见：$it\n") }
            if (missed.isNotEmpty()) append("· 重点补一补：${missed.joinToString("、")}\n")
            append("· 对照作业要求逐条检查有没有漏答\n· 改完后在「任务」里重新提交即可")
        }
        return "$head\n$body"
    }
}
