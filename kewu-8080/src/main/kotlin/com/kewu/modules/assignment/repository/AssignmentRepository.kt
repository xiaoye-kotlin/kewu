package com.kewu.modules.assignment.repository

import com.kewu.db.Assignments
import com.kewu.db.Submissions
import com.kewu.db.kewuJson
import com.kewu.modules.assignment.models.Assignment
import com.kewu.modules.assignment.models.AiSuggestion
import com.kewu.modules.assignment.models.Question
import com.kewu.modules.assignment.models.Submission
import com.kewu.modules.assignment.models.SubmissionVersion
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

// 作业和提交的数据库仓储, 方法签名和内存版一致
class AssignmentRepository {
    fun createAssignment(a: Assignment) {
        transaction {
            Assignments.insert {
                it[id] = a.id
                it[courseId] = a.courseId
                it[title] = a.title
                it[description] = a.description
                it[deadline] = a.deadline
                it[totalScore] = a.totalScore
                it[knowledgePoints] = kewuJson.encodeToString(a.knowledgePoints)
                it[publisher] = a.publisher
                it[createdAt] = a.createdAt
                it[questions] = kewuJson.encodeToString(a.questions)
            }
        }
    }

    fun listAssignments(courseId: String?): List<Assignment> = transaction {
        val query = Assignments.selectAll()
        if (courseId != null) query.andWhere { Assignments.courseId eq courseId }
        query.orderBy(Assignments.createdAt).map { it.toAssignment() }
    }

    fun findAssignment(id: String): Assignment? = transaction {
        Assignments.selectAll().where { Assignments.id eq id }.firstOrNull()?.toAssignment()
    }

    // 提交可能是新插入也可能是更新(反复提交走 version+history)，这里一起处理
    fun saveSubmission(s: Submission) {
        transaction {
            val exists = !Submissions.selectAll().where { Submissions.id eq s.id }.empty()
            if (exists) {
                Submissions.update({ Submissions.id eq s.id }) {
                    it[assignmentId] = s.assignmentId
                    it[studentId] = s.studentId
                    it[studentName] = s.studentName
                    it[content] = s.content
                    it[version] = s.version
                    it[submittedAt] = s.submittedAt
                    it[status] = s.status
                    it[score] = s.score
                    it[comment] = s.comment
                    it[reviewer] = s.reviewer
                    it[reviewedAt] = s.reviewedAt
                    it[history] = kewuJson.encodeToString(s.history)
                    it[ai] = s.ai?.let { a -> kewuJson.encodeToString<AiSuggestion>(a) }
                    it[answers] = kewuJson.encodeToString(s.answers)
                    it[questionResults] = kewuJson.encodeToString(s.questionResults)
                }
            } else {
                Submissions.insert {
                    it[id] = s.id
                    it[assignmentId] = s.assignmentId
                    it[studentId] = s.studentId
                    it[studentName] = s.studentName
                    it[content] = s.content
                    it[version] = s.version
                    it[submittedAt] = s.submittedAt
                    it[status] = s.status
                    it[score] = s.score
                    it[comment] = s.comment
                    it[reviewer] = s.reviewer
                    it[reviewedAt] = s.reviewedAt
                    it[history] = kewuJson.encodeToString(s.history)
                    it[ai] = s.ai?.let { a -> kewuJson.encodeToString<AiSuggestion>(a) }
                    it[answers] = kewuJson.encodeToString(s.answers)
                    it[questionResults] = kewuJson.encodeToString(s.questionResults)
                }
            }
        }
    }

    fun findSubmission(id: String): Submission? = transaction {
        Submissions.selectAll().where { Submissions.id eq id }.firstOrNull()?.toSubmission()
    }

    fun listSubmissions(assignmentId: String): List<Submission> = transaction {
        Submissions.selectAll().where { Submissions.assignmentId eq assignmentId }
            .orderBy(Submissions.submittedAt).map { it.toSubmission() }
    }

    fun findSubmissionOf(assignmentId: String, studentId: String): Submission? = transaction {
        Submissions.selectAll()
            .where { (Submissions.assignmentId eq assignmentId) and (Submissions.studentId eq studentId) }
            .firstOrNull()?.toSubmission()
    }

    fun listSubmissionsOfStudent(studentId: String): List<Submission> = transaction {
        Submissions.selectAll().where { Submissions.studentId eq studentId }
            .orderBy(Submissions.submittedAt).map { it.toSubmission() }
    }

    fun nextId(prefix: String): String = "$prefix${System.nanoTime().toString().takeLast(6)}"

    private fun ResultRow.toAssignment(): Assignment =
        Assignment(
            id = this[Assignments.id],
            courseId = this[Assignments.courseId],
            title = this[Assignments.title],
            description = this[Assignments.description],
            deadline = this[Assignments.deadline],
            totalScore = this[Assignments.totalScore],
            knowledgePoints = runCatching { kewuJson.decodeFromString<List<String>>(this[Assignments.knowledgePoints]) }.getOrElse { emptyList() },
            publisher = this[Assignments.publisher],
            createdAt = this[Assignments.createdAt],
            questions = decodeList(this[Assignments.questions].orEmpty())
        )

    private fun org.jetbrains.exposed.sql.ResultRow.toSubmission(): Submission =
        Submission(
            id = this[Submissions.id],
            assignmentId = this[Submissions.assignmentId],
            studentId = this[Submissions.studentId],
            studentName = this[Submissions.studentName],
            content = this[Submissions.content],
            version = this[Submissions.version],
            submittedAt = this[Submissions.submittedAt],
            status = this[Submissions.status],
            score = this[Submissions.score],
            comment = this[Submissions.comment],
            reviewer = this[Submissions.reviewer],
            reviewedAt = this[Submissions.reviewedAt],
            history = decodeList(this[Submissions.history]),
            ai = decodeOrNull<AiSuggestion>(this[Submissions.ai]),
            answers = decodeList(this[Submissions.answers].orEmpty()),
            questionResults = decodeList(this[Submissions.questionResults].orEmpty())
        )

    // 老数据 JSON 脏了也不能让整个接口挂掉, 退化成空值
    private inline fun <reified T> decodeList(raw: String): List<T> =
        try { kewuJson.decodeFromString(raw) } catch (e: Exception) { emptyList() }

    private inline fun <reified T> decodeOrNull(raw: String?): T? =
        raw?.let { try { kewuJson.decodeFromString<T>(it) } catch (e: Exception) { null } }
}
