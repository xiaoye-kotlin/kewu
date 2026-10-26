package com.kewu.modules.teacher.repository

import com.kewu.db.ClassStates
import com.kewu.db.ClassStudents
import com.kewu.db.Classes
import com.kewu.modules.teacher.models.ClassRoom
import com.kewu.modules.teacher.models.ClassState
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

// 班级的数据库仓储, 方法签名和内存版一致
class TeacherRepository {
    fun saveClass(c: ClassRoom) {
        transaction {
            val exists = !Classes.selectAll().where { Classes.id eq c.id }.empty()
            if (exists) {
                Classes.update({ Classes.id eq c.id }) {
                    it[name] = c.name
                    it[courseId] = c.courseId
                    it[courseName] = c.courseName
                    it[teacherId] = c.teacherId
                    it[teacherName] = c.teacherName
                    it[term] = c.term
                    it[inviteCode] = c.inviteCode
                    it[createdAt] = c.createdAt
                }
            } else {
                Classes.insert {
                    it[id] = c.id
                    it[name] = c.name
                    it[courseId] = c.courseId
                    it[courseName] = c.courseName
                    it[teacherId] = c.teacherId
                    it[teacherName] = c.teacherName
                    it[term] = c.term
                    it[inviteCode] = c.inviteCode
                    it[createdAt] = c.createdAt
                }
            }
            // 学生名单单独存关联表
            ClassStudents.deleteWhere { ClassStudents.classId eq c.id }
            c.studentIds.forEach { sid ->
                ClassStudents.insert {
                    it[classId] = c.id
                    it[studentId] = sid
                }
            }
        }
    }

    fun findClass(id: String): ClassRoom? = transaction {
        Classes.selectAll().where { Classes.id eq id }.firstOrNull()?.let { row ->
            loadClassRoom(row[Classes.id])
        }
    }

    fun findByCode(code: String): ClassRoom? = transaction {
        Classes.selectAll().where { Classes.inviteCode eq code.trim().uppercase() }
            .firstOrNull()?.let { row -> loadClassRoom(row[Classes.id]) }
    }

    fun codeTaken(code: String): Boolean = transaction {
        !Classes.selectAll().where { Classes.inviteCode eq code }.empty()
    }

    fun listByTeacher(teacherId: String): List<ClassRoom> = transaction {
        Classes.selectAll().where { Classes.teacherId eq teacherId }
            .orderBy(Classes.createdAt)
            .map { loadClassRoom(it[Classes.id]) }
    }

    fun addStudent(classId: String, studentId: String): Boolean {
        val c = findClass(classId) ?: return false
        if (c.studentIds.contains(studentId)) return true
        transaction {
            ClassStudents.insert {
                it[ClassStudents.classId] = classId
                it[ClassStudents.studentId] = studentId
            }
        }
        return true
    }

    // 生成 6 位邀请码(去掉容易看错的 0/O/1/I), 撞了就再来一次
    fun generateInviteCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        repeat(50) {
            val code = (1..6).map { chars.random() }.joinToString("")
            if (!codeTaken(code)) return code
        }
        return (1..6).map { chars.random() }.joinToString("")
    }

    // 课堂开关状态(开课/下课), 按课程 id 存
    fun saveState(s: ClassState) {
        transaction {
            val exists = !ClassStates.selectAll().where { ClassStates.courseId eq s.courseId }.empty()
            if (exists) {
                ClassStates.update({ ClassStates.courseId eq s.courseId }) {
                    it[courseName] = s.courseName
                    it[state] = s.state
                    it[teacherId] = s.teacherId
                    it[teacherName] = s.teacherName
                    it[startedAt] = s.startedAt
                    it[endedAt] = s.endedAt
                    it[updatedAt] = s.updatedAt
                }
            } else {
                ClassStates.insert {
                    it[courseId] = s.courseId
                    it[courseName] = s.courseName
                    it[state] = s.state
                    it[teacherId] = s.teacherId
                    it[teacherName] = s.teacherName
                    it[startedAt] = s.startedAt
                    it[endedAt] = s.endedAt
                    it[updatedAt] = s.updatedAt
                }
            }
        }
    }

    fun findState(courseId: String): ClassState? = transaction {
        ClassStates.selectAll().where { ClassStates.courseId eq courseId }.firstOrNull()?.let {
            ClassState(
                courseId = it[ClassStates.courseId],
                courseName = it[ClassStates.courseName],
                state = it[ClassStates.state],
                teacherId = it[ClassStates.teacherId],
                teacherName = it[ClassStates.teacherName],
                startedAt = it[ClassStates.startedAt],
                endedAt = it[ClassStates.endedAt],
                updatedAt = it[ClassStates.updatedAt]
            )
        }
    }

    fun listStates(): List<ClassState> = transaction {
        ClassStates.selectAll().map {
            ClassState(
                courseId = it[ClassStates.courseId],
                courseName = it[ClassStates.courseName],
                state = it[ClassStates.state],
                teacherId = it[ClassStates.teacherId],
                teacherName = it[ClassStates.teacherName],
                startedAt = it[ClassStates.startedAt],
                endedAt = it[ClassStates.endedAt],
                updatedAt = it[ClassStates.updatedAt]
            )
        }
    }

    fun nextId(prefix: String): String = "$prefix${System.nanoTime().toString().takeLast(6)}"

    private fun loadClassRoom(id: String): ClassRoom {
        val row = Classes.selectAll().where { Classes.id eq id }.first()
        val students = ClassStudents.selectAll().where { ClassStudents.classId eq id }
            .map { it[ClassStudents.studentId] }
        return ClassRoom(
            id = row[Classes.id],
            name = row[Classes.name],
            courseId = row[Classes.courseId],
            courseName = row[Classes.courseName],
            teacherId = row[Classes.teacherId],
            teacherName = row[Classes.teacherName],
            term = row[Classes.term],
            inviteCode = row[Classes.inviteCode],
            studentIds = students.toMutableSet(),
            createdAt = row[Classes.createdAt]
        )
    }
}
