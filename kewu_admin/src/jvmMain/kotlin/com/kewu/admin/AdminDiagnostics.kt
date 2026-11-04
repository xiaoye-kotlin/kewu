package com.kewu.admin

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 总控端本地诊断日志：所有网络错误、地址探测结果、未捕获异常都会追加到
 * `<用户主目录>/kewu-admin/logs/diagnostic.log`，错误提示里附带该路径，
 * 便于用户反馈或远程排查（服务器侧还可经 FTP 拉取后端日志对照）。
 */
object AdminDiagnostics {
    val logDir: File = File(System.getProperty("user.home"), "kewu-admin" + File.separator + "logs")
    val logFile: File get() = File(logDir, "diagnostic.log")

    /** 若在服务器本机运行，日志同时镜像到 FTP 可见的 `<后端目录>\logs\admin-diagnostic.log`，便于远程排查。 */
    private val serverMirror: File? by lazy { findServerMirror() }

    private fun findServerMirror(): File? {
        val explicit = System.getProperty("KEWU_SERVER_ROOT") ?: System.getenv("KEWU_SERVER_ROOT")
        val candidates = mutableListOf<File>()
        if (!explicit.isNullOrBlank()) candidates += File(explicit)
        for (basePath in listOf("C:/wwwroot", "D:/wwwroot")) {
            File(basePath).listFiles { f -> f.isDirectory }?.forEach { child ->
                candidates += child
                candidates += File(child, "kewu-server")
            }
        }
        candidates += listOf("C:/kewu-server", "D:/kewu-server").map(::File)
        val serverRoot = candidates.firstOrNull { File(it, "deploy-8080.ps1").isFile } ?: return null
        return File(serverRoot, "logs" + File.separator + "admin-diagnostic.log")
    }

    private val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")

    @Synchronized
    fun record(section: String, detail: String) {
        val entry = "[${timestamp.format(Date())}] [$section]\n$detail\n\n"
        runCatching {
            if (!logDir.exists()) logDir.mkdirs()
            logFile.appendText(entry)
        }
        serverMirror?.let { mirror ->
            runCatching {
                mirror.parentFile?.mkdirs()
                mirror.appendText(entry)
            }
        }
    }

    fun recordError(section: String, e: Throwable, context: Map<String, String?> = emptyMap()) {
        val ctx = context.filterValues { !it.isNullOrBlank() }
            .entries.joinToString("\n") { (k, v) -> "  $k: $v" }
        val stack = e.stackTraceToString()
        record(section, (if (ctx.isNotEmpty()) ctx + "\n" else "") +
            "  exception: ${e.javaClass.name}: ${e.message}\n$stack")
    }

    /** 全局兜底：UI 线程等未捕获异常也落盘，避免崩溃无迹可查。 */
    fun installGlobalHandler() {
        record(
            "APP_START",
            "user.home=${System.getProperty("user.home")}\n  os=${System.getProperty("os.name")}\n" +
                "  http.proxyHost=${System.getProperty("http.proxyHost")}\n  https.proxyHost=${System.getProperty("https.proxyHost")}\n" +
                "  serverMirror=${serverMirror?.absolutePath ?: "not detected"}\n  localLog=${logFile.absolutePath}",
        )
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            recordError("UNCAUGHT", e, mapOf("thread" to thread.name))
            previous?.uncaughtException(thread, e)
        }
    }
}
