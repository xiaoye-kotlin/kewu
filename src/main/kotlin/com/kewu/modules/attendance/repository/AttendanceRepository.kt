package com.kewu.modules.attendance.repository

import com.kewu.db.AttendanceRecords
import com.kewu.db.AttendanceRoster
import com.kewu.db.AttendanceSessions
import com.kewu.db.kewuJson
import com.kewu.modules.attendance.models.AttendanceRecord
import com.kewu.modules.attendance.models.AttendanceSession
import com.kewu.modules.attendance.models.QuizQuestion
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

// 签到场次和记录的数据库仓储, 方法签名和内存版一致
class AttendanceRepository {
    fun saveSession(s: AttendanceSession, startMillis: Long = System.currentTimeMillis()) {
        transaction {
            val exists = !AttendanceSessions.selectAll().where { AttendanceSessions.id eq s.id }.empty()
            if (exists) {
                AttendanceSessions.update({ AttendanceSessions.id eq s.id }) {
                    it[courseId] = s.courseId
                    it[title] = s.title
                    it[mode] = s.mode
                    it[state] = s.state
                    it[publisher] = s.publisher
                    it[createdAt] = s.createdAt
                    it[expireAt] = s.expireAt
                    it[lateAfterSeconds] = s.lateAfterSeconds
                    it[latitude] = s.latitude
                    it[longitude] = s.longitude
                    it[radiusMeters] = s.radiusMeters
                    it[quiz] = s.quiz?.let { q -> kewuJson.encodeToString<QuizQuestion>(q) }
                    it[startAtMillis] = startMillis
                }
            } else {
                AttendanceSessions.insert {
                    it[id] = s.id
                    it[courseId] = s.courseId
                    it[title] = s.title
                    it[mode] = s.mode
                    it[state] = s.state
                    it[publisher] = s.publisher
                    it[createdAt] = s.createdAt
                    it[expireAt] = s.expireAt
                    it[lateAfterSeconds] = s.lateAfterSeconds
                    it[latitude] = s.latitude
                    it[longitude] = s.longitude
                    it[radiusMeters] = s.radiusMeters
                    it[quiz] = s.quiz?.let { q -> kewuJson.encodeToString<QuizQuestion>(q) }
                    it[startAtMillis] = startMillis
                }
            }
        }
    }

    fun findSession(id: String): AttendanceSession? = transaction {
        AttendanceSessions.selectAll().where { AttendanceSessions.id eq id }.firstOrNull()?.toSession()
    }

    fun listSessions(courseId: String?): List<AttendanceSession> = transaction {
        val query = AttendanceSessions.selectAll()
        if (courseId != null) query.andWhere { AttendanceSessions.courseId eq courseId }
        query.orderBy(AttendanceSessions.createdAt).map { it.toSession() }
    }

    fun startMillis(id: String): Long = transaction {
        AttendanceSessions.selectAll().where { AttendanceSessions.id eq id }
            .firstOrNull()?.get(AttendanceSessions.startAtMillis) ?: System.currentTimeMillis()
    }

    fun saveRecord(r: AttendanceRecord) {
        transaction {
            AttendanceRecords.insert {
                it[id] = r.id
                it[sessionId] = r.sessionId
                it[studentId] = r.studentId
                it[studentName] = r.studentName
                it[checkedInAt] = r.checkedInAt
                it[status] = r.status
                it[method] = r.method
                it[answer] = r.answer
                it[secondsAfterStart] = r.secondsAfterStart
                it[distanceMeters] = r.distanceMeters
                it[deviceId] = r.deviceId
                it[riskScore] = r.riskScore
                it[riskReasons] = kewuJson.encodeToString(r.riskReasons)
            }
        }
    }

    fun findRecord(id: String): AttendanceRecord? = transaction {
        AttendanceRecords.selectAll().where { AttendanceRecords.id eq id }.firstOrNull()?.toRecord()
    }

    fun listRecords(sessionId: String): List<AttendanceRecord> = transaction {
        AttendanceRecords.selectAll().where { AttendanceRecords.sessionId eq sessionId }
            .orderBy(AttendanceRecords.checkedInAt).map { it.toRecord() }
    }

    fun findRecordOf(sessionId: String, studentId: String): AttendanceRecord? = transaction {
        AttendanceRecords.selectAll()
            .where { (AttendanceRecords.sessionId eq sessionId) and (AttendanceRecords.studentId eq studentId) }
            .firstOrNull()?.toRecord()
    }

    fun rosterOf(courseId: String): List<String> = transaction {
        AttendanceRoster.selectAll().where { AttendanceRoster.courseId eq courseId }
            .map { it[AttendanceRoster.studentId] }
    }

    fun addToRoster(courseId: String, studentId: String) {
        val list = rosterOf(courseId)
        if (studentId in list) return
        transaction {
            AttendanceRoster.insert {
                it[AttendanceRoster.courseId] = courseId
                it[AttendanceRoster.studentId] = studentId
            }
        }
    }

    fun nextId(prefix: String): String = "$prefix${System.nanoTime().toString().takeLast(6)}"

    private fun ResultRow.toSession(): AttendanceSession {
        val quizText = this[AttendanceSessions.quiz]
        return AttendanceSession(
            id = this[AttendanceSessions.id],
            courseId = this[AttendanceSessions.courseId],
            title = this[AttendanceSessions.title],
            mode = this[AttendanceSessions.mode],
            state = this[AttendanceSessions.state],
            publisher = this[AttendanceSessions.publisher],
            createdAt = this[AttendanceSessions.createdAt],
            expireAt = this[AttendanceSessions.expireAt],
            lateAfterSeconds = this[AttendanceSessions.lateAfterSeconds],
            latitude = this[AttendanceSessions.latitude],
            longitude = this[AttendanceSessions.longitude],
            radiusMeters = this[AttendanceSessions.radiusMeters],
            quiz = quizText?.let { runCatching { kewuJson.decodeFromString<QuizQuestion>(it) }.getOrNull() }
        )
    }

    private fun ResultRow.toRecord(): AttendanceRecord =
        AttendanceRecord(
            id = this[AttendanceRecords.id],
            sessionId = this[AttendanceRecords.sessionId],
            studentId = this[AttendanceRecords.studentId],
            studentName = this[AttendanceRecords.studentName],
            checkedInAt = this[AttendanceRecords.checkedInAt],
            status = this[AttendanceRecords.status],
            method = this[AttendanceRecords.method],
            answer = this[AttendanceRecords.answer],
            secondsAfterStart = this[AttendanceRecords.secondsAfterStart],
            distanceMeters = this[AttendanceRecords.distanceMeters],
            deviceId = this[AttendanceRecords.deviceId],
            riskScore = this[AttendanceRecords.riskScore],
            riskReasons = runCatching { kewuJson.decodeFromString<List<String>>(this[AttendanceRecords.riskReasons]) }.getOrElse { emptyList() }
        )
}
