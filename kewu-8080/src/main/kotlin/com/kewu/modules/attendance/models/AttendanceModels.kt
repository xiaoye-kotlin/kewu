package com.kewu.modules.attendance.models

import kotlinx.serialization.Serializable

// 签到模块的数据结构: 发起签到 -> 学生签到(二维码/暗号/AI问答) -> 记录与风控

// 签到方式
object AttendanceMode {
    const val QR = "QR"     // 动态二维码(每 30 秒变一次, 防截图代签)
    const val CODE = "CODE" // 数字暗号
    const val QUIZ = "QUIZ" // AI 随堂问答签到, 答对才算
}

// 签到场次状态
object AttendanceState {
    const val OPEN = "OPEN"
    const val CLOSED = "CLOSED"
}

// 学生签到结果
object CheckInStatus {
    const val NORMAL = "NORMAL" // 正常
    const val LATE = "LATE"     // 迟到
}

// AI 出的随堂小题, 题目从该课程的课堂纪要里自动生成
@Serializable
data class QuizQuestion(
    val question: String,
    val hint: String = "",
    val acceptedAnswers: List<String> = emptyList(),
    val sourceNoteTitle: String = ""
)

// 一场签到
@Serializable
data class AttendanceSession(
    val id: String,
    val courseId: String,
    val title: String,
    val mode: String = AttendanceMode.QR,
    val state: String = AttendanceState.OPEN,
    val publisher: String = "",
    val createdAt: String = "",
    val expireAt: String = "",
    val lateAfterSeconds: Int = 300,   // 超过这个秒数算迟到
    val latitude: Double? = null,      // 教室坐标, 用来做位置校验
    val longitude: Double? = null,
    val radiusMeters: Int = 200,
    val quiz: QuizQuestion? = null
)

// 一条签到记录
@Serializable
data class AttendanceRecord(
    val id: String,
    val sessionId: String,
    val studentId: String,
    val studentName: String = "",
    val checkedInAt: String = "",
    val status: String = CheckInStatus.NORMAL,
    val method: String = AttendanceMode.QR,
    val answer: String? = null,
    val secondsAfterStart: Long = 0,
    val distanceMeters: Double? = null,
    val deviceId: String? = null,
    val riskScore: Int = 0,
    val riskReasons: List<String> = emptyList()
)

// 动态签到码, 前端拿 code 去生成二维码
@Serializable
data class AttendanceCode(
    val sessionId: String,
    val code: String,
    val qrContent: String,        // 二维码里该放的字符串
    val windowSeconds: Int = 30,  // 多久刷新一次
    val remainingSeconds: Int = 0 // 当前这码还剩几秒
)

@Serializable
data class TimePoint(val minute: Int, val count: Int)

@Serializable
data class MethodCount(val method: String, val count: Int)

// 一场签到的统计
@Serializable
data class AttendanceStats(
    val sessionId: String,
    val title: String = "",
    val mode: String = "",
    val expected: Int = 0,        // 应到(课程名单)
    val checked: Int = 0,         // 实到
    val late: Int = 0,
    val absent: Int = 0,
    val checkRate: Double = 0.0,
    val avgSeconds: Double? = null,
    val timeline: List<TimePoint> = emptyList(),   // 每分钟累计签到人数
    val methodBreakdown: List<MethodCount> = emptyList()
)

// 疑似代签的可疑记录
@Serializable
data class RiskItem(
    val recordId: String,
    val studentId: String,
    val studentName: String,
    val riskScore: Int,
    val reasons: List<String> = emptyList(),
    val checkedInAt: String = ""
)

@Serializable
data class RecentCheckIn(val studentName: String, val secondsAfterStart: Long, val status: String)

// 实时看板
@Serializable
data class RealtimeBoard(
    val sessionId: String,
    val expected: Int,
    val checked: Int,
    val checkRate: Double,
    val elapsedSeconds: Long,
    val latest: List<RecentCheckIn> = emptyList()
)

// 带统计的场次详情
@Serializable
data class AttendanceDetail(
    val session: AttendanceSession,
    val stats: AttendanceStats
)

// ---- 请求体 ----

@Serializable
data class CreateAttendanceRequest(
    val courseId: String,
    val title: String,
    val mode: String = AttendanceMode.QR,
    val durationMinutes: Int = 10,
    val lateAfterSeconds: Int = 300,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Int = 200,
    val publisher: String = ""
)

@Serializable
data class CheckInRequest(
    val studentId: String,
    val studentName: String = "",
    val code: String = "",      // 二维码/暗号的码值
    val answer: String = "",    // QUIZ 模式的答案
    val latitude: Double? = null,
    val longitude: Double? = null,
    val deviceId: String? = null
)
