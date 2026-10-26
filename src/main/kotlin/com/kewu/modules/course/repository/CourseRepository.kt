package com.kewu.modules.course.repository

import com.kewu.db.ClassNotes
import com.kewu.db.Courses
import com.kewu.modules.course.models.ClassNote
import com.kewu.modules.course.models.Course
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

// 课程和课堂纪要的数据库仓储, 方法签名和内存版一致
class CourseRepository {
    // 老师端新建课程时调用
    fun createCourse(course: Course) {
        transaction {
            Courses.insert {
                it[id] = course.id
                it[name] = course.name
                it[description] = course.description
                it[teacher] = course.teacher
                it[coverUrl] = course.coverUrl
            }
        }
    }

    fun listCourses(): List<Course> = transaction {
        Courses.selectAll().map {
            Course(it[Courses.id], it[Courses.name], it[Courses.description], it[Courses.teacher], it[Courses.coverUrl])
        }
    }

    fun findCourse(id: String): Course? = transaction {
        Courses.selectAll().where { Courses.id eq id }.firstOrNull()?.let {
            Course(it[Courses.id], it[Courses.name], it[Courses.description], it[Courses.teacher], it[Courses.coverUrl])
        }
    }

    fun checkExists(id: String): Boolean = transaction {
        !Courses.selectAll().where { Courses.id eq id }.empty()
    }

    fun findCoursesByIds(ids: List<String>): List<Course> = transaction {
        if (ids.isEmpty()) emptyList()
        else Courses.selectAll().where { Courses.id inList ids }.map {
            Course(it[Courses.id], it[Courses.name], it[Courses.description], it[Courses.teacher], it[Courses.coverUrl])
        }
    }

    fun listNotes(courseId: String): List<ClassNote> = transaction {
        ClassNotes.selectAll().where { ClassNotes.courseId eq courseId }.map {
            ClassNote(it[ClassNotes.id], it[ClassNotes.courseId], it[ClassNotes.title],
                it[ClassNotes.content], it[ClassNotes.author], it[ClassNotes.createdAt])
        }
    }

    fun findNote(courseId: String, noteId: String): ClassNote? = transaction {
        ClassNotes.selectAll().where { (ClassNotes.courseId eq courseId) and (ClassNotes.id eq noteId) }
            .firstOrNull()?.let {
                ClassNote(it[ClassNotes.id], it[ClassNotes.courseId], it[ClassNotes.title],
                    it[ClassNotes.content], it[ClassNotes.author], it[ClassNotes.createdAt])
            }
    }
}
