package com.kewu.admin

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真实环境联调测试（2026-11-17 §82）。
 *
 * 用真实的 AdminApi（含 120s 超时 + 超时重试）打真实服务器，验证「登录连不上」修复。
 * 默认跳过（CI/离线不跑）；仅在设置了系统属性/env 时执行：
 *
 *   KEWU_LIVE_BASE=http://8.136.151.139:8080 KEWU_LIVE_USER=admin KEWU_LIVE_PWD=xxx \
 *     ./gradlew jvmTest --tests 'com.kewu.admin.LiveLoginTest'
 *
 * 该测试直接覆盖用户报障场景：低配服务器冷启动首次登录很慢，旧 30s 超时会失败。
 */
class LiveLoginTest {

    private fun env(name: String): String? =
        System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: System.getProperty(name)?.takeIf { it.isNotBlank() }

    private val base = env("KEWU_LIVE_BASE")
    private val user = env("KEWU_LIVE_USER") ?: "admin"
    private val pwd = env("KEWU_LIVE_PWD")

    @Test
    fun `real login succeeds against live server`() {
        assumeTrue("未设置 KEWU_LIVE_BASE，跳过真实联调", base != null)
        assumeTrue("未设置 KEWU_LIVE_PWD，跳过真实联调", pwd != null)

        val api = AdminApi(base!!)
        try {
            runBlocking {
                val t0 = System.currentTimeMillis()
                val session = api.login(user, pwd!!)
                val cost = System.currentTimeMillis() - t0
                println("live login OK in ${cost}ms, user=${session.username}, tokenLen=${session.token.length}")
                assertTrue(session.token.isNotBlank(), "应拿到 token")
                assertEquals(user, session.username)
                // 端口解析正确性
                assertEquals(8080, api.currentPort(), "应从 baseUrl 解析出 8080")
                // 登录后关键接口也验证一次，确保预加载链路可用
                val ov = api.overview()
                assertTrue(ov.containsKey("userCount"), "概览应含 userCount")
                val h = api.health()
                assertTrue(h.containsKey("backend"), "健康应含 backend")
                println("live overview/health OK")
            }
        } finally {
            api.close()
        }
    }

    @Test
    fun `wrong password returns clear business error not timeout`() {
        assumeTrue("未设置 KEWU_LIVE_BASE，跳过真实联调", base != null)
        val api = AdminApi(base!!)
        try {
            runBlocking {
                val err = runCatching { api.login(user, "definitely-wrong-password") }.exceptionOrNull()
                println("wrong password error: ${err?.message}")
                assertTrue(err is AdminApiException, "应为业务异常")
                val msg = err.message.orEmpty()
                assertTrue(
                    msg.contains("密码", true) || msg.contains("账号", true) || msg.contains("错误", true) || msg.contains("不存在", true),
                    "错误信息应说明账号/密码问题，实际=$msg",
                )
                assertTrue(!msg.contains("无法连接"), "不应误报为连接失败，实际=$msg")
            }
        } finally {
            api.close()
        }
    }
}
