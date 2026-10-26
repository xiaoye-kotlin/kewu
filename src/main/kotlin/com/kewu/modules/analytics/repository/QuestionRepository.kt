package com.kewu.modules.analytics.repository

import com.kewu.db.Questions
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

// 查某门课“助教页/学生端”的提问记录。
// 容错：表不存在 / 查询异常都返回空列表，绝不把异常抛出去（上层据此返回空雷达）。
class QuestionRepository {
    private val log = LoggerFactory.getLogger(QuestionRepository::class.java)

    // 该 courseId 下，最近 50 条提问的正文。createdAt 是 "yyyy-MM-dd HH:mm" 字符串，倒序即时间近的在前。
    fun recentQuestions(courseId: String, limit: Int = 50): List<String> = try {
        transaction {
            Questions.selectAll()
                .where { Questions.courseId eq courseId }
                .orderBy(Questions.createdAt, SortOrder.DESC)
                .limit(limit)
                .map { it[Questions.content] }
        }
    } catch (e: Throwable) {
        log.warn("QuestionRepository: 查 questions 失败（courseId=$courseId），降级返回空：${e.message}")
        emptyList()
    }

    // 写入一条学生提问（学情雷达的数据源）。返回新插入的 id；失败返回空串。
    fun insertQuestion(
        courseId: String,
        content: String,
        studentId: String?,
        studentName: String?
    ): String = try {
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        transaction {
            Questions.insert {
                it[Questions.id] = id
                it[Questions.courseId] = courseId
                it[Questions.content] = content
                it[Questions.studentId] = studentId
                it[Questions.studentName] = studentName
                it[Questions.createdAt] = now
            }
        }
        id
    } catch (e: Throwable) {
        log.warn("QuestionRepository: 插入提问失败（courseId=$courseId）：${e.message}")
        ""
    }

    // 提问列表（老师/前端展示用），可按课程过滤；不传 courseId 看全部。
    fun listQuestions(courseId: String?, limit: Int = 50): List<QuestionRow> = try {
        transaction {
            val base = Questions.selectAll()
            val query = if (!courseId.isNullOrBlank()) base.where { Questions.courseId eq courseId } else base
            query.orderBy(Questions.createdAt, SortOrder.DESC).limit(limit).map {
                QuestionRow(
                    id = it[Questions.id],
                    courseId = it[Questions.courseId],
                    content = it[Questions.content],
                    studentId = it[Questions.studentId],
                    studentName = it[Questions.studentName],
                    createdAt = it[Questions.createdAt]
                )
            }
        }
    } catch (e: Throwable) {
        log.warn("QuestionRepository: 查提问列表失败：${e.message}")
        emptyList()
    }
}

@Serializable
data class QuestionRow(
    val id: String,
    val courseId: String,
    val content: String,
    val studentId: String? = null,
    val studentName: String? = null,
    val createdAt: String
)
