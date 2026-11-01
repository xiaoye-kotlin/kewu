package com.kewu.modules.course.routes

import com.kewu.db.ClassStudents
import com.kewu.db.Classes
import com.kewu.db.Courses
import com.kewu.db.UserCourses
import com.kewu.db.Users
import com.kewu.models.ApiResponse
import com.kewu.modules.course.service.CourseService
import com.kewu.security.JwtConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class TeacherContact(
    val teacherId: String? = null,
    val teacherName: String? = null,
    val phone: String? = null,
    val email: String? = null,
)

private data class ContactResult(
    val status: HttpStatusCode? = null,
    val message: String? = null,
    val contact: TeacherContact? = null,
)

// 课程模块的接口: 课程列表、课程详情、课堂纪要查询
fun Routing.courseRoutes(courseService: CourseService) {
    route("/api/courses") {
        // 课程列表
        get {
            call.respond(ApiResponse(0, "ok", courseService.listCourses()))
        }

        // 单门课程详情
        get("{courseId}") {
            val courseId = call.parameters["courseId"]!!
            courseService.getCourse(courseId).fold(
                onSuccess = { call.respond(ApiResponse(0, "ok", it)) },
                onFailure = { call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, it.message ?: "课程不存在")) }
            )
        }

        // 某门课的课堂纪要列表
        get("{courseId}/notes") {
            val courseId = call.parameters["courseId"]!!
            courseService.listNotes(courseId).fold(
                onSuccess = { call.respond(ApiResponse(0, "ok", it)) },
                onFailure = { call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, it.message ?: "查询失败")) }
            )
        }

        // 某门课的单条课堂纪要
        get("{courseId}/notes/{noteId}") {
            val courseId = call.parameters["courseId"]!!
            val noteId = call.parameters["noteId"]!!
            courseService.getNote(courseId, noteId).fold(
                onSuccess = { call.respond(ApiResponse(0, "ok", it)) },
                onFailure = { call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, it.message ?: "纪要不存在")) }
            )
        }

        authenticate(JwtConfig.NAME) {
            // 任课教师联系方式：只给已绑定课程、已加入该课程班级或任课教师本人看。
            get("{courseId}/teacher-contact") {
                val courseId = call.parameters["courseId"]!!
                val uid = call.principal<JWTPrincipal>()?.payload?.getClaim(JwtConfig.CLAIM_USER_ID)?.asString()
                if (uid.isNullOrBlank()) {
                    call.respond(HttpStatusCode.Unauthorized, ApiResponse<String>(1, "请先登录"))
                    return@get
                }
                val result = teacherContact(courseId, uid)
                if (result.status != null) {
                    call.respond(result.status, ApiResponse<String>(1, result.message ?: "暂时无法获取教师联系方式"))
                } else {
                    call.respond(ApiResponse(0, "ok", result.contact))
                }
            }
        }
    }
}

private fun teacherContact(courseId: String, userId: String): ContactResult = transaction {
    val course = Courses.selectAll().where { Courses.id eq courseId }.firstOrNull()
        ?: return@transaction ContactResult(HttpStatusCode.NotFound, "课程不存在")
    val me = Users.selectAll().where { Users.id eq userId }.firstOrNull()
        ?: return@transaction ContactResult(HttpStatusCode.Forbidden, "当前账号无权查看教师联系方式")

    val classRows = Classes.selectAll().where { Classes.courseId eq courseId }.toList()
    val joinedClass = classRows.firstOrNull { row ->
        !ClassStudents.selectAll().where {
            (ClassStudents.classId eq row[Classes.id]) and (ClassStudents.studentId eq userId)
        }.empty()
    }
    val boundCourse = !UserCourses.selectAll().where {
        (UserCourses.userId eq userId) and (UserCourses.courseId eq courseId)
    }.empty()
    val ownsCourseClass = classRows.any { it[Classes.teacherId] == userId }
    val isAdmin = me[Users.role].equals("ADMIN", ignoreCase = true)

    if (!isAdmin && !ownsCourseClass && !boundCourse && joinedClass == null) {
        return@transaction ContactResult(HttpStatusCode.Forbidden, "只有这门课的学生或任课教师可以查看联系方式")
    }

    val chosenClass = when {
        ownsCourseClass -> classRows.firstOrNull { it[Classes.teacherId] == userId }
        joinedClass != null -> joinedClass
        else -> classRows.firstOrNull()
    }
    val fallbackTeacher = if (chosenClass == null) findBoundTeacher(courseId) else null
    val teacherId = chosenClass?.get(Classes.teacherId) ?: fallbackTeacher?.get(Users.id)
    val teacherUser = teacherId?.let { id -> Users.selectAll().where { Users.id eq id }.firstOrNull() }
    val teacherName = teacherUser?.let { it[Users.nickname] ?: it[Users.username] }
        ?: chosenClass?.get(Classes.teacherName)?.ifBlank { null }
        ?: course[Courses.teacher].ifBlank { null }

    ContactResult(
        contact = TeacherContact(
            teacherId = teacherId,
            teacherName = teacherName,
            phone = teacherUser?.get(Users.phone),
            email = teacherUser?.get(Users.email),
        )
    )
}

/** 老课程可能还没有教学班：从课程绑定关系里找角色为 TEACHER 的用户兜底。 */
private fun findBoundTeacher(courseId: String): ResultRow? {
    val ids = UserCourses.selectAll().where { UserCourses.courseId eq courseId }.map { it[UserCourses.userId] }
    return ids.firstNotNullOfOrNull { id ->
        Users.selectAll().where { Users.id eq id }.firstOrNull()
            ?.takeIf { it[Users.role].equals("TEACHER", ignoreCase = true) }
    }
}
