package com.kewu.app

/**
 * 作业提交附件（2026-10-26：提交作业除了文字，还能带图片 / 文件 / 录音）。
 *
 * 流程：本地选好附件（[LocalAttachment]，文件已拷进 App 缓存目录）→ 提交时逐个
 * `KewuApi.uploadAttachment` 上传换成服务端 [AttachmentInfo]（id + url）→ 随
 * `POST /api/assignments/{id}/submissions` 的 `attachments` 字段一起提交。
 * 后端接口见项目根 `作业附件-后端对接需求.md`（上传接口未上线前，带附件的提交会如实报错，不会静默丢附件）。
 */
enum class AttachmentKind(val value: String, val label: String) {
    IMAGE("IMAGE", "图片"),
    FILE("FILE", "文件"),
    AUDIO("AUDIO", "录音");

    companion object {
        fun from(v: String?): AttachmentKind = entries.firstOrNull { it.value == v } ?: FILE
    }
}

/** 本地待上传的附件。[path] 为 App 缓存目录里的副本（选择器返回后立即拷贝，原文件被删也不影响）。 */
data class LocalAttachment(
    val id: String,
    val kind: AttachmentKind,
    val name: String,
    val path: String,
    val mime: String,
    val size: Long,
    val durationSec: Int? = null,
)

/** 服务端附件（上传成功后返回；提交详情里原样带回）。 */
data class AttachmentInfo(
    val id: String,
    val kind: AttachmentKind,
    val name: String,
    val url: String,
    val mime: String,
    val size: Long,
    val durationSec: Int? = null,
)

/** 平台文件选择器的返回项（已拷贝进缓存目录）。 */
data class PickedFile(val name: String, val path: String, val mime: String, val size: Long)

/** 单个附件大小上限（与需求文档一致）。 */
const val ATTACHMENT_MAX_BYTES = 20L * 1024 * 1024

/** 单次提交附件个数上限。 */
const val ATTACHMENT_MAX_COUNT = 9

/**
 * 打开系统选择器。[images] = true 只选图片（Android 走系统照片选择器，免存储权限），
 * false 选任意文件。用户取消返回空列表；回调在主线程。
 */
expect fun pickFiles(images: Boolean, onResult: (List<PickedFile>) -> Unit)

/** 用系统浏览器 / 默认应用打开链接（查看附件）。 */
expect fun openUrl(url: String)

/** 人类可读的文件大小。 */
fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes / 102.4).toInt() / 10.0} KB"
    else -> "${(bytes / 104857.6).toInt() / 10.0} MB"
}
