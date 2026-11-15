package com.kewu.modules.focus

import com.kewu.db.FocusRecords
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

// focus_records 的读写：finish / cancel 各写一条，AI 只取最近 5 条 COMPLETED（CANCELLED 天然被排除）。
// 数据库出问题时仓储只返回失败 / 空列表，不把异常抛到路由上。
class FocusRepository {
    private val log = LoggerFactory.getLogger(FocusRepository::class.java)

    fun insert(r: FocusRecord): Boolean = try {
        transaction {
            FocusRecords.insert {
                it[id] = r.id
                it[userId] = r.userId
                it[taskId] = r.taskId
                it[startTime] = r.startTime
                it[endTime] = r.endTime
                it[plannedMinutes] = r.plannedMinutes
                it[actualMinutes] = r.actualMinutes
                it[distractionCount] = r.distractionCount
                it[taskDifficulty] = r.taskDifficulty
                it[fatigueLevel] = r.fatigueLevel
                it[status] = r.status
            }
        }
        true
    } catch (t: Throwable) {
        log.warn("FocusRepository: 写入 focus_records 失败：${t.message}")
        false
    }

    fun recentCompleted(userId: String, limit: Int = 5): List<FocusRecord> = try {
        transaction {
            FocusRecords.selectAll()
                .where {
                    (FocusRecords.userId eq userId) and (FocusRecords.status eq FocusStatus.COMPLETED.value)
                }
                .orderBy(FocusRecords.startTime, SortOrder.DESC)
                .limit(limit)
                .map { it.toRecord() }
        }
    } catch (t: Throwable) {
        log.warn("FocusRepository: 读取专注记录失败：${t.message}")
        emptyList()
    }

    private fun ResultRow.toRecord() = FocusRecord(
        id = this[FocusRecords.id],
        userId = this[FocusRecords.userId],
        taskId = this[FocusRecords.taskId],
        startTime = this[FocusRecords.startTime],
        endTime = this[FocusRecords.endTime],
        plannedMinutes = this[FocusRecords.plannedMinutes],
        actualMinutes = this[FocusRecords.actualMinutes],
        distractionCount = this[FocusRecords.distractionCount],
        taskDifficulty = this[FocusRecords.taskDifficulty],
        fatigueLevel = this[FocusRecords.fatigueLevel],
        status = this[FocusRecords.status],
    )
}
