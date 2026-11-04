package com.kewu.app

import io.ktor.http.encodeURLParameter
import io.ktor.util.cio.readChannel

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import java.util.prefs.Preferences
import kotlin.system.exitProcess

actual fun platformHttpClient(): HttpClient = HttpClient(OkHttp)

actual fun platformDeviceId(): String = "desktop-${System.getProperty("user.name", "user")}"

/** ★ V10：桌面窗口方向由用户控制，无系统级方向锁；通话页布局本身按竖屏比例设计。 */
actual fun platformLockPortrait(on: Boolean) = Unit

private val preferences: Preferences
    get() = Preferences.userRoot().node("com.kewu.app")

// ★ 2026-10-26：java.util.prefs 单值上限 8KB（Windows 注册表），聊天记录 / 录音记录一长就写不进去
//   （旧版要么抛异常、要么把整份历史写成 []）。超过 6000 字符的值改存 ~/.kewu/kv/<key>.json，prefs 里只放标记。
private const val FILE_MARK = "@file"
private fun kvFile(key: String) = File(System.getProperty("user.home"), ".kewu/kv/${key.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.json")

actual fun kvGet(key: String): String? {
    val v = preferences.get(key, null) ?: return null
    if (v != FILE_MARK) return v
    return try { kvFile(key).readText() } catch (_: Exception) { null }
}

actual fun kvSet(key: String, value: String?) {
    when {
        value == null -> {
            preferences.remove(key)
            runCatching { kvFile(key).delete() }
        }
        value.length > 6000 -> {
            val f = kvFile(key).apply { parentFile?.mkdirs() }
            f.writeText(value)
            preferences.put(key, FILE_MARK)
        }
        else -> {
            preferences.put(key, value)
            runCatching { kvFile(key).delete() }
        }
    }
}

actual fun platformAppContext(): Any = Any()

actual fun platformActivity(): Any? = null

actual fun platformCacheDir(): String =
    File(System.getProperty("user.home"), ".kewu/cache").apply { mkdirs() }.absolutePath

actual fun platformReadFile(path: String): ByteArray? = try {
    File(path).readBytes()
} catch (_: Exception) {
    null
}

actual fun requestAudioPermission(activity: Any, onResult: (Boolean) -> Unit) = onResult(true)

actual fun requestCameraPermission(activity: Any, onResult: (Boolean) -> Unit) = onResult(false)

// Desktop 无运行时权限概念：通知恒「已授予」，申请回调 false，设置页 no-op
actual fun isNotificationPermissionGranted(): Boolean = true
actual fun requestNotificationPermission(activity: Any, onResult: (Boolean) -> Unit) = onResult(false)
actual fun isAudioPermissionGranted(): Boolean = true
actual fun isCameraPermissionGranted(): Boolean = true
actual fun openAppSettings() = Unit
actual fun shareText(text: String) = Unit
actual fun platformExitApp() { exitProcess(0) }
actual fun openBilibiliSearch(keyword: String) =
    openUrl("https://search.bilibili.com/all?keyword=" + keyword.encodeURLParameter())
actual fun openBilibiliVideo(bvid: String) = openUrl("https://www.bilibili.com/video/$bvid")

actual fun platformFileSize(path: String): Long = try {
    java.io.File(path).let { if (it.exists()) it.length() else -1L }
} catch (_: Exception) {
    -1L
}

actual fun platformFileChannel(path: String): io.ktor.utils.io.ByteReadChannel =
    java.io.File(path).readChannel()

actual fun platformReadFileRange(path: String, offset: Long, len: Int): ByteArray? = try {
    java.io.RandomAccessFile(path, "r").use { raf ->
        val size = raf.length()
        if (offset >= size) return null
        raf.seek(offset)
        val toRead = minOf(len.toLong(), size - offset).toInt()
        val buf = ByteArray(toRead)
        var read = 0
        while (read < toRead) {
            val n = raf.read(buf, read, toRead - read)
            if (n < 0) break
            read += n
        }
        if (read == toRead) buf else buf.copyOf(read)
    }
} catch (_: Exception) {
    null
}

actual fun platformWriteFile(path: String, bytes: ByteArray): Boolean = try {
    java.io.File(path).let { it.parentFile?.mkdirs(); it.writeBytes(bytes) }
    true
} catch (_: Exception) {
    false
}

actual fun platformDeleteFile(path: String) {
    runCatching { java.io.File(path).delete() }
}
