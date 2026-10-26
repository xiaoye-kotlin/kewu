package com.kewu.modules.roster

import com.kewu.db.ClassStudents
import com.kewu.db.Classes
import com.kewu.db.UserCourses
import com.kewu.db.Users
import com.kewu.models.ApiResponse
import com.kewu.security.JwtConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/*
 * 班级通讯录 + 移出学生（2026-10-26）：
 *   - 学生的手机 / 邮箱只给**该班任课老师**看（同学之间互相看不到）；
 *   - 学生加入班级后不能自行退出，只有任课老师能把学生移出（同时解绑该课程）；
 *   - /user/me 附带「我加入的班级」，学生端「班级」以此为准、不可手改。
 * 鉴权：都要 Bearer token，且 token 里的用户必须是这个班的老师。
 */

@Serializable
data class StudentContact(
    val userId: String,
    val studentNo: String,   // 学号（= 登录账号）
    val name: String,
    val school: String? = null,
    val phone: String? = null,
    val email: String? = null
)

@Serializable
data class JoinedClass(val id: String, val name: String, val courseId: String, val courseName: String, val teacherName: String)

object RosterService {

    private fun classOwner(classId: String): String? = transaction {
        Classes.selectAll().where { Classes.id eq classId }.firstOrNull()?.get(Classes.teacherId)
    }

    fun contacts(classId: String): List<StudentContact> = transaction {
        val ids = ClassStudents.selectAll().where { ClassStudents.classId eq classId }.map { it[ClassStudents.studentId] }
        ids.mapNotNull { id ->
            Users.selectAll().where { Users.id eq id }.firstOrNull()?.let {
                StudentContact(
                    userId = it[Users.id], studentNo = it[Users.username],
                    name = it[Users.nickname] ?: it[Users.username], school = it[Users.school],
                    phone = it[Users.phone], email = it[Users.email]
                )
            }
        }.sortedBy { it.studentNo }
    }

    /** 移出学生：删班级成员关系；若他不在同课程的其它班里，同时解绑该课程。 */
    fun remove(classId: String, studentId: String): Boolean = transaction {
        val courseId = Classes.selectAll().where { Classes.id eq classId }.firstOrNull()?.get(Classes.courseId) ?: return@transaction false
        val n = ClassStudents.deleteWhere { (ClassStudents.classId eq classId) and (ClassStudents.studentId eq studentId) }
        val otherClasses = Classes.selectAll().where { Classes.courseId eq courseId }.map { it[Classes.id] }.filter { it != classId }
        val stillIn = otherClasses.any { cid ->
            !ClassStudents.selectAll().where { (ClassStudents.classId eq cid) and (ClassStudents.studentId eq studentId) }.empty()
        }
        if (!stillIn) UserCourses.deleteWhere { (UserCourses.userId eq studentId) and (UserCourses.courseId eq courseId) }
        n > 0
    }

    fun joinedClasses(userId: String): List<JoinedClass> = transaction {
        val ids = ClassStudents.selectAll().where { ClassStudents.studentId eq userId }.map { it[ClassStudents.classId] }
        ids.mapNotNull { cid ->
            Classes.selectAll().where { Classes.id eq cid }.firstOrNull()?.let {
                JoinedClass(it[Classes.id], it[Classes.name], it[Classes.courseId], it[Classes.courseName], it[Classes.teacherName])
            }
        }
    }

    /** token 里的用户是不是这个班的老师（不是就回 403）。 */
    suspend fun requireOwner(call: RoutingCall, classId: String): Boolean {
        val uid = call.principal<JWTPrincipal>()?.payload?.getClaim(JwtConfig.CLAIM_USER_ID)?.asString()
        val owner = classOwner(classId)
        if (owner == null) {
            call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, "班级不存在")); return false
        }
        if (uid == null || uid != owner) {
            call.respond(HttpStatusCode.Forbidden, ApiResponse<String>(1, "只有该班任课老师可以查看 / 管理学生")); return false
        }
        return true
    }
}

fun Route.rosterRoutes() {
    authenticate(JwtConfig.NAME) {
        // 班级通讯录（含手机 / 邮箱）：只给该班老师
        get("/api/teacher/classes/{classId}/contacts") {
            val classId = call.parameters["classId"]!!
            if (!RosterService.requireOwner(call, classId)) return@get
            call.respond(ApiResponse(0, "ok", RosterService.contacts(classId)))
        }
        // 把学生移出班级（学生不能自行退出）
        post("/api/teacher/classes/{classId}/students/{studentId}/remove") {
            val classId = call.parameters["classId"]!!
            val sid = call.parameters["studentId"]!!
            if (!RosterService.requireOwner(call, classId)) return@post
            if (RosterService.remove(classId, sid)) call.respond(ApiResponse(0, "已移出班级", true))
            else call.respond(HttpStatusCode.NotFound, ApiResponse<String>(1, "该学生不在这个班"))
        }
    }
}
