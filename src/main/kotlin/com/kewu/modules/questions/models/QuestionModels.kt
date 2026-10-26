package com.kewu.modules.questions.models

import kotlinx.serialization.Serializable

// 学生端提问请求体：courseId + content 必填，studentId/studentName 可选（用于展示谁问的）
@Serializable
data class AskQuestionRequest(
    val courseId: String,
    val content: String,
    val studentId: String = "",
    val studentName: String = ""
)
