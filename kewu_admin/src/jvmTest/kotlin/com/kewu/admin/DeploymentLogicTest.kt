package com.kewu.admin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 总控端部署逻辑的纯单元测试（无需 GUI / 服务器，CI 可直接跑）。
 * 覆盖：kewu.env 凭证解析、端口可用性检测、部署状态持久化/清除。
 */
class DeploymentLogicTest {

    @Test
    fun `parse env credentials standard`() {
        val text = """
            PORT=8091
            JWT_SECRET=abc
            ADMIN_ACCOUNT=admin
            ADMIN_PASSWORD=oUURSlsqBj
            KEWU_DB_PASSWORD=x=y=z
        """.trimIndent()
        assertEquals("admin" to "oUURSlsqBj", DeploymentState.parseEnvCredentials(text))
    }

    @Test
    fun `parse env credentials with quotes and whitespace`() {
        val text = "ADMIN_ACCOUNT = \"teacher\"\r\nADMIN_PASSWORD = 's3cr3t'\r\n"
        assertEquals("teacher" to "s3cr3t", DeploymentState.parseEnvCredentials(text))
    }

    @Test
    fun `parse env credentials missing password returns null`() {
        assertNull(DeploymentState.parseEnvCredentials("ADMIN_ACCOUNT=admin\n"))
    }

    @Test
    fun `parse env credentials ignores comments`() {
        val text = "# ADMIN_PASSWORD=decoy\nADMIN_ACCOUNT=admin\nADMIN_PASSWORD=real\n"
        assertEquals("admin" to "real", DeploymentState.parseEnvCredentials(text))
    }

    @Test
    fun `bindable port detection`() {
        val socket = java.net.ServerSocket(0)
        val port = socket.localPort
        try {
            assertFalse(DeploymentState.isPortBindable(port), "已占用端口应不可绑定")
            assertTrue(DeploymentState.findAvailablePorts(5).none { it == port }, "可用端口列表不应含被占用端口")
        } finally {
            socket.close()
        }
        assertTrue(DeploymentState.isPortBindable(port), "释放后端口应可绑定")
    }

    @Test
    fun `find available ports returns requested count`() {
        val ports = DeploymentState.findAvailablePorts(4)
        assertEquals(4, ports.size)
        assertEquals(ports.size, ports.distinct().size)
        assertTrue(ports.all { it in 1..65535 })
    }

    @Test
    fun `deployment state save load clear`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "kewu-admin-test-" + System.nanoTime())
        dir.deleteOnExit()
        val state = DeploymentState(dir)
        assertNull(state.load())
        state.save(File("C:/kewu"), 8091)
        val info = state.load()
        assertNotNull(info)
        assertEquals(8091, info.port)
        assertEquals("http://127.0.0.1:8091", info.baseUrl)
        assertTrue(info.serverRoot.replace('\\', '/').endsWith("kewu"))
        state.clear()
        assertNull(state.load())
    }
}
