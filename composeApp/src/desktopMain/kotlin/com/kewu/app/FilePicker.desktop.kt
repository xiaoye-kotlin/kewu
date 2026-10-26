package com.kewu.app

import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import java.nio.file.Files

/** 桌面端：AWT 原生文件对话框（多选），直接返回原文件路径（桌面端不必拷贝）。 */
actual fun pickFiles(images: Boolean, onResult: (List<PickedFile>) -> Unit) {
    val files = try {
        val dialog = FileDialog(null as Frame?, if (images) "选择图片" else "选择文件", FileDialog.LOAD).apply {
            isMultipleMode = true
            if (images) {
                setFilenameFilter { _, name ->
                    name.lowercase().substringAfterLast('.', "") in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
                }
            }
            isVisible = true
        }
        dialog.files.orEmpty().map { f: File ->
            PickedFile(
                name = f.name,
                path = f.absolutePath,
                mime = runCatching { Files.probeContentType(f.toPath()) }.getOrNull() ?: "application/octet-stream",
                size = f.length(),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
    onResult(files)
}

actual fun openUrl(url: String) {
    try {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url))
    } catch (_: Exception) {
    }
}
