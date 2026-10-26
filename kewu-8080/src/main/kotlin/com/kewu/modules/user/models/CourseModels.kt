package com.kewu.modules.user.models

import kotlinx.serialization.Serializable

// 用户绑课用的请求体, 课程本体已经挪到课程模块了
@Serializable
data class BindCourseRequest(val courseId: String)
