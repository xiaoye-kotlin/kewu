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
        // 普通注册即使传 ADMIN 也强制落 STUDENT：管理员只能由总控端 POST /users 创建（2026-10-28）
        val role = UserRole.valid(req.role).let { if (it == UserRole.ADMIN) UserRole.STUDENT else it }
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
        // 单设备登录：同类平台换新 sid，旧设备的 token 随即失效；不传 platform 的旧版客户端不参与
        val token = if (req.platform.isNullOrBlank()) JwtConfig.createToken(user.id, user.username) else {
            val plat = com.kewu.modules.user.repository.UserSessionStore.normalize(req.platform)
            JwtConfig.createToken(user.id, user.username, com.kewu.modules.user.repository.UserSessionStore.newSession(user.id, plat), plat)
        }
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
            // 学校注册后不可改（2026-11-02）：只有原来没填的才允许补填
            req.school?.trim()?.take(128)?.takeIf { u.school.isNullOrBlank() && it.isNotBlank() },
            // 学生的班级以加入的教学班为准，不接受自己改（2026-10-26）
            if (u.role == UserRole.TEACHER) req.className?.trim()?.take(128) else null,
            phone?.take(32),
            email?.take(128),
            req.subject?.trim()?.take(64),
            req.bio?.trim()?.take(512)
        )
        // 改了姓名 / 学校后再认领一次课表
        runCatching { com.kewu.modules.schedule.ScheduleService.autoBindTeacher(userId) }
        return Result.success(repository.findUserById(userId)!!.toInfo())
    }

    /**
     * 课程任课老师名片（2026-11-02）：教学班的老师 → 课表里的任课老师 → 课程表上的老师名；
     * 能对上注册账号就带上手机号 / 科目 / 简介，对不上只给名字。
     */
    fun courseTeacher(courseId: String): TeacherCard? = org.jetbrains.exposed.sql.transactions.transaction {
        val course = com.kewu.db.Courses.selectAll().where { com.kewu.db.Courses.id eq courseId }.firstOrNull()
        val cls = com.kewu.db.Classes.selectAll().where { com.kewu.db.Classes.courseId eq courseId }.firstOrNull()
        val sch = com.kewu.db.CourseSchedules.selectAll().where { com.kewu.db.CourseSchedules.courseId eq courseId }.firstOrNull()
        val teacherId = cls?.get(com.kewu.db.Classes.teacherId)?.ifBlank { null } ?: sch?.get(com.kewu.db.CourseSchedules.teacherId)?.ifBlank { null }
        val name = cls?.get(com.kewu.db.Classes.teacherName)?.ifBlank { null }
            ?: sch?.get(com.kewu.db.CourseSchedules.teacherName)?.ifBlank { null }
            ?: course?.get(com.kewu.db.Courses.teacher)?.ifBlank { null }
        val courseName = course?.get(com.kewu.db.Courses.name) ?: cls?.get(com.kewu.db.Classes.courseName)
        val u = teacherId?.let { repository.findUserById(it) ?: repository.findByUsername(it) }
            ?: name?.let { n ->
                com.kewu.db.Users.selectAll().where { com.kewu.db.Users.nickname eq n }
                    .firstOrNull { it[com.kewu.db.Users.role] == UserRole.TEACHER }?.get(com.kewu.db.Users.id)
                    ?.let { repository.findUserById(it) }
            }
        if (u == null && name == null) return@transaction null
        TeacherCard(
            id = u?.id, name = u?.nickname?.ifBlank { null } ?: name ?: u?.username.orEmpty(),
            subject = u?.subject ?: courseName, phone = u?.phone, email = u?.email,
            school = u?.school, bio = u?.bio, courseName = courseName,
        )
    }

    private fun User.toInfo() = UserInfo(id, username, nickname, role, school, className, phone, email, subject, bio)
}
