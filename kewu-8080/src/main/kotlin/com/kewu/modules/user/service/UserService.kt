package com.kewu.modules.user.service

import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
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
        // 老师注册：认领课表里「同名同校」的课程（2026-10-26）
        runCatching { com.kewu.modules.schedule.ScheduleService.autoBindTeacher(user.id) }
        return Result.success(user.toInfo())
    }

    fun login(req: LoginRequest): Result<LoginResponse> {
        val user = repository.findByUsername(req.username)
            ?: return Result.failure(IllegalArgumentException("用户名或密码不对"))
        val ok = BCrypt.verifyer().verify(req.password.toCharArray(), user.passwordHash).verified
        if (!ok) return Result.failure(IllegalArgumentException("用户名或密码不对"))
        runCatching { com.kewu.modules.schedule.ScheduleService.autoBindTeacher(user.id) }
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

    // 2026-10-27：「我的课程」= 已绑课程 ∪ 我所在班级的全部课程（班级主课 + 老师导入课表里的课）。
    // 旧版只读 user_courses，而绑定只在加班那一刻写：老师后导课表 / 后补课程，已加班的学生永远看不到（课程 0）。
    fun getBoundCourses(userId: String): List<CourseInfo> {
        val fromClasses = org.jetbrains.exposed.sql.transactions.transaction {
            val classIds = com.kewu.db.ClassStudents.selectAll()
                .where { com.kewu.db.ClassStudents.studentId eq userId }.map { it[com.kewu.db.ClassStudents.classId] }
            if (classIds.isEmpty()) emptyList()
            else com.kewu.db.Classes.selectAll().where { com.kewu.db.Classes.id inList classIds }
                .map { it[com.kewu.db.Classes.courseId] } +
                com.kewu.db.CourseSchedules.selectAll().where { com.kewu.db.CourseSchedules.classId inList classIds }
                    .map { it[com.kewu.db.CourseSchedules.courseId] }
        }
        val ids = (repository.getBoundCourseIds(userId) + fromClasses).filter { it.isNotBlank() }.distinct()
        return courseRepository.findCoursesByIds(ids).map { it.toInfo() }
    }

    fun updateProfile(userId: String, req: UpdateProfileRequest): Result<UserInfo> {
        val u = repository.findUserById(userId) ?: return Result.failure(IllegalArgumentException("用户不存在"))
        val phone = req.phone?.trim()
        if (!phone.isNullOrEmpty() && !Regex("^1\\d{10}$").matches(phone)) return Result.failure(IllegalArgumentException("请输入 11 位手机号"))
        val email = req.email?.trim()
        if (!email.isNullOrEmpty() && !Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email)) return Result.failure(IllegalArgumentException("邮箱格式不正确"))
        repository.updateProfile(
            userId,
            req.nickname?.trim()?.take(64)?.ifBlank { null },
            req.school?.trim()?.take(128),
            // 学生的班级以加入的教学班为准，不接受自己改（2026-10-26）
            if (u.role == UserRole.TEACHER) req.className?.trim()?.take(128) else null,
            phone?.take(32),
            email?.take(128)
        )
        // 改了姓名 / 学校后再认领一次课表
        runCatching { com.kewu.modules.schedule.ScheduleService.autoBindTeacher(userId) }
        return Result.success(repository.findUserById(userId)!!.toInfo())
    }

    private fun User.toInfo() = UserInfo(id, username, nickname, role, school, className, phone, email)
}
