package com.kewu.modules.user.models

import com.kewu.modules.course.models.CourseInfo
import kotlinx.serialization.Serializable

// 用户角色: 学生和老师在后端是两套权限
object UserRole {
    const val STUDENT = "STUDENT"
    const val TEACHER = "TEACHER"
    fun valid(r: String?): String =
        if (r.equals(TEACHER, true)) TEACHER else STUDENT // 认不出来的一律当学生
}

// 用户模块用到的数据模型
@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    val nickname: String? = null,
    val role: String? = null // 不传默认学生; 传 TEACHER 就是老师
)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(val token: String, val user: UserInfo, val role: String = UserRole.STUDENT)

@Serializable
data class UserInfo(
    val id: String,
    val username: String,
    val nickname: String?,
    val role: String = UserRole.STUDENT
)

@Serializable
data class UserProfileResponse(val user: UserInfo?, val courses: List<CourseInfo>)

// 内部用的用户实体, 密码不会往外吐
data class User(
    val id: String,
    val username: String,
    val passwordHash: String,
    val nickname: String?,
    val role: String = UserRole.STUDENT,
    val courseIds: MutableSet<String> = mutableSetOf()
)
