package com.kewu.admin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

class DeploymentException(message: String) : Exception(message)

/**
 * 一键部署只在「服务器本机 + 管理员权限」下执行：
 * 1. 从 GitHub 下载 kewu 源码包并解出 kewu-8080；
 * 2. 用服务器自带 jdk21 执行 shadowJar；
 * 3. 将产物放到服务器 kewu-server 的 kewu-8080-new.jar；
 * 4. 调用服务器既有 deploy-8080.ps1 做 8090 试启动、切换、验证和失败回滚。
 *
 * 部署脚本不包含数据库口令/OBS 密钥；环境变量仍由服务器既有脚本从 kewu.env 读取。
 */
class DeploymentService {
    val defaultBackendZipUrl: String = System.getProperty("KEWU_BACKEND_ZIP_URL")
        ?: System.getenv("KEWU_BACKEND_ZIP_URL")
        ?: "https://codeload.github.com/xiaoye-kotlin/kewu/zip/refs/heads/main"

    fun detectServerRoot(): String? {
        val explicit = System.getProperty("KEWU_SERVER_ROOT") ?: System.getenv("KEWU_SERVER_ROOT")
        if (!explicit.isNullOrBlank() && looksLikeServerRoot(File(explicit))) return File(explicit).absolutePath

        val cwd = File(".").absoluteFile
        generateSequence(cwd) { it.parentFile }.take(5).forEach { dir ->
            if (looksLikeServerRoot(dir)) return dir.absolutePath
        }

        val candidates = listOf(
            "C:/kewu-server", "D:/kewu-server",
            "C:/wwwroot/kewu-server", "D:/wwwroot/kewu-server",
            "C:/web/kewu-server", "D:/web/kewu-server",
            "C:/inetpub/wwwroot/kewu-server", "D:/inetpub/wwwroot/kewu-server",
        )
        return candidates.firstOrNull { looksLikeServerRoot(File(it)) }
    }

    private fun looksLikeServerRoot(dir: File): Boolean =
        dir.isDirectory && File(dir, "deploy-8080.ps1").isFile && File(dir, "kewu-8080.jar").isFile

    suspend fun deploy(
        serverRootPath: String,
        backendZipUrl: String = defaultBackendZipUrl,
        onLog: (String) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val serverRoot = File(serverRootPath).absoluteFile
        if (!looksLikeServerRoot(serverRoot)) {
            return@withContext Result.failure(DeploymentException("未找到服务器后端目录：$serverRoot（需要 deploy-8080.ps1 和 kewu-8080.jar）"))
        }
        val workDir = File(
            System.getProperty("java.io.tmpdir"),
            "kewu-admin-deploy-" + SimpleDateFormat("yyyyMMdd-HHmmss").format(Date()),
        )
        if (!workDir.mkdirs() && !workDir.isDirectory) {
            return@withContext Result.failure(DeploymentException("无法创建部署临时目录：${workDir.absolutePath}"))
        }
        val script = File(workDir, "deploy-backend.ps1")
        script.writeText(deployScript, Charsets.UTF_8)

        val command = listOf(
            "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.absolutePath,
            "-ServerRoot", serverRoot.absolutePath,
            "-ZipUrl", backendZipUrl,
        )

        try {
            val process = ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    if (line.isNotBlank()) onLog(line)
                    line = reader.readLine()
                }
            }
            val exit = process.waitFor()
            if (exit == 0) Result.success(Unit)
            else Result.failure(DeploymentException("后端部署失败（退出码 $exit），请查看上方日志"))
        } catch (e: Exception) {
            Result.failure(DeploymentException(e.message ?: "后端部署进程启动失败"))
        }
    }

    private val deployScript: String =
        javaClass.classLoader.getResourceAsStream("deploy-backend.ps1")?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw IllegalStateException("deploy-backend.ps1 resource is missing")
}
