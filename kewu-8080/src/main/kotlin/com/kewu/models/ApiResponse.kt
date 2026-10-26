package com.kewu.models

import kotlinx.serialization.Serializable

// 所有接口统一套一层这个返回结构, 前后端都好解析
@Serializable
data class ApiResponse<T>(val code: Int, val message: String, val data: T? = null)
