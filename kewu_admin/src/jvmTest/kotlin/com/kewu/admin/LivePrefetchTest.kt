package com.kewu.admin

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 「每个页面都能预加载」真实联调测试（§83）。
 *
 * 直接调用生产代码 [AdminPrefetch.begin] + [AdminPrefetch.awaitReady]，然后逐页断言缓存已填充：
 * 概览 / 健康 / 密钥 / 用户 / 录音 / OBS / 课程 / 作业 / 签到 / 系统快照 / 课堂纪要，
 * 以及默认选中项的二级数据（首个课程的班级、首个作业的提交）。
 *
 * 默认跳过；需要真实服务器时设置：
 *   KEWU_LIVE_BASE=http://8.136.151.139:8080 KEWU_LIVE_USER=admin KEWU_LIVE_PWD=xxx
 */
class LivePrefetchTest {

    private fun env(name: String): String? =
        System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: System.getProperty(name)?.takeIf { it.isNotBlank() }

    private val base = env("KEWU_LIVE_BASE")
    private val user = env("KEWU_LIVE_USER") ?: "admin"
    private val pwd = env("KEWU_LIVE_PWD")

    @Test
    fun `every page gets prefetched`() {
        assumeTrue("未设置 KEWU_LIVE_BASE，跳过真实预加载联调", base != null)
        assumeTrue("未设置 KEWU_LIVE_PWD，跳过真实预加载联调", pwd != null)

        val api = AdminApi(base!!)
        try {
            runBlocking {
                api.login(user, pwd!!)
                AdminPrefetch.clearAll()
                AdminPrefetch.begin(api)
                AdminPrefetch.awaitReady(180_000)

                val report = mutableListOf<String>()
                fun check(name: String, ok: Boolean, detail: String = "") {
                    report += (if (ok) "  ✓ " else "  ✗ ") + name + if (detail.isNotBlank()) " ($detail)" else ""
                }

                val overview = AdminPrefetch.overview.get()
                val health = AdminPrefetch.health.get()
                val config = AdminPrefetch.config.get()
                val users = AdminPrefetch.users.get()
                val recordings = AdminPrefetch.recordings.get()
                val obs = AdminPrefetch.obsRecordings.get()
                val courses = AdminPrefetch.courses.get()
                val assignments = AdminPrefetch.assignments.get()
                val attendance = AdminPrefetch.attendance.get()
                val snapshot = AdminPrefetch.serverSnapshot.get()
                val minutes = AdminPrefetch.minutes.get()

                check("运行概览 overview", overview != null)
                check("健康 health", health != null)
                check("密钥配置 config", config != null)
                check("用户管理 users", users != null, "total=${users?.total}")
                check("录音资源 recordings", recordings != null, "total=${recordings?.total}")
                check("录音资源 OBS", obs != null, "count=${obs?.size}")
                check("课程与班级 courses", courses != null, "count=${courses?.size}")
                check("作业与批阅 assignments", assignments != null, "count=${assignments?.size}")
                check("签到管理 attendance", attendance != null, "count=${attendance?.size}")
                check("服务器状态 snapshot", snapshot != null, "source=${snapshot?.monitorSource}")
                check("课堂纪要 minutes", minutes != null, "total=${minutes?.total}")

                val firstCourseId = courses?.firstOrNull()?.str("id", "")
                val classes = firstCourseId?.takeIf { it.isNotBlank() }?.let { AdminPrefetch.classesFor(it) }
                check("课程二级：首个课程班级", classes != null, "courseId=$firstCourseId")

                val firstAssignmentId = assignments?.firstOrNull()?.str("id", "")
                val subs = firstAssignmentId?.takeIf { it.isNotBlank() }?.let { AdminPrefetch.submissionsFor(it) }
                check("作业二级：首个作业提交", subs != null, "assignmentId=$firstAssignmentId")

                println("=== 预加载结果 ===")
                report.forEach { println(it) }

                // 系统快照必须真的采到数据（用户报障的核心）
                assertNotNull(snapshot, "服务器状态快照必须预加载成功")
                assertTrue(snapshot.cpuModel.isNotBlank(), "CPU 型号不应为空")
                assertTrue(snapshot.totalRamBytes > 0, "物理内存应大于 0")
                assertTrue(snapshot.adminHeapMaxBytes > 0, "JVM 堆上限应大于 0（java.management 可用）")

                val missing = listOfNotNull(
                    "overview".takeIf { overview == null },
                    "health".takeIf { health == null },
                    "config".takeIf { config == null },
                    "users".takeIf { users == null },
                    "recordings".takeIf { recordings == null },
                    "obs".takeIf { obs == null },
                    "courses".takeIf { courses == null },
                    "assignments".takeIf { assignments == null },
                    "attendance".takeIf { attendance == null },
                    "minutes".takeIf { minutes == null },
                )
                assertTrue(missing.isEmpty(), "以下页面未预加载成功：$missing")
            }
        } finally {
            AdminPrefetch.clearAll()
            api.close()
        }
    }
}
