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

    /**
     * 刷新。[maxAgeMs] 内刚刷过就直接返回缓存（多个页面同时进来只打一轮）。
     * 返回最新列表；作业列表都拿不到时返回 null 并置 [failed]。
     */
    suspend fun refresh(maxAgeMs: Long = 0L): List<Item>? = lock.withLock {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        _items.value?.let { if (now - fetchedAt < maxAgeMs) return@withLock it }
        val list = KewuApi.getAssignments()
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
