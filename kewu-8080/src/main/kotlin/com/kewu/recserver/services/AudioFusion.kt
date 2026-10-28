package com.kewu.recserver.services

import com.kewu.recserver.model.FusionResult
import com.kewu.recserver.model.RecordingMeta

// 多端录音择优融合：教师端优先；教师录音时长/大小异常（疑似中断）时回退学生端；组内挑分最高的当主音源
class AudioFusion {

    fun fuse(sessionId: String, scored: List<Pair<RecordingMeta, Double>>): FusionResult {
        if (scored.isEmpty()) {
            return FusionResult(sessionId, null, emptyList(), "无可用录音")
        }
        if (scored.size == 1) {
            val only = scored[0]
            return FusionResult(
                sessionId = sessionId,
                primaryRecordingId = only.first.recordingId,
                mergedSources = listOf(only.first.recordingId),
                strategy = "单源直出",
                selectedRole = only.first.role
            )
        }

        val teachers = scored.filter { isTeacher(it.first.role) }
        val students = scored.filter { !isTeacher(it.first.role) }
        val maxDur = scored.maxOf { it.first.durationSec ?: 0.0 }
        val maxSize = scored.maxOf { it.first.sizeBytes ?: 0L }
        val abnormalTeachers = teachers.filter { isInterrupted(it.first, maxDur, maxSize) }

        val chosen: List<Pair<RecordingMeta, Double>>
        val strategy: String
        val fallback: Boolean
        when {
            // 没有教师端，学生端里挑
            teachers.isEmpty() -> {
                chosen = students
                strategy = "学生端择优"
                fallback = false
            }
            // 教师端全异常：有学生端就回退，没有就只能捏着鼻子用教师端
            abnormalTeachers.size == teachers.size -> {
                chosen = if (students.isNotEmpty()) students else teachers
                strategy = if (students.isNotEmpty()) "教师端录音异常-回退学生端" else "教师端录音异常-暂无备选"
                fallback = students.isNotEmpty()
            }
            // 教师端有正常的，只在他们中间挑
            else -> {
                chosen = teachers - abnormalTeachers.toSet()
                strategy = "教师端优先"
                fallback = false
            }
        }
        val best = chosen.maxByOrNull { it.second }
        return FusionResult(
            sessionId = sessionId,
            primaryRecordingId = best?.first?.recordingId,
            mergedSources = scored.map { it.first.recordingId },
            strategy = strategy,
            selectedRole = best?.first?.role ?: "",
            fallback = fallback,
            abnormalIds = abnormalTeachers.map { it.first.recordingId }
        )
    }

    private fun isTeacher(role: String): Boolean =
        role.lowercase() in setOf("teacher", "教师")

    // 疑似中断：时长或体量不到本课最长那份的一半，或低于绝对下限
    private fun isInterrupted(meta: RecordingMeta, maxDur: Double, maxSize: Long): Boolean {
        val dur = meta.durationSec ?: 0.0
        val size = meta.sizeBytes ?: 0L
        val durBad = dur < maxOf(2.0, maxDur * 0.5)
        val sizeBad = size < maxOf(10_000L, (maxSize * 0.5).toLong())
        return durBad || sizeBad
    }
}
