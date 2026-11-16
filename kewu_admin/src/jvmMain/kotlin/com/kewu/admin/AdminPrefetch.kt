package com.kewu.admin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 登录后全局数据预加载缓存（2026-11-17，1.0.17）。
 *
 * 目标：打开总控端、登录成功后立刻在后台**并行**拉取所有界面首屏数据，
 * 用户切到任意 tab 时直接读缓存即时展示，不再每次进页面才发请求等待。
 * 尤其系统信息 / 服务器状态这类耗时的采样提前完成，避免进页面干等。
 *
 * 用法：
 * - AdminApp 登录成功后调用 [begin]（内部并发拉取，[awaitReady] 可等待第一批完成）。
 * - 各页面进入时先读这里的值；缓存新鲜（[FRESH_MS] 内）则跳过本页首屏请求，
 *   之后仍保留各页面自己的定时刷新与手动刷新。
 */
object AdminPrefetch {
    const val FRESH_MS: Long = 60_000L

    class Slot<T> {
        @Volatile var value: T? = null
            private set
        @Volatile var at: Long = 0L
            private set

        fun set(v: T) { value = v; at = System.currentTimeMillis() }
        /** 缓存命中且未过期。 */
        fun fresh(maxAgeMs: Long = FRESH_MS): T? = value?.takeIf { System.currentTimeMillis() - at < maxAgeMs }
        /** 不判断时效，用于首屏先展示旧值。 */
        fun peek(): T? = value
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
    val serverSnapshot = Slot<ServerRuntimeSnapshot>()

    private var ready = CompletableDeferred<Unit>()
    private val started = AtomicBoolean(false)

    /** 登录成功调用；重复调用安全。第一批数据（除 OBS 全量外）就绪后 [awaitReady] 返回。 */
    fun begin(api: AdminApi) {
        if (!started.compareAndSet(false, true)) return
        ready = CompletableDeferred<Unit>()
        GlobalScope.launch(Dispatchers.IO) {
            prefetch(api)
        }
    }

    /** 页面首屏用：最多等 [timeoutMs]，让并发预加载先填好缓存，避免和页面各自请求重复/竞态。 */
    suspend fun awaitReady(timeoutMs: Long = 15_000) {
        withTimeoutOrNull(timeoutMs) { ready.await() }
    }

    fun isReady(): Boolean = ready.isCompleted

    /** 退出登录/切换账号时清空。 */
    fun clearAll() {
        overview.clear(); health.clear(); config.clear(); users.clear(); recordings.clear()
        obsRecordings.clear(); courses.clear(); assignments.clear(); attendance.clear(); serverSnapshot.clear()
        started.set(false)
    }

    private suspend fun prefetch(api: AdminApi) = withContext(Dispatchers.IO) {
        // 第一批：无参数、各页面首屏必需、相对轻量的请求，全部并发。
        coroutineScope {
            val jobs = listOf(
                async { runCatching { api.overview() }.getOrNull()?.let { overview.set(it) } },
                async { runCatching { api.health() }.getOrNull()?.let { health.set(it) } },
                async { runCatching { api.configItems() }.getOrNull()?.let { config.set(it) } },
                async { runCatching { api.page("/api/admin/users", mapOf("page" to "1", "size" to "20")) }.getOrNull()?.let { users.set(it) } },
                async { runCatching { api.page("/api/admin/recordings", mapOf("page" to "1", "size" to "1000")) }.getOrNull()?.let { recordings.set(it) } },
                async { runCatching { api.list("/api/admin/courses") }.getOrNull()?.let { courses.set(it) } },
                async { runCatching { api.list("/api/admin/assignments") }.getOrNull()?.let { assignments.set(it) } },
                async { runCatching { api.list("/api/admin/attendance") }.getOrNull()?.let { attendance.set(it) } },
                async { runCatching { ServerMonitor.snapshot() }.getOrNull()?.let { serverSnapshot.set(it) } },
            )
            jobs.forEach { it.await() }
        }
        ready.complete(Unit)

        // 第二批：OBS 全量分页列举（跨公网、最重），后台慢慢拉，不阻塞首屏就绪。
        runCatching { api.obsRecordings() }.getOrNull()?.let { obsRecordings.set(it) }
    }
}
