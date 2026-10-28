package com.kewu.modules.upload.models

import kotlinx.serialization.Serializable

// 附件种类
object AttachmentKind {
    const val IMAGE = "IMAGE"
    const val FILE = "FILE"
    const val AUDIO = "AUDIO"
    val ALL = listOf(IMAGE, FILE, AUDIO)
}

// 一个已上传的附件（/api/uploads 返回的就是它；提交作业 / 课前资料原样引用）
@Serializable
data class Attachment(
    val id: String = "",
    val kind: String = AttachmentKind.FILE,
    val name: String = "",
    val url: String = "",
    val mime: String = "",
    val size: Long = 0,
    val durationSec: Int? = null
)

// 课前资料：老师开课前上传的 PPT / 讲义，AI 后续结合课堂录音做分析
@Serializable
data class CourseMaterial(
    val id: String,
    val courseId: String,
    val sessionId: String? = null,
    val title: String = "",
    val note: String = "",
    val attachments: List<Attachment> = emptyList(),
    val uploader: String = "",
    val createdAt: String = ""
)

@Serializable
data class CreateMaterialRequest(
    val title: String = "",
    val note: String = "",
    val sessionId: String? = null,
    val uploader: String = "",
    val attachments: List<Attachment> = emptyList()
)
