package com.kewu.modules.user.repository

import com.kewu.db.UserCourses
import com.kewu.db.Users
import com.kewu.modules.user.models.User
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

// 用户仓储：MySQL 持久化，方法签名和原来内存版一致，上层无感
class UserRepository {
    fun findByUsername(username: String): User? = transaction {
        Users.selectAll().where { Users.username eq username }.firstOrNull()?.toUser()
    }

    fun findUserById(id: String): User? = transaction {
        Users.selectAll().where { Users.id eq id }.firstOrNull()?.toUser()
    }

    fun createUser(user: User): User {
        transaction {
            Users.insert {
                it[id] = user.id
                it[username] = user.username
                it[passwordHash] = user.passwordHash
                it[nickname] = user.nickname
                it[role] = user.role
            }
        }
        return user
    }

    // 只管把课程 id 绑到用户身上, 课程存不存在交给课程模块去查
    fun bindCourse(userId: String, courseId: String): Boolean {
        val user = findUserById(userId) ?: return false
        transaction {
            UserCourses.deleteWhere { (UserCourses.userId eq userId) and (UserCourses.courseId eq courseId) }
            UserCourses.insert {
                it[UserCourses.userId] = userId
                it[UserCourses.courseId] = courseId
            }
        }
        return true
    }

    fun getBoundCourseIds(userId: String): List<String> = transaction {
        UserCourses.selectAll().where { UserCourses.userId eq userId }
            .map { it[UserCourses.courseId] }
    }

    private fun ResultRow.toUser(): User {
        val uid = this[Users.id]
        val courseIds = transaction {
            UserCourses.selectAll().where { UserCourses.userId eq uid }
                .map { it[UserCourses.courseId] }
                .toMutableSet()
        }
        return User(
            id = uid,
            username = this[Users.username],
            passwordHash = this[Users.passwordHash],
            nickname = this[Users.nickname],
            role = this[Users.role],
            courseIds = courseIds
        )
    }
}
