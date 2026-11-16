package com.kewu.admin

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 启动自检的健康探测测试（2026-11-17）。
 * 复现服务器装有 CCProxy 等系统代理时，openConnection() 默认走代理导致本机探测失败的场景，
 * 验证 DeploymentDetector.probeHealth 强制 Proxy.NO_PROXY 后仍能命中本机后端。
 */
class DeploymentDetectionTest {
    private var server: HttpServer? = null
    private val oldProxyHost = System.getProperty("http.proxyHost")
    private val oldProxyPort = System.getProperty("http.proxyPort")

    @AfterTest
    fun tearDown() {
        server?.stop(0)
        restoreProxy()
    }

    private fun restoreProxy() {
        if (oldProxyHost == null) System.clearProperty("http.proxyHost") else System.setProperty("http.proxyHost", oldProxyHost)
        if (oldProxyPort == null) System.clearProperty("http.proxyPort") else System.setProperty("http.proxyPort", oldProxyPort)
    }

    private fun startHealthServer(body: String = "ok"): Int {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/api/health") { ex ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        http.start()
        server = http
        return http.address.port
    }

    @Test
    fun `probeHealth ignores bogus system proxy (NO_PROXY fix)`() {
        val port = startHealthServer("ok")
        // 模拟服务器系统代理：指向一个绝不响应的地址，默认 openConnection() 会走它而失败。
        System.setProperty("http.proxyHost", "127.0.0.1")
        System.setProperty("http.proxyPort", "1")

        assertTrue(DeploymentDetector.probeHealth(port), "即使配了错误系统代理，也必须直连本机 health 成功")
    }

    @Test
    fun `probeHealth rejects unhealthy body and closed port`() {
        val port = startHealthServer("degraded")
        assertFalse(DeploymentDetector.probeHealth(port), "health 体不含 ok 应判失败")

        server?.stop(0)
        assertFalse(DeploymentDetector.probeHealth(port), "端口关闭后应判失败")
    }
}
