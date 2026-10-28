package com.kewu.modules.admin.service

import at.favre.lib.crypto.bcrypt.BCrypt
import com.kewu.db.Assignments
import com.kewu.db.AttendanceRecords
import com.kewu.db.AttendanceRoster
import com.kewu.db.AttendanceSessions
import com.kewu.db.ClassStudents
import com.kewu.db.Classes
import com.kewu.db.Courses
import com.kewu.db.Recordings
import com.kewu.db.Submissions
import com.kewu.db.Users
import com.kewu.modules.admin.models.AdminAssignmentDTO
import com.kewu.modules.admin.models.AdminAssignmentDetailDTO
import com.kewu.modules.admin.models.AdminAttendanceDetailDTO
import com.kewu.modules.admin.models.AdminAttendanceRecordDTO
import com.kewu.modules.admin.models.AdminAttendanceSessionDTO
import com.kewu.modules.admin.models.AdminClassDTO
import com.kewu.modules.admin.models.AdminClassStudentDTO
import com.kewu.modules.admin.models.AdminCourseDTO
import com.kewu.modules.admin.models.AdminCreateUserRequest
import com.kewu.modules.admin.models.AdminOverviewDTO
import com.kewu.modules.admin.models.AdminRecordingDTO
import com.kewu.modules.admin.models.AdminRecordingDetailDTO
import com.kewu.modules.admin.models.AdminResetPasswordRequest
import com.kewu.modules.admin.models.AdminSubmissionDTO
import com.kewu.modules.admin.models.AdminUpdateClassRequest
import com.kewu.modules.admin.models.AdminUpdateCourseRequest
import com.kewu.modules.admin.models.AdminUpdateUserRequest
import com.kewu.modules.admin.models.AdminUserDTO
import com.kewu.modules.admin.models.AdminUserDetailDTO
import com.kewu.modules.assignment.models.ReviewRequest
import com.kewu.modules.assignment.repository.AssignmentRepository
import com.kewu.modules.attendance.models.AttendanceSession
import com.kewu.modules.attendance.repository.AttendanceRepository
import com.kewu.modules.course.models.Course
import com.kewu.modules.course.repository.CourseRepository
import com.kewu.modules.teacher.models.ClassRoom
import com.kewu.modules.teacher.models.CreateCourseRequest
import com.kewu.modules.teacher.models.CreateClassRequest
import com.kewu.modules.teacher.repository.TeacherRepository
import com.kewu.modules.user.models.UserRole
import com.kewu.modules.user.repository.UserRepository
import com.kewu.recserver.config.ObsConfig
import com.kewu.recserver.config.RedisConfig
import com.kewu.recserver.services.RedisService
import com.kewu.security.JwtConfig
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import com.obs.services.ObsClient
import com.obs.services.ObsConfiguration
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

// 总控端（管理后台）业务逻辑：鉴权 + 用户/概览/录音/课程班级/作业批阅/签到管理。
// 复用现有 MySQL 表与 Repository。写操作都要求调用方先过 isAdmin 鉴权（在 AdminRoutes 里拦截），本类不重复校验。
class AdminService(
    private val userRepository: UserRepository,
    private val obsConfig: ObsConfig,
    private val courseRepository: CourseRepository,
    private val assignmentRepository: AssignmentRepository,
    private val attendanceRepository: AttendanceRepository,
    private val teacherRepository: TeacherRepository,
    redisCfg: RedisConfig
) {
    private val adminKey: String? = System.getenv("ADMIN_KEY")?.takeIf { it.isNotBlank() }
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    // 系统健康探测用：自己持有 RedisService 实例，不依赖全局 attributes，避免取不到时整段崩
    private val redisService = RedisService(redisCfg)

    // ---------- 鉴权：X-Admin-Key 或 Bearer token(role==ADMIN) ----------
    // 双保险：服务器内网/本机管理程序走 X-Admin-Key（免登录）；网页端走管理员登录 Token
    suspend fun isAdmin(call: ApplicationCall): Boolean {
        val key = call.request.header("X-Admin-Key")
        if (!adminKey.isNullOrBlank() && key != null && key == adminKey) return true
        val auth = call.request.header(HttpHeaders.Authorization)
        if (auth != null && auth.startsWith("Bearer ", true)) {
            val token = auth.substring(7).trim()
            val userId = JwtConfig.verifyToken(token) ?: return false
            val user = userRepository.findUserById(userId) ?: return false
            return user.role == UserRole.ADMIN
        }
        return false
    }

    // ---------- 用户管理（§5.3） ----------
    fun listUsers(
        page: Int, size: Int, role: String?, keyword: String?, status: String?
    ): Pair<List<AdminUserDTO>, Int> = transaction {
        val rows = Users.selectAll().toList()
        val filtered = rows.filter { row ->
            (role == null || row[Users.role] == role) &&
            (status == null || row[Users.status] == status) &&
            (keyword.isNullOrBlank() ||
                row[Users.username].contains(keyword, true) ||
                (row[Users.nickname]?.contains(keyword, true) == true))
        }
        val total = filtered.size
        val items = filtered.drop((page - 1) * size).take(size).map { row ->
            AdminUserDTO(row[Users.id], row[Users.username], row[Users.nickname], row[Users.role], row[Users.status])
        }
        items to total
    }

    fun getUser(id: String): AdminUserDetailDTO? = transaction {
        val row = Users.selectAll().where { Users.id eq id }.firstOrNull() ?: return@transaction null
        val uid = row[Users.id]
        val courseCount = userRepository.getBoundCourseIds(uid).size
        val submissionCount = Submissions.selectAll().where { Submissions.studentId eq uid }.count().toInt()
        val recordingCount = Recordings.selectAll().where { Recordings.studentId eq uid }.count().toInt()
        AdminUserDetailDTO(
            uid, row[Users.username], row[Users.nickname], row[Users.role], row[Users.status],
            courseCount, submissionCount, recordingCount
        )
    }

    fun createUser(req: AdminCreateUserRequest): AdminUserDTO {
        if (req.username.isBlank() || req.password.length < 6) throw IllegalArgumentException("用户名不能为空, 密码至少 6 位")
        if (userRepository.findByUsername(req.username) != null) throw IllegalArgumentException("用户名已存在")
        val role = UserRole.valid(req.role)
        val id = UUID.randomUUID().toString()
        transaction {
            Users.insert {
                it[Users.id] = id
                it[username] = req.username
                it[passwordHash] = BCrypt.withDefaults().hashToString(12, req.password.toCharArray())
                it[nickname] = req.nickname ?: req.username
                it[Users.role] = role
                it[Users.status] = "active"
            }
        }
        return AdminUserDTO(id, req.username, req.nickname ?: req.username, role, "active")
    }

    fun updateUser(id: String, req: AdminUpdateUserRequest): Boolean = transaction {
        if (Users.selectAll().where { Users.id eq id }.firstOrNull() == null) return@transaction false
        Users.update({ Users.id eq id }) {
            if (req.nickname != null) it[nickname] = req.nickname
            if (req.role != null) it[Users.role] = UserRole.valid(req.role)
            if (req.status != null) it[Users.status] = req.status
        }
        true
    }

    fun resetPassword(id: String, newPassword: String): Boolean {
        if (newPassword.length < 6) throw IllegalArgumentException("新密码至少 6 位")
        return transaction {
            if (Users.selectAll().where { Users.id eq id }.firstOrNull() == null) return@transaction false
            Users.update({ Users.id eq id }) {
                it[passwordHash] = BCrypt.withDefaults().hashToString(12, newPassword.toCharArray())
            }
            true
        }
    }

    fun setStatus(id: String, status: String): Boolean = transaction {
        if (Users.selectAll().where { Users.id eq id }.firstOrNull() == null) return@transaction false
        Users.update({ Users.id eq id }) { it[Users.status] = status }
        true
    }

    fun deleteUser(id: String): Boolean = transaction {
        Users.deleteWhere { Users.id eq id } > 0
    }

    // ---------- 概览（§5.1） ----------
    fun overview(): AdminOverviewDTO = transaction {
        val allRec = Recordings.selectAll().toList()
        val liveRec = allRec.filter { it[Recordings.deletedAt] == null }
        val userCount = Users.selectAll().count().toInt()
        val courseCount = Courses.selectAll().count().toInt()
        val classCount = Classes.selectAll().count().toInt()
        val recordingCount = liveRec.size
        val assignmentCount = Assignments.selectAll().count().toInt()
        val submissionCount = Submissions.selectAll().count().toInt()
        val recordingBytes = liveRec.map { it[Recordings.sizeBytes] ?: 0L }.sum()
        AdminOverviewDTO(userCount, courseCount, classCount, recordingCount, assignmentCount, submissionCount, recordingBytes)
    }

    // ---------- 录音管理（§5.2） ----------
    fun listRecordings(
        page: Int, size: Int, classId: String?, courseId: String?, studentId: String?,
        role: String?, from: String?, to: String?, keyword: String?
    ): Pair<List<AdminRecordingDTO>, Int> = transaction {
        val rows = Recordings.selectAll().toList().filter { it[Recordings.deletedAt] == null }
        val filtered = rows.filter { r ->
            (classId == null || r[Recordings.classId] == classId) &&
            (courseId == null || r[Recordings.courseId] == courseId) &&
            (studentId == null || r[Recordings.studentId] == studentId) &&
            (role == null || r[Recordings.role] == role) &&
            (from == null || r[Recordings.createdAt] >= from) &&
            (to == null || r[Recordings.createdAt] <= to) &&
            (keyword.isNullOrBlank() || (r[Recordings.studentName]?.contains(keyword, true) == true))
        }
        val total = filtered.size
        val items = filtered.sortedByDescending { it[Recordings.createdAt] }
            .drop((page - 1) * size).take(size).map { r ->
                AdminRecordingDTO(
                    id = r[Recordings.id], classId = r[Recordings.classId], sessionId = r[Recordings.sessionId],
                    courseId = r[Recordings.courseId], studentName = r[Recordings.studentName], role = r[Recordings.role],
                    url = r[Recordings.url], score = r[Recordings.score], durationSec = r[Recordings.durationSec],
                    format = r[Recordings.format], createdAt = r[Recordings.createdAt], deleted = false
                )
            }
        items to total
    }

    fun getRecording(id: String): AdminRecordingDetailDTO? = transaction {
        val r = Recordings.selectAll().where { Recordings.id eq id }.firstOrNull() ?: return@transaction null
        val url = r[Recordings.url]
        // 试听/下载暂用直链（OBS 桶若为私有，后续补签名短链，返回原 URL 前端也能直接用或报错后我们再改）
        AdminRecordingDetailDTO(
            id = r[Recordings.id], classId = r[Recordings.classId], sessionId = r[Recordings.sessionId],
            courseId = r[Recordings.courseId], studentId = r[Recordings.studentId], studentName = r[Recordings.studentName],
            role = r[Recordings.role], url = url, playUrl = url, downloadUrl = url,
            score = r[Recordings.score], avgLevelDb = r[Recordings.avgLevelDb], snrDb = r[Recordings.snrDb],
            durationSec = r[Recordings.durationSec], format = r[Recordings.format], sizeBytes = r[Recordings.sizeBytes],
            createdAt = r[Recordings.createdAt]
        )
    }

    fun deleteRecording(id: String): Boolean = transaction {
        if (Recordings.selectAll().where { Recordings.id eq id }.firstOrNull() == null) return@transaction false
        Recordings.update({ Recordings.id eq id }) { it[deletedAt] = fmt.format(LocalDateTime.now()) }
        true
    }

    // ---------- OBS 连通性探测（系统健康用） ----------
    private val obsClient by lazy {
        val c = ObsConfiguration().apply { endPoint = obsConfig.endpoint }
        ObsClient(obsConfig.ak ?: "", obsConfig.sk ?: "", c)
    }

    fun obsHealthy(): Boolean = runCatching {
        obsClient.listObjects(obsConfig.bucket) // 列对象探测连通，异常即视为 OBS 不可用
        true
    }.getOrDefault(false)

    // 系统健康：backend 永远 up；redis/obs 各自探测，任何异常都降级为 down 而非 500
    // 值一律用 String：Map<String, Any> 里的 Any 没有对应序列化器，respond 时会直接 500
    suspend fun systemHealth(): Map<String, String> {
        val redisUp = runCatching { redisService.redisHealthy() }.getOrDefault(false)
        val obsUp = runCatching { obsHealthy() }.getOrDefault(false)
        val rt = Runtime.getRuntime()
        return mapOf(
            "backend" to "up",
            "redis" to if (redisUp) "up" else "down",
            "obs" to if (obsUp) "up" else "down",
            "jvmUsedMb" to ((rt.totalMemory() - rt.freeMemory()) / 1024 / 1024).toString(),
            "diskFreeBytes" to java.io.File("/").freeSpace.toString(),
            "time" to fmt.format(LocalDateTime.now())
        )
    }

    // ---------- 课程与班级（§5.4） ----------
    // 全部课程 + 班级数 + 去重学生数
    fun listCourses(): List<AdminCourseDTO> = transaction {
        courseRepository.listCourses().map { c ->
            val classes = teacherRepository.listByCourse(c.id)
            val studentIds = classes.flatMap { it.studentIds }.toSet()
            AdminCourseDTO(
                id = c.id, name = c.name, description = c.description, teacher = c.teacher,
                coverUrl = c.coverUrl, classCount = classes.size, studentCount = studentIds.size
            )
        }
    }

    fun createCourse(req: CreateCourseRequest): AdminCourseDTO {
        if (req.name.isBlank()) throw IllegalArgumentException("课程名不能为空")
        val id = UUID.randomUUID().toString()
        transaction {
            courseRepository.createCourse(
                Course(id = id, name = req.name, description = req.description, teacher = req.teacher, coverUrl = null)
            )
        }
        return AdminCourseDTO(id, req.name, req.description, req.teacher, null, 0, 0)
    }

    fun updateCourse(id: String, req: AdminUpdateCourseRequest): Boolean = transaction {
        if (courseRepository.findCourse(id) == null) return@transaction false
        Courses.update({ Courses.id eq id }) {
            if (req.name != null) it[name] = req.name
            if (req.description != null) it[description] = req.description
            if (req.teacher != null) it[teacher] = req.teacher
            if (req.coverUrl != null) it[coverUrl] = req.coverUrl
        }
        true
    }

    // 删课程：连同其班级（及班级学生关联）一起删；作业/提交/录音按"保留数据、断开关联"原则不动
    fun deleteCourse(id: String): Boolean = transaction {
        if (courseRepository.findCourse(id) == null) return@transaction false
        Classes.deleteWhere { Classes.courseId eq id }
        Courses.deleteWhere { Courses.id eq id }
        true
    }

    // 班级列表：不传 courseId 拉全部
    fun listClasses(courseId: String?): List<AdminClassDTO> = transaction {
        val ids = if (courseId.isNullOrBlank()) {
            Classes.selectAll().orderBy(Classes.createdAt).map { it[Classes.id] }
        } else {
            Classes.selectAll().where { Classes.courseId eq courseId }.map { it[Classes.id] }
        }
        ids.mapNotNull { cid ->
            teacherRepository.findClass(cid)?.let { c ->
                AdminClassDTO(
                    id = c.id, name = c.name, courseId = c.courseId, courseName = c.courseName,
                    teacherId = c.teacherId, teacherName = c.teacherName, term = c.term,
                    inviteCode = c.inviteCode, studentCount = c.studentIds.size, createdAt = c.createdAt
                )
            }
        }
    }

    fun createClass(req: CreateClassRequest): AdminClassDTO {
        if (req.courseId.isBlank() || req.name.isBlank()) throw IllegalArgumentException("courseId 与班级名必填")
        val course = courseRepository.findCourse(req.courseId) ?: throw IllegalArgumentException("课程不存在")
        val id = teacherRepository.nextId("cls")
        val room = ClassRoom(
            id = id, name = req.name, courseId = req.courseId, courseName = course.name,
            teacherId = req.teacherId, teacherName = "", term = req.term,
            inviteCode = teacherRepository.generateInviteCode(), createdAt = fmt.format(LocalDateTime.now())
        )
        transaction { teacherRepository.saveClass(room) }
        return AdminClassDTO(id, req.name, req.courseId, course.name, req.teacherId, "", req.term, room.inviteCode, 0, room.createdAt)
    }

    fun updateClass(id: String, req: AdminUpdateClassRequest): Boolean {
        val c = teacherRepository.findClass(id) ?: return false
        val updated = c.copy(
            name = req.name ?: c.name,
            term = req.term ?: c.term,
            teacherId = req.teacherId ?: c.teacherId
        )
        transaction { teacherRepository.saveClass(updated) }
        return true
    }

    fun deleteClass(id: String): Boolean = teacherRepository.deleteClass(id)

    // 班级名单：学生 id + 入班时间 + 名字（从 users 表补昵称/用户名）
    fun listClassStudents(classId: String): List<AdminClassStudentDTO> = transaction {
        ClassStudents.selectAll().where { ClassStudents.classId eq classId }.map { row ->
            val sid = row[ClassStudents.studentId]
            val u = Users.selectAll().where { Users.id eq sid }.firstOrNull()
            val name = u?.let { it[Users.nickname] ?: it[Users.username] } ?: sid
            AdminClassStudentDTO(sid, name, row[ClassStudents.joinedAt])
        }
    }

    fun addStudentToClass(classId: String, studentId: String): Boolean {
        if (userRepository.findUserById(studentId) == null) return false // 防脏数据
        return teacherRepository.addStudent(classId, studentId)
    }

    fun removeStudentFromClass(classId: String, studentId: String): Boolean =
        teacherRepository.removeClassStudent(classId, studentId)

    // ---------- 作业与批阅（§5.5） ----------
    fun listAssignments(courseId: String?, from: String?, to: String?, keyword: String?): List<AdminAssignmentDTO> = transaction {
        assignmentRepository.listAssignments(courseId).filter { a ->
            (from == null || a.createdAt >= from) &&
            (to == null || a.createdAt <= to) &&
            (keyword.isNullOrBlank() || a.title.contains(keyword, true) || a.description.contains(keyword, true))
        }.map { a ->
            val subs = Submissions.selectAll().where { Submissions.assignmentId eq a.id }.count().toInt()
            AdminAssignmentDTO(
                id = a.id, courseId = a.courseId, courseName = courseRepository.findCourse(a.courseId)?.name ?: "",
                title = a.title, description = a.description, deadline = a.deadline,
                totalScore = a.totalScore, publisher = a.publisher, createdAt = a.createdAt,
                questionCount = a.questions.size, submissionCount = subs
            )
        }
    }

    fun getAssignment(id: String): AdminAssignmentDetailDTO? = transaction {
        val a = assignmentRepository.findAssignment(id) ?: return@transaction null
        val subs = Submissions.selectAll().where { Submissions.assignmentId eq a.id }.toList()
        val reviewed = subs.count { it[Submissions.status] == "REVIEWED" }
        val pending = subs.count { it[Submissions.status] == "PENDING" }
        val avg = subs.mapNotNull { it[Submissions.score] }.let { if (it.isEmpty()) null else it.average() }
        AdminAssignmentDetailDTO(
            id = a.id, courseId = a.courseId, courseName = courseRepository.findCourse(a.courseId)?.name ?: "",
            title = a.title, description = a.description, deadline = a.deadline,
            totalScore = a.totalScore, knowledgePoints = a.knowledgePoints, publisher = a.publisher,
            createdAt = a.createdAt, questions = a.questions,
            submissionCount = subs.size, reviewedCount = reviewed, pendingCount = pending, avgScore = avg
        )
    }

    // 删作业：保留提交记录（断开关联），只删作业定义
    fun deleteAssignment(id: String): Boolean = transaction {
        if (assignmentRepository.findAssignment(id) == null) return@transaction false
        Assignments.deleteWhere { Assignments.id eq id }
        true
    }

    fun listSubmissions(assignmentId: String): List<AdminSubmissionDTO> = transaction {
        assignmentRepository.listSubmissions(assignmentId).map { s ->
            AdminSubmissionDTO(
                id = s.id, assignmentId = s.assignmentId, studentId = s.studentId, studentName = s.studentName,
                version = s.version, submittedAt = s.submittedAt, status = s.status, score = s.score,
                comment = s.comment, reviewer = s.reviewer, reviewedAt = s.reviewedAt
            )
        }
    }

    // 人工覆盖批阅：分数/评语/action，复用现行业务语义（ACCEPT 定分 / RETURN 打回）
    fun reviewSubmission(id: String, req: ReviewRequest): Boolean = transaction {
        if (Submissions.selectAll().where { Submissions.id eq id }.firstOrNull() == null) return@transaction false
        val status = if (req.action == "RETURN") "RETURNED" else "REVIEWED"
        Submissions.update({ Submissions.id eq id }) {
            if (req.score != null) it[score] = req.score
            it[comment] = req.comment
            it[reviewer] = req.reviewer
            it[reviewedAt] = fmt.format(LocalDateTime.now())
            it[Submissions.status] = status
        }
        true
    }

    // ---------- 签到（§5.6） ----------
    fun listAttendanceSessions(courseId: String?, from: String?, to: String?): List<AdminAttendanceSessionDTO> = transaction {
        attendanceRepository.listSessions(courseId)
            .filter { (from == null || it.createdAt >= from) && (to == null || it.createdAt <= to) }
            .map { buildSessionDTO(it) }
    }

    fun getAttendanceSession(id: String): AdminAttendanceDetailDTO? = transaction {
        val s = attendanceRepository.findSession(id) ?: return@transaction null
        val sessionDTO = buildSessionDTO(s)
        val records = attendanceRepository.listRecords(id).map { r ->
            AdminAttendanceRecordDTO(
                id = r.id, studentId = r.studentId, studentName = r.studentName,
                checkedInAt = r.checkedInAt, status = r.status, method = r.method,
                secondsAfterStart = r.secondsAfterStart, riskScore = r.riskScore
            )
        }
        AdminAttendanceDetailDTO(sessionDTO, records)
    }

    // 导出 CSV：学号,姓名,状态,签到时间（含简易转义，防止逗号/引号破坏格式）
    fun attendanceCsv(id: String): String {
        val detail = getAttendanceSession(id) ?: return ""
        val sb = StringBuilder()
        sb.appendLine("studentId,studentName,status,checkedInAt")
        detail.records.forEach { r ->
            sb.appendLine("${r.studentId},${csvCell(r.studentName)},${r.status},${r.checkedInAt}")
        }
        return sb.toString()
    }

    private fun csvCell(v: String?): String {
        if (v.isNullOrBlank()) return ""
        return if (v.contains(',') || v.contains('"') || v.contains('\n')) "\"${v.replace("\"", "\"\"")}\"" else v
    }

    private fun buildSessionDTO(s: AttendanceSession): AdminAttendanceSessionDTO {
        val roster = attendanceRepository.rosterOf(s.courseId)
        val records = attendanceRepository.listRecords(s.id)
        val checked = records.size
        val late = records.count { it.status == "LATE" }
        val expected = roster.size
        val absent = (expected - checked).coerceAtLeast(0)
        val rate = if (expected == 0) 0.0 else checked.toDouble() / expected
        return AdminAttendanceSessionDTO(
            id = s.id, courseId = s.courseId, courseName = courseRepository.findCourse(s.courseId)?.name ?: "",
            title = s.title, mode = s.mode, state = s.state, publisher = s.publisher,
            createdAt = s.createdAt, expireAt = s.expireAt, expected = expected,
            checked = checked, late = late, absent = absent, checkRate = rate
        )
    }
}
