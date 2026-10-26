package com.kewu.modules.user.service

import at.favre.lib.crypto.bcrypt.BCrypt
import com.kewu.modules.course.models.CourseInfo
import com.kewu.modules.course.models.toInfo
import com.kewu.modules.course.repository.CourseRepository
import com.kewu.modules.user.models.*
import com.kewu.modules.user.repository.UserRepository
import com.kewu.security.JwtConfig
import java.util.UUID

// 注册/登录/绑课的业务逻辑都在这
class UserService(private val repository: UserRepository, private val courseRepository: CourseRepository) {

    fun register(req: RegisterRequest): Result<UserInfo> {
        if (req.username.isBlank() || req.password.length < 6) {
            return Result.failure(IllegalArgumentException("用户名不能为空, 密码至少 6 位"))
        }
        if (repository.findByUsername(req.username) != null) {
            return Result.failure(IllegalArgumentException("这个用户名已经被注册了"))
        }
        val role = UserRole.valid(req.role)
        val user = User(
            id = UUID.randomUUID().toString(),
            username = req.username,
            passwordHash = BCrypt.withDefaults().hashToString(12, req.password.toCharArray()),
            nickname = req.nickname ?: req.username,
            role = role,
            school = req.school?.trim()?.take(128)?.ifBlank { null }
        )
        repository.createUser(user)
        return Result.success(user.toInfo())
    }

    fun login(req: LoginRequest): Result<LoginResponse> {
        val user = repository.findByUsername(req.username)
            ?: return Result.failure(IllegalArgumentException("用户名或密码不对"))
        val ok = BCrypt.verifyer().verify(req.password.toCharArray(), user.passwordHash).verified
        if (!ok) return Result.failure(IllegalArgumentException("用户名或密码不对"))
        val token = JwtConfig.createToken(user.id, user.username)
        return Result.success(LoginResponse(token, user.toInfo(), user.role))
    }

    fun bindCourse(userId: String, courseId: String): Result<CourseInfo> {
        if (!courseRepository.checkExists(courseId)) {
            return Result.failure(IllegalArgumentException("课程不存在"))
        }
        if (!repository.bindCourse(userId, courseId)) {
            return Result.failure(IllegalArgumentException("绑定失败, 请确认用户是否有效"))
        }
        // 返回绑定后的课程信息
        return Result.success(courseRepository.findCourse(courseId)!!.toInfo())
    }

    fun getProfile(userId: String): UserInfo? = repository.findUserById(userId)?.toInfo()

    fun getBoundCourses(userId: String): List<CourseInfo> =
        courseRepository.findCoursesByIds(repository.getBoundCourseIds(userId)).map { it.toInfo() }

    fun updateProfile(userId: String, req: UpdateProfileRequest): Result<UserInfo> {
        repository.findUserById(userId) ?: return Result.failure(IllegalArgumentException("用户不存在"))
        repository.updateProfile(
            userId,
            req.nickname?.trim()?.take(64)?.ifBlank { null },
            req.school?.trim()?.take(128),
            req.className?.trim()?.take(128)
        )
        return Result.success(repository.findUserById(userId)!!.toInfo())
    }

    private fun User.toInfo() = UserInfo(id, username, nickname, role, school, className)
}
