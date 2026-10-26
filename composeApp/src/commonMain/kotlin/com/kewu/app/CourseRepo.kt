package com.kewu.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 课程列表的进程级缓存（2026-10-26）。
 *
 * 修「学生端首页偶发加载不出课程、显示无课，必须去『我的』页转一圈回来才出现」：
 *   - 旧版首页只在首次组合时请求一次 `/api/courses`，而且要先等 `/user/me` 返回；
 *     任一请求慢（OkHttp read 超时 120s）或失败，首页就一直空着，没有任何重试；
 *   - 「我的」页自己另请求一次，两边数据互不相通。
 * 现在：首页 / 我的 / 课程列表页共用 [courses]；[refresh] 单次请求 8s 超时、失败或空列表自动退避重试，
 * 并发调用合并成同一次加载（Mutex），任一页面拉到的结果其它页面立刻可见。
 */
object CourseRepo {

    private val _courses = MutableStateFlow<List<CourseInfo>?>(null)

    /** 最近一次成功拉到的课程；null = 还没成功过。 */
    val courses: StateFlow<List<CourseInfo>?> = _courses.asStateFlow()

    private val _loaded = MutableStateFlow(false)

    /** 是否已经完成过至少一轮加载（成功或重试耗尽），供页面区分「加载中」与「确实没课」。 */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val mutex = Mutex()

    /** 重试间隔：首次立即，之后 0.8s / 1.6s / 3.2s。 */
    private val BACKOFF_MS = longArrayOf(0L, 800L, 1_600L, 3_200L)

    /**
     * 拉取课程。[force] = false 且已有非空缓存时直接返回缓存（页面切回来不重复打接口）。
     * 返回最新列表；全部重试失败返回缓存（可能为 null）。
     */
    suspend fun refresh(force: Boolean = false): List<CourseInfo>? = mutex.withLock {
        val cached = _courses.value
        if (!force && !cached.isNullOrEmpty()) return@withLock cached
        var best: List<CourseInfo>? = null
        for (wait in BACKOFF_MS) {
            if (wait > 0) delay(wait)
            // 2026-10-26：只取「我的课程」（旧版取全站课程列表，所有账号看到同一批课）
            val r = withTimeoutOrNull(8_000L) { KewuApi.myCourses(force = true) }
            if (!r.isNullOrEmpty()) {
                best = r
                break
            }
            // 空列表也可能是真的没课：记下来，但仍再试几次（偶发返回空是这次 bug 的表现之一）
            if (r != null) best = r
        }
        if (best != null) _courses.value = best
        _loaded.value = true
        _courses.value
    }

    /** 本地移除一门课（退出课程成功后调用，首页同步消失）。 */
    fun remove(courseId: String) {
        _courses.value = _courses.value?.filter { it.id != courseId }
    }

    /** 退出登录时清空，避免下一个账号看到上一个账号的课程。 */
    fun clear() {
        _courses.value = null
        _loaded.value = false
    }
}
