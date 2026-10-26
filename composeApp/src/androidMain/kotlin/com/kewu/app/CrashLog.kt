package com.kewu.app

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 未捕获异常落盘（2026-10-26，排查「某些机型闪退、开发机不复现」用）。
 *
 * 闪退时把机型 / 系统版本 / 完整堆栈写到
 * `/sdcard/Android/data/com.kewu.app/files/crash/crash-<时间戳>.txt`（最多留 5 份），
 * 同时以 tag `KewuCrash` 打到 logcat，然后交还系统默认处理（照常闪退，不吞异常）。
 * 取日志：`adb pull /sdcard/Android/data/com.kewu.app/files/crash` 或 `adb logcat -s KewuCrash AndroidRuntime`。
 */
object CrashLog {
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val text = buildString {
                    appendLine("time=${System.currentTimeMillis()} thread=${thread.name}")
                    appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
                    appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} rom=${Build.DISPLAY}")
                    appendLine()
                    append(sw.toString())
                }
                Log.e("KewuCrash", text)
                val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "crash").apply { mkdirs() }
                File(dir, "crash-${System.currentTimeMillis()}.txt").writeText(text)
                dir.listFiles()?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
            } catch (_: Throwable) {
                // 记录失败也不能影响系统默认的崩溃处理
            }
            previous?.uncaughtException(thread, e)
        }
        uploadPending(context)
    }

    /**
     * 启动时上报上次的崩溃（2026-10-26：用户的手机上才闪退、连不上电脑，只能让 App 自己把原因传回来）：
     *   ① 上面写下的 Java 崩溃文件；
     *   ② 系统记录的进程退出原因（Android 11+ ApplicationExitInfo）：原生崩溃（如液态玻璃 RenderThread SIGSEGV）
     *      Java 层抓不到，只能从这里拿到墓碑摘要。
     * 上报到 POST /api/crash-reports，成功后删除本地文件 / 推进已报游标。
     */
    private fun uploadPending(context: Context) {
        Thread {
            try {
                val reports = mutableListOf<Triple<String, String, String>>() // reason, detail, happenedAt
                val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "crash")
                val files = dir.listFiles()?.filter { it.name.startsWith("crash-") }.orEmpty()
                files.forEach { f -> reports += Triple("JAVA_CRASH", f.readText(), f.name.removePrefix("crash-").removeSuffix(".txt")) }
                val prefs = context.getSharedPreferences("kewu_crash", Context.MODE_PRIVATE)
                var cursor = prefs.getLong("exit_cursor", 0L)
                var newest = cursor
                if (Build.VERSION.SDK_INT >= 30) {
                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    am.getHistoricalProcessExitReasons(context.packageName, 0, 10).forEach { info ->
                        if (info.timestamp <= cursor) return@forEach
                        newest = maxOf(newest, info.timestamp)
                        val r = info.reason
                        if (r == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ||
                            r == android.app.ApplicationExitInfo.REASON_CRASH ||
                            r == android.app.ApplicationExitInfo.REASON_ANR
                        ) {
                            val trace = runCatching {
                                info.traceInputStream?.use { ins -> ins.readBytes().take(48_000).toByteArray() }
                                    ?.let { b -> String(b, Charsets.ISO_8859_1).filter { c -> c == '\n' || c in ' '..'~' } }
                            }.getOrNull().orEmpty()
                            val reasonName = when (r) {
                                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
                                android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
                                else -> "CRASH"
                            }
                            reports += Triple(
                                reasonName,
                                "status=${info.status} desc=${info.description}\n$trace",
                                info.timestamp.toString(),
                            )
                        }
                    }
                }
                if (reports.isEmpty()) {
                    if (newest != cursor) prefs.edit().putLong("exit_cursor", newest).apply()
                    return@Thread
                }
                val user = context.getSharedPreferences("kewu_prefs", Context.MODE_PRIVATE).getString("kewu_username", "") ?: ""
                val device = "${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}"
                var allOk = true
                reports.forEach { (reason, detail, at) ->
                    val body = org.json.JSONObject()
                        .put("user", user).put("device", device).put("appVersion", "debug")
                        .put("reason", reason).put("detail", detail).put("happenedAt", at)
                        .toString()
                    val ok = runCatching {
                        val conn = java.net.URL("http://47.99.42.168:8080/api/crash-reports").openConnection() as java.net.HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.connectTimeout = 8000
                        conn.readTimeout = 8000
                        conn.doOutput = true
                        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                        val code = conn.responseCode
                        conn.disconnect()
                        code in 200..299
                    }.getOrDefault(false)
                    if (!ok) allOk = false
                }
                if (allOk) {
                    files.forEach { it.delete() }
                    prefs.edit().putLong("exit_cursor", newest).apply()
                }
            } catch (_: Throwable) {
                // 上报失败下次启动再试
            }
        }.start()
    }
}
