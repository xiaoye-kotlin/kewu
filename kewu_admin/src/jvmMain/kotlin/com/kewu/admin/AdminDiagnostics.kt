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

    private val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")

    @Synchronized
    fun record(section: String, detail: String) {
        runCatching {
            if (!logDir.exists()) logDir.mkdirs()
            logFile.appendText("[${timestamp.format(Date())}] [$section]\n$detail\n\n")
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
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            recordError("UNCAUGHT", e, mapOf("thread" to thread.name))
            previous?.uncaughtException(thread, e)
        }
    }
}
