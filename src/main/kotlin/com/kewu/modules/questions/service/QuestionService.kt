package com.kewu.modules.questions.service

import com.kewu.modules.analytics.repository.QuestionRepository
import com.kewu.modules.analytics.repository.QuestionRow
import com.kewu.modules.questions.models.AskQuestionRequest

// 课堂提问的业务层：校验 + 落库 + 列表。
// 这是学情雷达真正的“数据源”——学生上课提的问题写进来，雷达才有的分析。
class QuestionService(private val repo: QuestionRepository) {

    // 学生提问：courseId / content 都不能空；写入成功返回新 id
    fun ask(req: AskQuestionRequest): Result<String> {
        if (req.courseId.isBlank()) return Result.failure(IllegalArgumentException("courseId 不能为空"))
        if (req.content.isBlank()) return Result.failure(IllegalArgumentException("提问内容不能为空"))
        val id = repo.insertQuestion(
            req.courseId,
            req.content,
            req.studentId.ifBlank { null },
            req.studentName.ifBlank { null }
        )
        if (id.isBlank()) return Result.failure(RuntimeException("写入提问失败，请稍后重试"))
        return Result.success(id)
    }

    // 提问列表：可按课程过滤；limit 限制安全范围，防一次拉爆
    fun list(courseId: String?, limit: Int): Result<List<QuestionRow>> {
        val safeLimit = limit.coerceIn(1, 200)
        return Result.success(repo.listQuestions(courseId, safeLimit))
    }
}
