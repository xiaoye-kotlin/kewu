package com.kewu.modules.course.service

import com.kewu.modules.course.models.*
import com.kewu.modules.course.repository.CourseRepository

// 课程模块的业务逻辑: 列表、详情、课堂纪要查询都在这里
class CourseService(private val repository: CourseRepository) {

    // 课程列表
    fun listCourses(): List<CourseInfo> = repository.listCourses().map { it.toInfo() }

    // 单门课程详情
    fun getCourse(id: String): Result<CourseInfo> {
        val course = repository.findCourse(id)
            ?: return Result.failure(NoSuchElementException("课程不存在"))
        return Result.success(course.toInfo())
    }

    // 某门课的课堂纪要列表
    fun listNotes(courseId: String): Result<List<ClassNoteInfo>> {
        if (!repository.checkExists(courseId)) {
            return Result.failure(NoSuchElementException("课程不存在"))
        }
        return Result.success(repository.listNotes(courseId).map { it.toInfo() })
    }

    // 某门课的单条课堂纪要
    fun getNote(courseId: String, noteId: String): Result<ClassNoteInfo> {
        val note = repository.findNote(courseId, noteId)
            ?: return Result.failure(NoSuchElementException("课堂纪要不存在"))
        return Result.success(note.toInfo())
    }
}
