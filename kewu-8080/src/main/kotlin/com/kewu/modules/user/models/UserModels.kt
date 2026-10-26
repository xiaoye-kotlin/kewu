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
    val role: String? = null, // 不传默认学生; 传 TEACHER 就是老师
    val school: String? = null // 注册时选的学校
)

// 修改个人资料：只改传了的字段
@Serializable
data class UpdateProfileRequest(
    val nickname: String? = null,
    val school: String? = null,
    val className: String? = null, // 学生忽略：班级以加入的教学班为准
    val phone: String? = null,
    val email: String? = null
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
    val role: String = UserRole.STUDENT,
    val school: String? = null,
    val className: String? = null,
    val phone: String? = null,   // 只在 /me（本人）里返回
    val email: String? = null
)

@Serializable
data class UserProfileResponse(
    val user: UserInfo?,
    val courses: List<CourseInfo>,
    val classes: List<com.kewu.modules.roster.JoinedClass> = emptyList() // 学生加入的教学班（2026-10-26）
)

// 内部用的用户实体, 密码不会往外吐
data class User(
    val id: String,
    val username: String,
    val passwordHash: String,
    val nickname: String?,
    val role: String = UserRole.STUDENT,
    val courseIds: MutableSet<String> = mutableSetOf(),
    val school: String? = null,
    val className: String? = null,
    val phone: String? = null,
    val email: String? = null
)
