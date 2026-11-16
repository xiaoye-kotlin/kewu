package com.kewu.admin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Properties

/**
 * 总控端「本机部署状态」的持久化与探测（2026-11-17，1.0.16）。
 *
 * 落盘位置固定为 `<user.home>/kewu-admin/deployment.properties`，与诊断日志同目录。
 * 保存后端目录、实际监听端口和健康检查地址，供下次启动时**自动跳过部署界面**
 * 直接进登录页；同时把本机 `kewu.env` 里的随机管理员账号密码暴露给登录页，
 * 解决「部署成功但不知道随机密码 → 登录不上」的老问题。
 */
data class DeploymentInfo(
    val serverRoot: String,
    val port: Int,
    val baseUrl: String,
    val deployedAt: Long,
)

class DeploymentState(private val stateDir: File = File(System.getProperty("user.home"), "kewu-admin")) {

    private val stateFile: File get() = File(stateDir, "deployment.properties")

    fun load(): DeploymentInfo? {
        if (!stateFile.isFile) return null
        return runCatching {
            val props = Properties()
            stateFile.inputStream().use { props.load(it) }
            val root = props.getProperty("serverRoot", "").trim()
            val port = props.getProperty("port", "").trim().toIntOrNull() ?: return null
            if (root.isEmpty() || port !in 1..65535) return null
            DeploymentInfo(
                serverRoot = root,
                port = port,
                baseUrl = props.getProperty("baseUrl", "http://127.0.0.1:$port").trim(),
                deployedAt = props.getProperty("deployedAt", "0").toLongOrNull() ?: 0L,
            )
        }.getOrNull()
    }

    fun save(serverRoot: File, port: Int) {
        if (!stateDir.exists()) stateDir.mkdirs()
        val props = Properties()
        props.setProperty("serverRoot", serverRoot.absolutePath)
        props.setProperty("port", port.toString())
        props.setProperty("baseUrl", "http://127.0.0.1:$port")
        props.setProperty("deployedAt", System.currentTimeMillis().toString())
        stateFile.outputStream().use { props.store(it, "kewu-admin local deployment state") }
    }

    fun clear() {
        runCatching { if (stateFile.isFile) stateFile.delete() }
    }

    /** 从已保存部署目录的 kewu.env 读取管理员账号/密码；失败返回 null（绝不抛异常影响 UI）。 */
    fun adminCredentials(): Pair<String, String>? {
        val root = load()?.serverRoot ?: return null
        return readEnvCredentials(File(root, "kewu.env"))
    }

    companion object {
        /** 解析 kewu.env 文本里的 ADMIN_ACCOUNT / ADMIN_PASSWORD（兼容 CRLF、引号、前后空格）。 */
        fun parseEnvCredentials(text: String): Pair<String, String>? {
            var account: String? = null
            var password: String? = null
            text.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
                val idx = trimmed.indexOf('=')
                if (idx <= 0) return@forEach
                val key = trimmed.substring(0, idx).trim()
                val value = trimmed.substring(idx + 1).trim().trim('"', '\'')
                when (key) {
                    "ADMIN_ACCOUNT" -> account = value
                    "ADMIN_PASSWORD" -> password = value
                }
            }
            return if (!account.isNullOrBlank() && !password.isNullOrBlank()) account to password else null
        }

        fun readEnvCredentials(envFile: File): Pair<String, String>? {
            if (!envFile.isFile) return null
            return runCatching { parseEnvCredentials(envFile.readText(Charsets.UTF_8)) }.getOrNull()
        }

        /** TCP 层判断端口是否已被监听（本地探测用于「已部署」判定，比 HTTP 更省时）。 */
        fun isPortOpen(port: Int, host: String = "127.0.0.1", timeoutMs: Int = 700): Boolean =
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    true
                }
            }.getOrDefault(false)

        /** 端口能否被绑定（0 = 空闲）；排除 TIME_WAIT 干扰，直接尝试 bind。 */
        fun isPortBindable(port: Int): Boolean = runCatching {
            ServerSocket().use { it.bind(InetSocketAddress("0.0.0.0", port)) ; true }
        }.getOrDefault(false)

        /** 动态检测一组可用端口：优先 8080，其次 8091-8095、18080-18085，最后补足到 count 个。 */
        fun findAvailablePorts(count: Int = 6): List<Int> {
            val preferred = listOf(8080, 8091, 8092, 8093, 8094, 8095) +
                (18080..18090) + (8200..8210)
            return preferred.distinct().filter { isPortBindable(it) }.take(count.coerceAtLeast(1))
        }
    }
}

/**
 * 启动自检：若本机（或已保存部署目录）的后端健康检查可达，则视为「已完成部署」。
 * 探测端口优先级：持久化端口 → kewu.env 里的 PORT → 8080（兼容队友旧版部署）。
 */
object DeploymentDetector {
    suspend fun detect(state: DeploymentState = DeploymentState()): DeploymentInfo? = withContext(Dispatchers.IO) {
        val cached = state.load()
        val ports = linkedSetOf<Int>()
        cached?.port?.let { ports += it }
        cached?.serverRoot?.let { root ->
            val envPort = readPortFromEnv(File(root, "kewu.env"))
            if (envPort != null) ports += envPort
        }
        ports += 8080

        for (port in ports) {
            if (probeHealth(port)) {
                val root = cached?.serverRoot?.takeIf { File(it).isDirectory }
                    ?: DeploymentService().detectServerRoot()
                if (!root.isNullOrBlank()) state.save(File(root), port)
                return@withContext DeploymentInfo(
                    serverRoot = root ?: "",
                    port = port,
                    baseUrl = "http://127.0.0.1:$port",
                    deployedAt = cached?.deployedAt ?: System.currentTimeMillis(),
                )
            }
        }
        null
    }

    /** 健康检查（短超时，避免冷启动时界面卡很久）。 */
    fun probeHealth(port: Int, timeoutMs: Int = 1500): Boolean = runCatching {
        val conn = URL("http://127.0.0.1:$port/api/health").openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.requestMethod = "GET"
        conn.useCaches = false
        val ok = conn.responseCode in 200..299
        if (ok) {
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            body.contains("ok", ignoreCase = true)
        } else false
    }.getOrDefault(false)

    private fun readPortFrom(envFile: File): Int? {
        if (!envFile.isFile) return null
        return runCatching {
            envFile.readLines().firstOrNull { it.trim().startsWith("PORT=") }
                ?.substringAfter('=')?.trim()?.toIntOrNull()
        }.getOrNull()
    }

    private fun readPortFromEnv(envFile: File): Int? = readPortFrom(envFile)
}
