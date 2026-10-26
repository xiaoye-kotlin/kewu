package com.kewu.modules.course.models

import kotlinx.serialization.Serializable

// 课程模块的数据模型

// 课程内部实体, 比对外结构多了点可扩展字段
data class Course(
    val id: String,
    val name: String,
    val description: String = "",
    val teacher: String = "",
    val coverUrl: String? = null
)

// 课程对外结构
@Serializable
data class CourseInfo(
    val id: String,
    val name: String,
    val description: String = "",
    val teacher: String = "",
    val coverUrl: String? = null
)

// 课堂纪要内部实体
data class ClassNote(
    val id: String,
    val courseId: String,
    val title: String,
    val content: String,
    val author: String = "",
    val createdAt: String
)

// 课堂纪要对外结构
@Serializable
data class ClassNoteInfo(
    val id: String,
    val courseId: String,
    val title: String,
    val content: String,
    val author: String = "",
    val createdAt: String
)

// 内部实体转对外结构的小工具
fun Course.toInfo() = CourseInfo(id, name, description, teacher, coverUrl)
fun ClassNote.toInfo() = ClassNoteInfo(id, courseId, title, content, author, createdAt)
