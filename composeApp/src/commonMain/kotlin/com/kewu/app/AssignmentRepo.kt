package com.kewu.app

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * 学生端「作业 + 我的提交」进程级缓存（2026-10-26）。
 *
 * 旧版任务页、首页「作业待交」、助教打回检查三处各自对每个作业**串行**调一次 `/my`（N+1），
 * 首页待交数干脆写死 "3"。后端还没有批量接口（已写进后端整改需求 §5.3），先在前端：
 * 一次拉作业列表 + 4 路并行查我的提交，结果共享；任一页面刷新后其它页面立即可见。
 */
object AssignmentRepo {

    /** 一条作业 + 我的最新提交（未提交为 null）。 */
    data class Item(val assignment: AssignmentInfo, val mine: SubmissionInfo?) {
        /** 待交：没交过，或被老师打回待重交。 */
        val pending: Boolean get() = mine == null || mine.status == "RETURNED"
    }

    private val _items = MutableStateFlow<List<Item>?>(null)

    /** 最近一次成功的结果；null = 还没成功过。 */
    val items: StateFlow<List<Item>?> = _items.asStateFlow()

    private val _failed = MutableStateFlow(false)

    /** 最近一次刷新是否失败（列表接口拿不到）。 */
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    private val lock = Mutex()
    private var fetchedAt = 0L

    // ── ★ H3（2026-11-05）：「任务」tab 红点的持久化已读快照 ──
    // 旧版 badge = 待交作业的派生存量，TaskPage 没有任何已读写入，只靠 navBar 的 `selected != i`
    // 临时不画红点 → 切走再回来必然重现（用户「根本消不掉」）。这里存每条作业的「id#提交状态」签名：
    // 新作业(mine==null) 或被打回(RETURNED) 与已读时签名不同 → 重新算未读；交完作业 pending=false 不计入。
    private const val KEY_SEEN = "kewu_seen_assignments"
    private fun seenKey(): String =
        KewuApi.username?.takeIf { it.isNotBlank() }?.let { "${KEY_SEEN}_u_$it" } ?: KEY_SEEN
    private fun loadSeen(): Set<String> = kvGet(seenKey()).orEmpty()
        .split(',').filter { it.isNotBlank() }.toSet()
    private fun signature(item: Item) = item.assignment.id + "#" + (item.mine?.status ?: "none")

    private val _seen = MutableStateFlow(loadSeen())
    /** 已读作业签名集合（MainScreen collect 后红点即时归零，切 tab 不再重现）。 */
    val seen: StateFlow<Set<String>> = _seen.asStateFlow()

    /** 已绑定到哪个账号的 key；同账号只重载一次，避免 refresh 与 markAllSeen 并发时把快照覆盖回旧值。 */
    private var boundKey: String? = null

    /** 账号变化 / 刷新后重新绑定已读快照（object 初始化时 username 可能还是空的）。 */
    fun reloadSeen() {
        val k = seenKey()
        if (k == boundKey) return
        boundKey = k
        _seen.value = loadSeen()
    }

    /** 进入任务页时调用：把当前看到的全部作业记为已读（仿 ChatHistory.markAllAssistantRead）。 */
    fun markAllSeen(items: List<Item>) {
        val merged = (loadSeen().toList() + items.map(::signature)).takeLast(300).toSet()
        if (merged == _seen.value) return
        kvSet(seenKey(), merged.joinToString(","))
        _seen.value = merged
    }

    /** 红点数 = 待交且「未见过」的作业（新发布 / 被打回才算；存量待办不再常亮）。 */
    fun unreadCount(items: List<Item>?, seenSet: Set<String> = _seen.value): Int =
        items.orEmpty().count { it.pending && signature(it) !in seenSet }

    /**
     * 刷新。[maxAgeMs] 内刚刷过就直接返回缓存（多个页面同时进来只打一轮）。
     * 返回最新列表；作业列表都拿不到时返回 null 并置 [failed]。
     */
    suspend fun refresh(maxAgeMs: Long = 0L): List<Item>? = lock.withLock {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        _items.value?.let { if (now - fetchedAt < maxAgeMs) return@withLock it }
        // 2026-10-26：只看「我的课程」里的作业（旧版拿全站作业，所有学生看到同一批）
        val myIds = (CourseRepo.courses.value ?: CourseRepo.refresh()).orEmpty().map { it.id }.toSet()
        val me = KewuApi.username.orEmpty()
        // 指定学生的作业（AI 分层补救）只给名单里的人看
        val list = KewuApi.getAssignments()?.filter { it.courseId in myIds && (it.targetStudents.isEmpty() || me in it.targetStudents) }
        if (list == null) {
            _failed.value = true
            return@withLock _items.value
        }
        val studentId = KewuApi.username.orEmpty()
        val gate = Semaphore(4)
        val items = coroutineScope {
            list.map { a ->
                async {
                    val mine = if (studentId.isEmpty()) null
                    else gate.withPermit { KewuApi.getMySubmissions(a.id, studentId) }
                    Item(a, mine)
                }
            }.awaitAll()
        }
        _items.value = items
        _failed.value = false
        fetchedAt = now
        // ★ H3：刷新后按当前账号重新绑定已读快照（避免 object 初始化时 username 为空读到错的账号）
        reloadSeen()
        items
    }

    /** 提交成功后本地先标记（不等下一轮刷新），再后台刷新。 */
    fun markSubmitted(assignmentId: String) {
        _items.value = _items.value?.map {
            if (it.assignment.id == assignmentId && it.mine == null) it.copy(mine = placeholderSubmission(it.assignment)) else it
        }
    }

    private fun placeholderSubmission(a: AssignmentInfo) = SubmissionInfo(
        id = "", assignmentId = a.id, studentId = KewuApi.username.orEmpty(), studentName = "",
        content = "", version = 1, submittedAt = "", status = "PENDING", score = null, comment = null,
        reviewer = null, reviewedAt = null, history = emptyList(), ai = null,
    )

    fun clear() {
        _items.value = null
        _failed.value = false
        fetchedAt = 0L
    }
}
