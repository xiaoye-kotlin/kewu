package com.kewu.admin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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

    // 首选通道：codeload 分发仓库（该域名在服务器上已多次验证可通；Release 直链作兜底）。
    val defaultPrebuiltZipUrl: String = System.getProperty("KEWU_PREBUILT_ZIP_URL")
        ?: System.getenv("KEWU_PREBUILT_ZIP_URL")
        ?: "https://codeload.github.com/xiaoye-kotlin/kewu-backend-dist/zip/refs/heads/main"

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
            "-PrebuiltZipUrl", defaultPrebuiltZipUrl,
            "-DeployTemplate", template.absolutePath,
        )
        if (onlineBuild) command.add("-OnlineBuild")

        try {
            val process = ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()

            // PowerShell 部署进程退出后，被后台常驻拉起的后端 Java 子进程（Start-Process 已将其 stdout/stderr
            // 重定向到文件）在某些 Windows 环境下仍可能握着 stdout 管道，导致 readLine 永远读不到 EOF、
            // deploy() 永不返回、界面卡在「部署中」。这里不再无限等待 EOF：看到终态标记并静置一小段后，
            // 直接强行回收 PowerShell 收集进程。常驻后端 Java 由 Start-Process 独立拉起，不在同一进程树，
            // 不会被误杀。
            val doneMarker = "BACKEND DEPLOY DONE"
            val failMarker = "[FAIL]"
            val seenDone = AtomicBoolean(false)
            val seenFail = AtomicBoolean(false)
            val lastLineAt = AtomicLong(System.currentTimeMillis())
            val tail = Collections.synchronizedList(mutableListOf<String>())

            val readerThread = Thread({
                try {
                    process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        if (line.isNotBlank()) {
                            onLog(line)
                            lastLineAt.set(System.currentTimeMillis())
                            tail.add(line)
                            if (tail.size > 80) tail.removeAt(0)
                            if (line.contains(doneMarker)) seenDone.set(true)
                            if (line.contains(failMarker)) seenFail.set(true)
                        }
                    }
                } catch (_: Exception) { /* 管道被强行关闭 */ }
            }, "deploy-log-reader").apply { isDaemon = true; start() }

            // 终态标记出现且静置 8s 仍无新输出（说明脚本已结束、管道多半被常驻 Java 占着），
            // 或整体超过 10 分钟硬上限，则跳出等待并强行回收 PowerShell 进程。
            val absoluteDeadline = System.currentTimeMillis() + 10L * 60_000
            while (readerThread.isAlive && System.currentTimeMillis() < absoluteDeadline) {
                val finished = seenDone.get() || seenFail.get()
                val quietFor = System.currentTimeMillis() - lastLineAt.get()
                if (finished && quietFor > 8_000) break
                Thread.sleep(500)
            }

            var exit = -1
            if (readerThread.isAlive) {
                try { process.destroyForcibly() } catch (_: Exception) {}
                readerThread.join(8_000)
                try { exit = process.exitValue() } catch (_: Exception) { exit = -1 }
            } else {
                exit = process.waitFor()
            }

            when {
                seenDone.get() || exit == 0 -> Result.success(Unit)
                seenFail.get() -> Result.failure(DeploymentException(tail.lastOrNull() ?: "部署脚本报告失败，请查看上方日志"))
                exit != 0 -> Result.failure(DeploymentException("后端部署失败（退出码 $exit），请查看上方日志"))
                else -> Result.failure(DeploymentException("后端部署未完成，请查看上方日志"))
            }
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
