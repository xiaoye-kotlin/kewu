package com.kewu.app

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android 文件选择：图片走系统照片选择器（PickMultipleVisualMedia，Android 13+ 免存储权限，
 * 老系统/部分厂商 ROM 由 androidx 自动回落到 ACTION_OPEN_DOCUMENT），其它文件走 OpenMultipleDocuments。
 * 选中的 content:// Uri 在 IO 线程拷进 `cacheDir/attach/`，回调切回主线程。
 */
actual fun pickFiles(images: Boolean, onResult: (List<PickedFile>) -> Unit) {
    val activity = appActivity
    if (activity == null) {
        onResult(emptyList())
        return
    }
    val onUris: (List<Uri>) -> Unit = { uris ->
        if (uris.isEmpty()) {
            onResult(emptyList())
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                val files = uris.mapNotNull { copyToCache(it) }
                withContext(Dispatchers.Main) { onResult(files) }
            }
        }
    }
    try {
        if (images) activity.pickImages(onUris) else activity.pickDocuments(onUris)
    } catch (_: Exception) {
        onResult(emptyList())
    }
}

private fun copyToCache(uri: Uri): PickedFile? = try {
    val cr = appContext.contentResolver
    var name = "附件"
    var size = -1L
    cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getString(0)?.let { name = it }
            if (!c.isNull(1)) size = c.getLong(1)
        }
    }
    val mime = cr.getType(uri) ?: "application/octet-stream"
    val dir = File(appContext.cacheDir, "attach").apply { mkdirs() }
    val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    val out = File(dir, "${System.currentTimeMillis()}_$safe")
    cr.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return null
    PickedFile(name = name, path = out.absolutePath, mime = mime, size = if (size >= 0) size else out.length())
} catch (_: Exception) {
    null
}

actual fun openUrl(url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    } catch (_: Exception) {
        // 没有可处理的应用：静默
    }
}
