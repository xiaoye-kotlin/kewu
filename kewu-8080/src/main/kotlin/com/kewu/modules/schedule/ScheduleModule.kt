package com.kewu.modules.schedule

import com.kewu.db.ClassStudents
import com.kewu.db.Classes
import com.kewu.db.CourseSchedules
import com.kewu.db.Courses
import com.kewu.db.CrashReports
import com.kewu.db.UserCourses
import com.kewu.db.Users
import com.kewu.models.ApiResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/*
 * 课表模块（2026-10-26）：老师建班后导入课表（文件 / AI 识别 / 手填），每节课带时间、地点、任课老师。
 *   - 任课老师按「姓名 + 学校」自动匹配：已注册的老师立刻绑定；还没注册的，等他用同名同校注册 / 登录时自动认领；
 *   - 学生看自己所在班级的课表，老师看自己教的课表；
 *   - 同时提供崩溃上报（App 下次启动时把上次崩溃的原因传上来，方便定位手机上才出现的闪退）。
 */

@Serializable
data class ScheduleEntry(
    val id: String = "",
    val classId: String? = null,
    val className: String = "",
    val courseId: String = "",
    val courseName: String = "",
    val teacherName: String = "",
    val teacherId: String? = null,
    val school: String? = null,
    val weekday: Int = 1,          // 1=周一 … 7=周日
    val startTime: String = "",    // "08:00"
    val endTime: String = "",      // "09:40"
    val location: String = "",
    val weeks: String = ""         // "1-16" / "1-8,10-16" / 空 = 每周
)

@Serializable
data class ImportScheduleRequest(
    val teacherId: String = "",    // 导入人（老师 id 或账号）
    val classId: String? = null,   // 导入到哪个教学班（学生据此看到课表）
    val replace: Boolean = true,   // true = 先清掉该班已有课表再导入
    val entries: List<ScheduleEntry> = emptyList()
)

@Serializable
data class ImportScheduleResult(
    val imported: Int,
    val boundTeachers: List<String> = emptyList(),   // 已自动绑定到的老师
    val pendingTeachers: List<String> = emptyList()  // 还没注册、等其同名同校注册后自动认领的老师
)

@Serializable
data class CrashReport(
    val id: String = "",
    val user: String = "",
    val device: String = "",
    val appVersion: String = "",
    val reason: String = "",
    val detail: String = "",
    val happenedAt: String = "",
    val receivedAt: String = ""
)

private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val timeRe = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")

private fun normTime(t: String): String? {
    val m = timeRe.find(t.trim().replace("：", ":")) ?: return null
    return m.groupValues[1].padStart(2, '0') + ":" + m.groupValues[2]
}

object ScheduleService {

    private data class UserLite(val id: String, val username: String, val nickname: String?, val role: String, val school: String?)

    private fun findUser(idOrName: String): UserLite? = transaction {
        Users.selectAll().where { (Users.id eq idOrName) or (Users.username eq idOrName) }.firstOrNull()?.let {
            UserLite(it[Users.id], it[Users.username], it[Users.nickname], it[Users.role], it[Users.school])
        }
    }

    /** 同名同校（对方没填学校也算）的老师账号。 */
    private fun findTeacherByName(name: String, school: String?): UserLite? = transaction {
        Users.selectAll().where { (Users.nickname eq name) and (Users.role eq "TEACHER") }
            .map { UserLite(it[Users.id], it[Users.username], it[Users.nickname], it[Users.role], it[Users.school]) }
            .firstOrNull { school.isNullOrBlank() || it.school.isNullOrBlank() || it.school == school }
    }

    private fun bindCourse(userId: String, courseId: String) = transaction {
        val has = !UserCourses.selectAll().where { (UserCourses.userId eq userId) and (UserCourses.courseId eq courseId) }.empty()
        if (!has) UserCourses.insert { it[UserCourses.userId] = userId; it[UserCourses.courseId] = courseId }
    }

    /** 按课程名 + 任课老师找课程，找不到就建一门。 */
    private fun findOrCreateCourse(name: String, teacher: String): String = transaction {
        Courses.selectAll().where { (Courses.name eq name) and (Courses.teacher eq teacher) }.firstOrNull()?.get(Courses.id)
            ?: ("c" + System.nanoTime().toString().takeLast(6)).also { id ->
                Courses.insert {
                    it[Courses.id] = id
                    it[Courses.name] = name
                    it[description] = "由课表导入"
                    it[Courses.teacher] = teacher
                    it[coverUrl] = null
                }
            }
    }

    fun import(req: ImportScheduleRequest): Result<ImportScheduleResult> {
        val importer = findUser(req.teacherId) ?: return Result.failure(IllegalArgumentException("导入人账号不存在"))
        if (req.entries.isEmpty()) return Result.failure(IllegalArgumentException("课表是空的"))
        if (req.entries.size > 200) return Result.failure(IllegalArgumentException("一次最多导入 200 节课"))
        val cls = req.classId?.let { cid ->
            transaction { Classes.selectAll().where { Classes.id eq cid }.firstOrNull() }
                ?: return Result.failure(IllegalArgumentException("班级不存在"))
        }
        val school = importer.school
        val importerName = importer.nickname ?: importer.username
        // 逐条校验，坏数据直接告诉老师是第几条
        req.entries.forEachIndexed { i, e ->
            if (e.courseName.isBlank()) return Result.failure(IllegalArgumentException("第 ${i + 1} 节：缺少课程名称"))
            if (e.weekday !in 1..7) return Result.failure(IllegalArgumentException("第 ${i + 1} 节：星期应为 1–7"))
            if (normTime(e.startTime) == null || normTime(e.endTime) == null)
                return Result.failure(IllegalArgumentException("第 ${i + 1} 节：时间格式应为 08:00"))
        }
        val bound = linkedSetOf<String>()
        val pending = linkedSetOf<String>()
        val now = LocalDateTime.now().format(fmt)
        transaction {
            val cid = req.classId
            if (req.replace && cid != null) {
                CourseSchedules.deleteWhere { CourseSchedules.classId eq cid }
            }
        }
        req.entries.forEach { e ->
            val teacherName = e.teacherName.trim().ifBlank { importerName }
            val courseName = e.courseName.trim()
            // 该班本课（与建班时的课程同名）直接用班级的课程 id
            val courseId = if (cls != null && cls[Classes.courseName] == courseName) cls[Classes.courseId]
            else findOrCreateCourse(courseName, teacherName)
            val teacher = if (teacherName == importerName) importer else findTeacherByName(teacherName, school)
            if (teacher != null) {
                bindCourse(teacher.id, courseId)
                bound += teacherName
            } else pending += teacherName
            transaction {
                CourseSchedules.insert {
                    it[id] = "sc_" + UUID.randomUUID().toString().replace("-", "").take(12)
                    it[classId] = req.classId
                    it[className] = cls?.get(Classes.name) ?: ""
                    it[CourseSchedules.courseId] = courseId
                    it[CourseSchedules.courseName] = courseName
                    it[CourseSchedules.teacherName] = teacherName
                    it[teacherId] = teacher?.id
                    it[CourseSchedules.school] = school
                    it[weekday] = e.weekday
                    it[startTime] = normTime(e.startTime)!!
                    it[endTime] = normTime(e.endTime)!!
                    it[location] = e.location.trim().take(128)
                    it[weeks] = e.weeks.trim().take(64)
                    it[createdBy] = importer.id
                    it[createdAt] = now
                }
            }
        }
        // 先建班后导课表的班级：回填班级关联课程 = 导入人自己任教的那门（没有就取第一门），控制台据此显示「今日课程」
        if (cls != null && cls[Classes.courseId].isBlank()) {
            val own = transaction {
                CourseSchedules.selectAll().where { CourseSchedules.classId eq cls[Classes.id] }.toList()
            }.let { rows -> rows.firstOrNull { it[CourseSchedules.teacherName] == importerName } ?: rows.firstOrNull() }
            if (own != null) transaction {
                Classes.update({ Classes.id eq cls[Classes.id] }) {
                    it[courseId] = own[CourseSchedules.courseId]
                    it[courseName] = own[CourseSchedules.courseName]
                }
            }
        }
        // 班里的学生也绑上这些课，学生端课程列表直接可见
        if (cls != null) {
            val students = transaction { ClassStudents.selectAll().where { ClassStudents.classId eq cls[Classes.id] }.map { it[ClassStudents.studentId] } }
            val courseIds = transaction { CourseSchedules.selectAll().where { CourseSchedules.classId eq cls[Classes.id] }.map { it[CourseSchedules.courseId] }.distinct() }
            students.forEach { s -> courseIds.forEach { c -> bindCourse(s, c) } }
        }
        return Result.success(ImportScheduleResult(req.entries.size, bound.toList(), (pending - bound).toList()))
    }

    /**
     * 老师注册 / 登录时调用：把「任课老师 = 我的姓名、学校相同（或课表没写学校）」且还没认领的课表认领过来并绑定课程。
     */
    fun autoBindTeacher(userId: String): Int {
        val u = findUser(userId) ?: return 0
        if (u.role != "TEACHER") return 0
        val name = u.nickname ?: return 0
        val rows = transaction {
            CourseSchedules.selectAll().where { (CourseSchedules.teacherName eq name) and (CourseSchedules.teacherId.isNull()) }
                .filter { r -> r[CourseSchedules.school].isNullOrBlank() || u.school.isNullOrBlank() || r[CourseSchedules.school] == u.school }
                .map { it[CourseSchedules.id] to it[CourseSchedules.courseId] }
        }
        rows.forEach { (sid, cid) ->
            transaction { CourseSchedules.update({ CourseSchedules.id eq sid }) { it[teacherId] = u.id } }
            bindCourse(u.id, cid)
        }
        return rows.size
    }

    /** 某班课表里出现的全部课程 id（学生加班时一并绑定）。 */
    fun courseIdsOfClass(classId: String): List<String> = transaction {
        CourseSchedules.selectAll().where { CourseSchedules.classId eq classId }.map { it[CourseSchedules.courseId] }.distinct()
    }

    /** 我的课表：学生 = 所在班级的课表 + 已绑课程的课表；老师 = 我任教的。 */
    fun mySchedule(userIdOrName: String): List<ScheduleEntry> {
        val u = findUser(userIdOrName) ?: return emptyList()
        val rows = transaction {
            if (u.role == "TEACHER") {
                CourseSchedules.selectAll().where {
                    (CourseSchedules.teacherId eq u.id) or (CourseSchedules.teacherName eq (u.nickname ?: u.username))
                }.toList()
            } else {
                val classIds = ClassStudents.selectAll().where { ClassStudents.studentId eq u.id }.map { it[ClassStudents.classId] }
                val courseIds = UserCourses.selectAll().where { UserCourses.userId eq u.id }.map { it[UserCourses.courseId] }
                CourseSchedules.selectAll().toList().filter { r ->
                    (r[CourseSchedules.classId] != null && r[CourseSchedules.classId] in classIds) || r[CourseSchedules.courseId] in courseIds
                }
            }
        }
        return rows.map { r ->
            ScheduleEntry(
                id = r[CourseSchedules.id], classId = r[CourseSchedules.classId], className = r[CourseSchedules.className],
                courseId = r[CourseSchedules.courseId], courseName = r[CourseSchedules.courseName],
                teacherName = r[CourseSchedules.teacherName], teacherId = r[CourseSchedules.teacherId],
                school = r[CourseSchedules.school], weekday = r[CourseSchedules.weekday],
                startTime = r[CourseSchedules.startTime], endTime = r[CourseSchedules.endTime],
                location = r[CourseSchedules.location], weeks = r[CourseSchedules.weeks]
            )
        }.distinctBy { it.courseId + it.weekday + it.startTime }.sortedWith(compareBy({ it.weekday }, { it.startTime }))
    }

    fun saveCrash(r: CrashReport) {
        transaction {
            CrashReports.insert {
                it[id] = "cr_" + UUID.randomUUID().toString().replace("-", "").take(12)
                it[user] = r.user.take(64)
                it[device] = r.device.take(128)
                it[appVersion] = r.appVersion.take(32)
                it[reason] = r.reason.take(64)
                it[detail] = r.detail.take(60_000)
                it[happenedAt] = r.happenedAt.take(32)
                it[receivedAt] = LocalDateTime.now().format(fmt)
            }
        }
        println("CRASH-REPORT: user=${r.user} device=${r.device} reason=${r.reason} at=${r.happenedAt}\n${r.detail.take(4000)}")
    }

    fun listCrashes(limit: Int): List<CrashReport> = transaction {
        CrashReports.selectAll().orderBy(CrashReports.receivedAt, SortOrder.DESC).limit(limit).map {
            CrashReport(
                it[CrashReports.id], it[CrashReports.user], it[CrashReports.device], it[CrashReports.appVersion],
                it[CrashReports.reason], it[CrashReports.detail], it[CrashReports.happenedAt], it[CrashReports.receivedAt]
            )
        }
    }
}

fun Route.scheduleRoutes() {
    // 导入课表（老师）：POST /api/teacher/schedules/import
    post("/api/teacher/schedules/import") {
        val req = call.receive<ImportScheduleRequest>()
        ScheduleService.import(req).fold(
            onSuccess = { call.respond(ApiResponse(0, "已导入 ${it.imported} 节课", it)) },
            onFailure = { call.respond(HttpStatusCode.BadRequest, ApiResponse<String>(1, it.message ?: "导入失败")) }
        )
    }
    // 我的课表：GET /api/schedules/my?userId=xxx（学生看所在班级的，老师看自己任教的）
    get("/api/schedules/my") {
        val uid = call.request.queryParameters["userId"].orEmpty()
        call.respond(ApiResponse(0, "ok", ScheduleService.mySchedule(uid)))
    }
    // 崩溃上报：POST /api/crash-reports；查看：GET /api/crash-reports?limit=20
    post("/api/crash-reports") {
        ScheduleService.saveCrash(call.receive<CrashReport>())
        call.respond(ApiResponse(0, "ok", true))
    }
    get("/api/crash-reports") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20
        call.respond(ApiResponse(0, "ok", ScheduleService.listCrashes(limit)))
    }
}
