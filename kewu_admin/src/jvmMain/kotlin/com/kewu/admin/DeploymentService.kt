package com.kewu.admin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

class DeploymentException(message: String) : Exception(message)

/**
 * 一键部署只在「服务器本机 + 管理员权限」下执行。两种模式：
 *
 * - **预构建包部署（默认）**：从 GitHub Release 下载开发机构建好的 kewu-8080.jar，
 *   服务器上完全不跑 Gradle，只做 8090 试启动、切换、验证和失败回滚。
 * - **在线构建（备选）**：从 GitHub 下载源码包，在服务器本机执行 shadowJar 后再走同一套安全部署。
 *
 * 全新服务器首装会自动补 JDK 21、kewu.env、deploy-8080.ps1（内嵌模板）和开机启动任务。
 * 部署脚本不包含数据库口令/OBS 密钥；环境变量仍由服务器既有脚本从 kewu.env 读取。
 */
class DeploymentService {
    val defaultBackendZipUrl: String = System.getProperty("KEWU_BACKEND_ZIP_URL")
        ?: System.getenv("KEWU_BACKEND_ZIP_URL")
        ?: "https://codeload.github.com/xiaoye-kotlin/kewu/zip/refs/heads/main"

    val defaultPrebuiltJarUrl: String = System.getProperty("KEWU_PREBUILT_JAR_URL")
        ?: System.getenv("KEWU_PREBUILT_JAR_URL")
        ?: "https://github.com/xiaoye-kotlin/kewu/releases/download/backend-latest/kewu-8080.jar"

    fun detectServerRoot(): String? {
        val explicit = System.getProperty("KEWU_SERVER_ROOT") ?: System.getenv("KEWU_SERVER_ROOT")
        if (!explicit.isNullOrBlank() && looksLikeServerRoot(File(explicit))) return File(explicit).absolutePath

        val cwd = File(".").absoluteFile
        generateSequence(cwd) { it.parentFile }.take(5).forEach { dir ->
            if (looksLikeServerRoot(dir)) return dir.absolutePath
        }

        // 面板类服务器（如小皮/phpStudy）常把站点放在 C:/wwwroot/<域名>/ 下，向下扫两层。
        for (basePath in listOf("C:/wwwroot", "D:/wwwroot")) {
            val base = File(basePath)
            val children = base.listFiles { f -> f.isDirectory } ?: continue
            for (child in children) {
                if (looksLikeServerRoot(child)) return child.absolutePath
                val nested = File(child, "kewu-server")
                if (looksLikeServerRoot(nested)) return nested.absolutePath
            }
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
        prebuiltJarUrl: String = defaultPrebuiltJarUrl,
        backendZipUrl: String = defaultBackendZipUrl,
        onlineBuild: Boolean = false,
        onLog: (String) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val serverRoot = File(serverRootPath).absoluteFile
        // 目录可以不存在（全新服务器首次搭建）：不存在则创建，不再要求预先放好 deploy-8080.ps1 / kewu-8080.jar。
        if (!serverRoot.exists() && !serverRoot.mkdirs()) {
            return@withContext Result.failure(DeploymentException("无法创建服务器后端目录：${serverRoot.absolutePath}"))
        }
        if (!serverRoot.isDirectory) {
            return@withContext Result.failure(DeploymentException("服务器后端目录不是文件夹：${serverRoot.absolutePath}"))
        }
        val workDir = File(
            System.getProperty("java.io.tmpdir"),
            "kewu-admin-deploy-" + SimpleDateFormat("yyyyMMdd-HHmmss").format(Date()),
        )
        if (!workDir.mkdirs() && !workDir.isDirectory) {
            return@withContext Result.failure(DeploymentException("无法创建部署临时目录：${workDir.absolutePath}"))
        }
        val script = File(workDir, "deploy-backend.ps1")
        // 必须带 UTF-8 BOM 写出：Windows PowerShell 5.1 对无 BOM 文件按系统 ANSI(GBK) 解码，
        // 脚本里的中文注释乱码后可能注入引号字符、破坏 here-string，导致 init 脚本等生成物损坏。
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        script.writeBytes(bom + deployScript.toByteArray(Charsets.UTF_8))
        // 安全部署脚本模板同样内嵌（含 BOM 写出），全新服务器不再依赖 GitHub 源码包提供它。
        val template = File(workDir, "deploy-8080-template.ps1")
        template.writeBytes(bom + deployTemplate.toByteArray(Charsets.UTF_8))

        val command = mutableListOf(
            "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.absolutePath,
            "-ServerRoot", serverRoot.absolutePath,
            "-ZipUrl", backendZipUrl,
            "-PrebuiltJarUrl", prebuiltJarUrl,
            "-DeployTemplate", template.absolutePath,
        )
        if (onlineBuild) command.add("-OnlineBuild")

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

    private val deployTemplate: String =
        javaClass.classLoader.getResourceAsStream("deploy-8080-template.ps1")?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw IllegalStateException("deploy-8080-template.ps1 resource is missing")
}
