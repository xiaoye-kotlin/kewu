package com.kewu.admin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 登录后「全界面一次性预加载」缓存（2026-11-17，1.0.18）。
 *
 * 用户诉求：登录成功进入首页即把**所有界面**首屏数据全部拉好（不局限于某一个），
 * 之后切换任意 tab 只读缓存、绝不重新发请求；只有手动「刷新」才更新。
 *
 * 设计：
 * - [begin] 在登录成功后调用一次：一个 coroutineScope 内 **10 个 async 并发**
 *   拉 overview/health/config/users/recordings/obs/courses/assignments/attendance/系统快照，
 *   全部完成后再并发拉默认选中项的二级数据（首个课程的班级、首个作业详情+提交）。
 * - 缓存**会话内长期有效**（不按时间过期）；[get] 直接返回值。
 * - 页面进入时状态从 [peek] 初始化，LaunchedEffect 先 [awaitReady] 再读 [get]，
 *   有值就跳过首屏请求；切 tab 因 key(shown) 重建也走同一路径，只读缓存。
 */
object AdminPrefetch {
    class Slot<T> {
        @Volatile var value: T? = null
            private set
        @Volatile var at: Long = 0L
            private set

        fun set(v: T) { value = v; at = System.currentTimeMillis() }
        /** 会话内缓存：有值即返回，不按时间过期。 */
        fun get(): T? = value
        /** 同上；保留旧命名兼容。 */
        fun peek(): T? = value
        /** 兼容：不再按时间过滤（切 tab 不重载）。 */
        fun fresh(maxAgeMs: Long = Long.MAX_VALUE): T? = value
        fun clear() { value = null; at = 0L }
    }

    val overview = Slot<JsonObject>()
    val health = Slot<JsonObject>()
    val config = Slot<List<ConfigItem>>()
    val users = Slot<PageResult>()
    val recordings = Slot<PageResult>()
    val obsRecordings = Slot<List<JsonObject>>()
    val courses = Slot<List<JsonObject>>()
    val assignments = Slot<List<JsonObject>>()
    val attendance = Slot<List<JsonObject>>()
    /** 课堂纪要（§83）：正在生成 + 已生成完毕的列表。 */
    val minutes = Slot<PageResult>()
    val serverSnapshot = Slot<ServerRuntimeSnapshot>()

    // 默认选中项的二级数据，key = 父级 id
    @Volatile private var classesByCourse: Map<String, List<JsonObject>> = emptyMap()
    @Volatile private var assignmentSubmissions: Map<String, Pair<JsonObject, List<JsonObject>>> = emptyMap()

    fun classesFor(courseId: String): List<JsonObject>? = classesByCourse[courseId]
    fun submissionsFor(assignmentId: String): Pair<JsonObject, List<JsonObject>>? = assignmentSubmissions[assignmentId]

    private var ready = CompletableDeferred<Unit>()
    private val started = AtomicBoolean(false)
    // ★ 修复（2026-11-19）：用应用级 SupervisorJob 作用域替代 GlobalScope——
    //   GlobalScope 无父作业、不可取消、不受结构化并发约束；这里改为本对象持有的作用域，
    //   单个预加载子任务失败（SupervisorJob 隔离）不会连带取消其它，语义更可控。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 登录成功调用；重复调用安全。所有首屏数据 + 默认二级数据就绪后 [awaitReady] 返回。 */
    fun begin(api: AdminApi) {
        if (!started.compareAndSet(false, true)) return
        ready = CompletableDeferred<Unit>()
        scope.launch(Dispatchers.IO) { prefetch(api) }
    }

    /** 页面首屏用：等待预加载完成；已就绪时立即返回。超时只作兜底，不影响缓存读取。 */
    suspend fun awaitReady(timeoutMs: Long = 60_000) {
        if (ready.isCompleted) return
        withTimeoutOrNull(timeoutMs) { ready.await() }
    }

    fun isReady(): Boolean = ready.isCompleted

    fun clearAll() {
        overview.clear(); health.clear(); config.clear(); users.clear(); recordings.clear()
        obsRecordings.clear(); courses.clear(); assignments.clear(); attendance.clear(); serverSnapshot.clear()
        minutes.clear()
        classesByCourse = emptyMap(); assignmentSubmissions = emptyMap()
        started.set(false)
    }

    private suspend fun prefetch(api: AdminApi) = withContext(Dispatchers.IO) {
        // 第一批：所有界面首屏数据 + OBS 全量，一次性全部并发（用户要"所有界面"）。
        coroutineScope {
            val jobs = listOf(
                async { runCatching { api.overview() }.getOrNull()?.let { overview.set(it) } },
                async { runCatching { api.health() }.getOrNull()?.let { health.set(it) } },
                async { runCatching { api.configItems() }.getOrNull()?.let { config.set(it) } },
                async { runCatching { api.page("/api/admin/users", mapOf("page" to "1", "size" to "20")) }.getOrNull()?.let { users.set(it) } },
                async { runCatching { api.page("/api/admin/recordings", mapOf("page" to "1", "size" to "1000")) }.getOrNull()?.let { recordings.set(it) } },
                async { runCatching { api.obsRecordings() }.getOrNull()?.let { obsRecordings.set(it) } },
                async { runCatching { api.list("/api/admin/courses") }.getOrNull()?.let { courses.set(it) } },
                async { runCatching { api.list("/api/admin/assignments") }.getOrNull()?.let { assignments.set(it) } },
                async { runCatching { api.list("/api/admin/attendance") }.getOrNull()?.let { attendance.set(it) } },
                async { runCatching { api.page("/api/admin/minutes", mapOf("page" to "1", "size" to "100")) }.getOrNull()?.let { minutes.set(it) } },
                async { runCatching { ServerMonitor.snapshot() }.getOrNull()?.let { serverSnapshot.set(it) } },
            )
            jobs.forEach { it.await() }
        }

        // 第二批：默认选中项（列表第一条）的二级数据，让"进入课程/作业页"也无需再加载。
        coroutineScope {
            val courseJobs = courses.get()?.firstOrNull()?.str("id", "")?.takeIf { it.isNotBlank() }?.let { cid ->
                async { runCatching { api.list("/api/admin/courses/$cid/classes") }.getOrNull()?.let { classesByCourse = classesByCourse + (cid to it) } }
            }
            val assignmentJobs = assignments.get()?.firstOrNull()?.str("id", "")?.takeIf { it.isNotBlank() }?.let { aid ->
                async {
                    val detail = runCatching { api.detail("/api/admin/assignments/$aid") }.getOrNull()
                    val subs = runCatching { api.list("/api/admin/assignments/$aid/submissions") }.getOrNull()
                    if (detail != null && subs != null) assignmentSubmissions = assignmentSubmissions + (aid to (detail to subs))
                }
            }
            listOfNotNull(courseJobs, assignmentJobs).forEach { it.await() }
        }

        ready.complete(Unit)
    }
}
