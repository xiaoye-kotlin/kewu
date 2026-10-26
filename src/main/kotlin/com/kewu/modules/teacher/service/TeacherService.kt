package com.kewu.modules.teacher.service

import com.kewu.modules.assignment.models.SubmissionStatus
import com.kewu.modules.assignment.repository.AssignmentRepository
import com.kewu.modules.attendance.repository.AttendanceRepository
import com.kewu.modules.course.models.Course
import com.kewu.modules.course.models.CourseInfo
import com.kewu.modules.course.models.toInfo
import com.kewu.modules.course.repository.CourseRepository
import com.kewu.modules.teacher.models.*
import com.kewu.modules.teacher.repository.TeacherRepository
import com.kewu.modules.user.models.UserRole
import com.kewu.modules.user.repository.UserRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// 教师端的业务逻辑: 建课 -> 建班(生成邀请码) -> 学生加班, 加上控制台聚合与开课下课
class TeacherService(
    private val repository: TeacherRepository,
    private val courseRepository: CourseRepository,
    private val userRepository: UserRepository,
    private val assignmentRepository: AssignmentRepository,
    private val attendanceRepository: AttendanceRepository
) {
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private fun now(): String = LocalDateTime.now().format(fmt)

    // id 或用户名都能查到用户, 前端测试时填哪个都成
    private fun resolveUser(idOrName: String) =
        userRepository.findUserById(idOrName) ?: userRepository.findByUsername(idOrName)

    // ---------- 建课程 ----------

    fun createCourse(req: CreateCourseRequest): Result<CourseInfo> {
        if (req.name.isBlank()) return Result.failure(IllegalArgumentException("课程名不能为空"))
        val teacher = req.teacherId.takeIf { it.isNotBlank() }?.let { resolveUser(it) }
        if (req.teacherId.isNotBlank() && teacher == null) {
            return Result.failure(IllegalArgumentException("老师账号不存在: ${req.teacherId}"))
        }
        val course = Course(
            id = repository.nextId("c"),
            name = req.name,
            description = req.description,
            teacher = req.teacher.ifBlank { teacher?.nickname ?: teacher?.username ?: "" }
        )
        courseRepository.createCourse(course)
        return Result.success(course.toInfo())
    }

    // ---------- 建班级(自动生成邀请码) ----------

    fun createClass(req: CreateClassRequest): Result<ClassRoomInfo> {
        val teacher = resolveUser(req.teacherId)
            ?: return Result.failure(IllegalArgumentException("老师账号不存在: ${req.teacherId}"))
        if (teacher.role != UserRole.TEACHER) {
            return Result.failure(IllegalArgumentException("这个账号不是老师, 只有老师账号能建班"))
        }
        if (req.name.isBlank()) return Result.failure(IllegalArgumentException("班级名不能为空"))
        val course = courseRepository.findCourse(req.courseId)
            ?: return Result.failure(IllegalArgumentException("课程不存在: ${req.courseId}"))

        val cls = ClassRoom(
            id = repository.nextId("k"),
            name = req.name,
            courseId = course.id,
            courseName = course.name,
            teacherId = teacher.id,
            teacherName = teacher.nickname ?: teacher.username,
            term = req.term,
            inviteCode = repository.generateInviteCode(),
            createdAt = now()
        )
        repository.saveClass(cls)
        return Result.success(cls.toInfo())
    }

    // ---------- 我教了哪些班哪些课 ----------

    fun overview(teacherIdOrName: String): Result<TeacherOverview> {
        val teacher = resolveUser(teacherIdOrName)
            ?: return Result.failure(IllegalArgumentException("老师账号不存在: $teacherIdOrName"))
        val list = repository.listByTeacher(teacher.id)
        val allStudents = list.flatMap { it.studentIds }.distinct()
        return Result.success(
            TeacherOverview(
                teacherId = teacher.id,
                teacherName = teacher.nickname ?: teacher.username,
                classCount = list.size,
                studentCount = allStudents.size,
                courseCount = list.map { it.courseId }.distinct().size,
                classes = list.map { it.toInfo() }
            )
        )
    }

    // ---------- 学生用邀请码加入班级 ----------

    fun joinClass(req: JoinClassRequest): Result<ClassRoomInfo> {
        val student = resolveUser(req.studentId)
            ?: return Result.failure(IllegalArgumentException("学生账号不存在: ${req.studentId}"))
        val cls = repository.findByCode(req.inviteCode)
            ?: return Result.failure(IllegalArgumentException("邀请码不对或已失效"))
        if (!cls.studentIds.add(student.id)) {
            return Result.failure(IllegalArgumentException("你已经在这个班里了"))
        }
        // 加班顺手把这门课绑到学生身上, 学生端课程列表就能看到
        userRepository.bindCourse(student.id, cls.courseId)
        return Result.success(cls.toInfo())
    }

    // ---------- 控制台聚合: 首屏三样一次给全 ----------

    fun console(teacherIdOrName: String): Result<TeacherConsole> {
        val teacher = resolveUser(teacherIdOrName)
            ?: return Result.failure(IllegalArgumentException("老师账号不存在: $teacherIdOrName"))
        val today = LocalDate.now().toString()
        val classes = repository.listByTeacher(teacher.id)
        val courseIds = classes.map { it.courseId }.distinct()

        // ① 今日课程
        val todayCourses = classes.map { cls ->
            val st = repository.findState(cls.courseId)
            val todaySessions = attendanceRepository.listSessions(cls.courseId)
                .filter { it.createdAt.startsWith(today) }
            val rate = if (todaySessions.isEmpty()) null else {
                val checked = todaySessions.sumOf { attendanceRepository.listRecords(it.id).size }
                val expected = todaySessions.sumOf { attendanceRepository.rosterOf(it.courseId).size }
                if (expected == 0) null else round2(checked.toDouble() / expected)
            }
            TodayCourseItem(
                courseId = cls.courseId,
                courseName = cls.courseName.ifBlank { courseRepository.findCourse(cls.courseId)?.name ?: cls.courseId },
                classId = cls.id,
                className = cls.name,
                studentCount = cls.studentIds.size,
                state = st?.state ?: ClassStateValue.OFF,
                startedAt = st?.startedAt,
                todayCheckRate = rate
            )
        }

        // ② 待批阅作业总数(红点就靠它)
        var pending = 0
        courseIds.forEach { cid ->
            assignmentRepository.listAssignments(cid).forEach { a ->
                pending += assignmentRepository.listSubmissions(a.id)
                    .count { it.status == SubmissionStatus.PENDING }
            }
        }

        // ③ 今日最新签到率
        val todaySessions = courseIds.flatMap { attendanceRepository.listSessions(it) }
            .filter { it.createdAt.startsWith(today) }
        val checkedToday = todaySessions.sumOf { attendanceRepository.listRecords(it.id).size }
        val expectedToday = todaySessions.sumOf { attendanceRepository.rosterOf(it.courseId).size }
        val rate = if (expectedToday == 0) 0.0 else checkedToday.toDouble() / expectedToday

        return Result.success(
            TeacherConsole(
                teacherId = teacher.id,
                teacherName = teacher.nickname ?: teacher.username,
                date = today,
                todayCourses = todayCourses,
                pendingReview = pending,
                hasPending = pending > 0,
                todayCheckRate = round2(rate),
                todaySessionCount = todaySessions.size,
                checkedToday = checkedToday,
                expectedToday = expectedToday
            )
        )
    }

    // ---------- 开课 / 下课 ----------

    fun setClassState(req: ClassStateRequest): Result<ClassState> {
        val teacher = resolveUser(req.teacherId)
            ?: return Result.failure(IllegalArgumentException("老师账号不存在: ${req.teacherId}"))
        val course = courseRepository.findCourse(req.courseId)
            ?: return Result.failure(IllegalArgumentException("课程不存在: ${req.courseId}"))
        val old = repository.findState(req.courseId)
        val time = now()

        val state = when (req.action.uppercase()) {
            "START" -> {
                if (old?.state == ClassStateValue.ON) {
                    return Result.failure(IllegalArgumentException("《${course.name}》已经在上课了"))
                }
                ClassState(
                    courseId = course.id, courseName = course.name,
                    state = ClassStateValue.ON, teacherId = teacher.id,
                    teacherName = teacher.nickname ?: teacher.username,
                    startedAt = time, endedAt = null, updatedAt = time
                )
            }
            "END" -> {
                if (old?.state != ClassStateValue.ON) {
                    return Result.failure(IllegalArgumentException("《${course.name}》还没开课"))
                }
                ClassState(
                    courseId = course.id, courseName = course.name,
                    state = ClassStateValue.OFF, teacherId = teacher.id,
                    teacherName = teacher.nickname ?: teacher.username,
                    startedAt = old.startedAt, endedAt = time, updatedAt = time
                )
            }
            else -> return Result.failure(IllegalArgumentException("action 只能是 START(开课) 或 END(下课)"))
        }
        repository.saveState(state)
        return Result.success(state)
    }

    // 查某门课当前是上课中还是没开
    fun getClassState(courseId: String): Result<ClassState> {
        val course = courseRepository.findCourse(courseId)
            ?: return Result.failure(IllegalArgumentException("课程不存在: $courseId"))
        val st = repository.findState(courseId)
            ?: return Result.success(
                ClassState(courseId = courseId, courseName = course.name, state = ClassStateValue.OFF)
            )
        return Result.success(st)
    }

    private fun round2(v: Double) = Math.round(v * 100) / 100.0

    // ---------- 班级学生名单 ----------

    fun classStudents(classId: String): Result<List<StudentBrief>> {
        val cls = repository.findClass(classId)
            ?: return Result.failure(IllegalArgumentException("班级不存在"))
        return Result.success(
            cls.studentIds.map { id ->
                val u = userRepository.findUserById(id)
                StudentBrief(id, u?.nickname ?: u?.username ?: id)
            }
        )
    }
}
