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
    }
}
