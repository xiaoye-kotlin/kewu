package com.kewu.db

import org.jetbrains.exposed.sql.Table

// 数据库表结构：每个内存 map 对应一张表，字段名和模型一一对应
// 日期一律用字符串存（模型里本来就是 String），JSON 字段序列化成 TEXT
object Users : Table("users") {
    val id = varchar("id", 36)
    val username = varchar("username", 64).uniqueIndex()
    val passwordHash = varchar("password_hash", 100)
    val nickname = varchar("nickname", 64).nullable()
    val role = varchar("role", 16)
    val school = varchar("school", 128).nullable()        // 注册时选的学校（2026-10-26，旧版只存在手机本地，换机就没了）
    val className = varchar("class_name", 128).nullable() // 班级（教师用；学生以加入的教学班为准）
    val phone = varchar("phone", 32).nullable()           // 联系方式：只给任课老师看（2026-10-26）
    val email = varchar("email", 128).nullable()
    // 账户状态：active / disabled（总控端可禁用，2026-10-28 移植 admin 时加）
    val status = varchar("status", 16).default("active")
    override val primaryKey = PrimaryKey(id)
}

// 用户绑定的课程，多对多
object UserCourses : Table("user_courses") {
    val userId = varchar("user_id", 36)
    val courseId = varchar("course_id", 64)
    override val primaryKey = PrimaryKey(userId, courseId)
}

object Courses : Table("courses") {
    val id = varchar("id", 64)
    val name = varchar("name", 128)
    val description = text("description")
    val teacher = varchar("teacher", 64)
    val coverUrl = varchar("cover_url", 512).nullable()
    override val primaryKey = PrimaryKey(id)
}

object ClassNotes : Table("class_notes") {
    val id = varchar("id", 64)
    val courseId = varchar("course_id", 64).index()
    val title = varchar("title", 256)
    val content = text("content")
    val author = varchar("author", 64)
    val createdAt = varchar("created_at", 32)
    override val primaryKey = PrimaryKey(id)
}

// 教学班：老师开的班，学生拿邀请码加入
object Classes : Table("classes") {
    val id = varchar("id", 64)
    val name = varchar("name", 128)
    val courseId = varchar("course_id", 64).index()
    val courseName = varchar("course_name", 128)
    val teacherId = varchar("teacher_id", 64).index()
    val teacherName = varchar("teacher_name", 64)
    val term = varchar("term", 32)
    val inviteCode = varchar("invite_code", 16).uniqueIndex()
    val createdAt = varchar("created_at", 32)
    override val primaryKey = PrimaryKey(id)
}

// 班里的学生，多对多
object ClassStudents : Table("class_students") {
    val classId = varchar("class_id", 64)
    val studentId = varchar("student_id", 64)
    val joinedAt = varchar("joined_at", 32).nullable() // 入班时间（总控端班级名单，2026-10-28）
    override val primaryKey = PrimaryKey(classId, studentId)
}

// 课程的开课/下课状态
object ClassStates : Table("class_states") {
    val courseId = varchar("course_id", 64)
    val courseName = varchar("course_name", 128)
    val state = varchar("state", 8)
    val teacherId = varchar("teacher_id", 64)
    val teacherName = varchar("teacher_name", 64)
    val startedAt = varchar("started_at", 32).nullable()
    val endedAt = varchar("ended_at", 32).nullable()
    val updatedAt = varchar("updated_at", 32)
    override val primaryKey = PrimaryKey(courseId)
}

object Assignments : Table("assignments") {
    val id = varchar("id", 64)
    val courseId = varchar("course_id", 64).index()
    val title = varchar("title", 256)
    val description = text("description")
    val deadline = varchar("deadline", 32)
    val totalScore = integer("total_score")
    val knowledgePoints = text("knowledge_points") // JSON 数组
    val publisher = varchar("publisher", 64)
    val createdAt = varchar("created_at", 32)
    // 结构化题目（单选/多选/判断/填空/简答），JSON 数组；为空就是老版纯文本作业。
    // 用可空列而不是 DEFAULT '[]'：MySQL 对 TEXT 列加 DEFAULT 有版本限制（8.0.13 才允许），
    // 老库 ALTER 上去容易踩坑，可空列在任何版本都成立，null 在仓储层按 "[]" 处理
    val questions = text("questions").nullable()
    // 指定学生（JSON 数组，学号）：AI 分层补救作业只发给某一层的学生；null / [] = 全班（2026-10-26）
    val targetStudents = text("target_students").nullable()
    override val primaryKey = PrimaryKey(id)
}

// 一次提交：修改历史、AI 建议都是 JSON 文本
object Submissions : Table("submissions") {
    val id = varchar("id", 64)
    val assignmentId = varchar("assignment_id", 64).index()
    val studentId = varchar("student_id", 64).index()
    val studentName = varchar("student_name", 64)
    val content = text("content")
    val version = integer("version")
    val submittedAt = varchar("submitted_at", 32)
    val status = varchar("status", 16).index()
    val score = double("score").nullable()
    val comment = text("comment")
    val reviewer = varchar("reviewer", 64)
    val reviewedAt = varchar("reviewed_at", 32)
    val history = text("history") // JSON 数组
    val ai = text("ai").nullable() // JSON 对象或 null
    val answers = text("answers").nullable()             // JSON 数组，学生的结构化作答
    val questionResults = text("question_results").nullable() // JSON 数组，逐题判定结果
    val attachments = text("attachments").nullable()          // JSON 数组，本版附件（2026-10-26）
    val durationSec = integer("duration_sec").nullable()      // 本次作答用时（秒，前端计时，2026-10-26）
    val aiHelp = text("ai_help").nullable()                   // JSON：每题向「小悟一题一辅」求助的次数（2026-10-26）
    override val primaryKey = PrimaryKey(id)
}

// 签到场次；quiz 是 JSON；start_at_millis 单独存，重启后迟到计算才准
object AttendanceSessions : Table("attendance_sessions") {
    val id = varchar("id", 64)
    val courseId = varchar("course_id", 64).index()
    val title = varchar("title", 256)
    val mode = varchar("mode", 16)
    val state = varchar("state", 16)
    val publisher = varchar("publisher", 64)
    val createdAt = varchar("created_at", 32)
    val expireAt = varchar("expire_at", 32)
    val lateAfterSeconds = integer("late_after_seconds")
    val latitude = double("latitude").nullable()
    val longitude = double("longitude").nullable()
    val radiusMeters = integer("radius_meters")
    val quiz = text("quiz").nullable() // JSON 对象或 null
    val startAtMillis = long("start_at_millis")
    override val primaryKey = PrimaryKey(id)
}

object AttendanceRecords : Table("attendance_records") {
    val id = varchar("id", 64)
    val sessionId = varchar("session_id", 64).index()
    val studentId = varchar("student_id", 64)
    val studentName = varchar("student_name", 64)
    val checkedInAt = varchar("checked_in_at", 32)
    val status = varchar("status", 16)
    val method = varchar("method", 16)
    val answer = varchar("answer", 256).nullable()
    val secondsAfterStart = long("seconds_after_start")
    val distanceMeters = double("distance_meters").nullable()
    val deviceId = varchar("device_id", 64).nullable()
    val riskScore = integer("risk_score")
    val riskReasons = text("risk_reasons") // JSON 数组
    override val primaryKey = PrimaryKey(id)
}

// 课程的学生名单，算签到"应到人数"
object AttendanceRoster : Table("attendance_roster") {
    val courseId = varchar("course_id", 64)
    val studentId = varchar("student_id", 64)
    override val primaryKey = PrimaryKey(courseId, studentId)
}

// 助教页 / 学生端提问记录：学情雷达就是从这 50 条里提炼核心知识点
object Questions : Table("questions") {
    val id = varchar("id", 36)
    val courseId = varchar("course_id", 64).index()
    val content = text("content")
    val studentId = varchar("student_id", 64).nullable()      // 谁提的问（可选，前端不传就留空）
    val studentName = varchar("student_name", 128).nullable()  // 展示用昵称
    val createdAt = varchar("created_at", 32)
    override val primaryKey = PrimaryKey(id)
}

// 通用上传记录：/api/uploads 存进 OBS 的每个文件一条，提交作业 / 课件引用附件时按 id 回查防伪造外链
object Uploads : Table("uploads") {
    val id = varchar("id", 40)
    val kind = varchar("kind", 16)
    val name = varchar("name", 256)
    val url = varchar("url", 1024)
    val mime = varchar("mime", 128)
    val size = long("size")
    val durationSec = integer("duration_sec").nullable()
    val uploader = varchar("uploader", 64)
    val createdAt = varchar("created_at", 32)
    override val primaryKey = PrimaryKey(id)
}

// 课前资料（PPT / 讲义 / 文档）：老师开课前上传，供 AI 结合课堂录音分析、课后指导学生
object CourseMaterials : Table("course_materials") {
    val id = varchar("id", 40)
    val courseId = varchar("course_id", 64).index()
    val sessionId = varchar("session_id", 64).nullable() // 关联的签到 / 课堂会话（可空：开课前还没有）
    val title = varchar("title", 256)
    val note = text("note")
    val attachments = text("attachments") // JSON 数组
    val uploader = varchar("uploader", 64)
    val createdAt = varchar("created_at", 32)
    override val primaryKey = PrimaryKey(id)
}

// 一节课的「最佳录音」：下课（手动或开课 90 分钟自动）后由后端择优选出，后续交给华为云语音转文字
object ClassRecordings : Table("class_recordings") {
    val sessionId = varchar("session_id", 96)
    val courseId = varchar("course_id", 64).index()
    val primaryRecordingId = varchar("primary_recording_id", 64).nullable()
    val primaryUrl = varchar("primary_url", 1024).nullable()
    val strategy = varchar("strategy", 128)
    val candidateCount = integer("candidate_count")
    val endReason = varchar("end_reason", 16)        // MANUAL / AUTO_90MIN
    val decidedAt = varchar("decided_at", 32)
    val transcriptStatus = varchar("transcript_status", 16) // PENDING_ASR / NO_AUDIO / DONE / FAILED
    val transcript = text("transcript").nullable()
    val summary = text("summary").nullable()      // AI 课堂纪要（转写后由 MaaS 生成，小悟辅导学生时引用）
    val asrError = text("asr_error").nullable()   // 转写失败原因
    override val primaryKey = PrimaryKey(sessionId)
}

// 课表：每节课的星期 / 时间 / 地点 / 任课老师（老师建班后导入；任课老师按姓名 + 学校自动绑定）
object CourseSchedules : Table("course_schedules") {
    val id = varchar("id", 40)
    val classId = varchar("class_id", 64).nullable().index()
    val className = varchar("class_name", 128)
    val courseId = varchar("course_id", 64).index()
    val courseName = varchar("course_name", 128)
    val teacherName = varchar("teacher_name", 64).index()
    val teacherId = varchar("teacher_id", 64).nullable()
    val school = varchar("school", 128).nullable()
    val weekday = integer("weekday")
    val startTime = varchar("start_time", 5)
    val endTime = varchar("end_time", 5)
    val location = varchar("location", 128)
    val weeks = varchar("weeks", 64)
    val createdBy = varchar("created_by", 64)
    val createdAt = varchar("created_at", 32)
    override val primaryKey = PrimaryKey(id)
}

// App 崩溃上报（下次启动时上传上次崩溃原因，含原生崩溃）
object CrashReports : Table("crash_reports") {
    val id = varchar("id", 40)
    val user = varchar("user", 64)
    val device = varchar("device", 128)
    val appVersion = varchar("app_version", 32)
    val reason = varchar("reason", 64)
    val detail = text("detail")
    val happenedAt = varchar("happened_at", 32)
    val receivedAt = varchar("received_at", 32)
    override val primaryKey = PrimaryKey(id)
}

// 每次上传的录音元数据（教师端/学生端上传即落一条），总控端「查看全部录音」的数据源（2026-10-28 移植 admin 时加）
// 与 class_recordings（每节最优录音）互补：这里是全量逐条上传，那里是择优后的代表录音
object Recordings : Table("recordings") {
    val id = varchar("id", 64)
    val classId = varchar("class_id", 64).index()
    val sessionId = varchar("session_id", 96).nullable()
    val courseId = varchar("course_id", 64).index().nullable()
    val studentId = varchar("student_id", 64).nullable()
    val studentName = varchar("student_name", 128).nullable()
    val role = varchar("role", 16)
    val deviceId = varchar("device_id", 64).nullable()
    val url = varchar("url", 1024)
    val score = double("score").nullable()
    val avgLevelDb = double("avg_level_db").nullable()
    val snrDb = double("snr_db").nullable()
    val durationSec = double("duration_sec").nullable()
    val format = varchar("format", 16).nullable()
    val sizeBytes = long("size_bytes").nullable()
    val createdAt = varchar("created_at", 32)
    val deletedAt = varchar("deleted_at", 32).nullable() // 软删：非空即已删
    override val primaryKey = PrimaryKey(id)
}

// 融合会话（暂未落库，表结构先备着，落库后总控端 /sessions 自动有数据；2026-10-28 移植 admin 时加）
object RecordingSessions : Table("recording_sessions") {
    val id = varchar("id", 64)
    val classId = varchar("class_id", 64)
    val sessionId = varchar("session_id", 96)
    val startedAt = varchar("started_at", 32).nullable()
    val endedAt = varchar("ended_at", 32).nullable()
    val createdAt = varchar("created_at", 32)
    override val primaryKey = PrimaryKey(id)
}

val allTables = arrayOf(
    Users, UserCourses, Courses, ClassNotes,
    Classes, ClassStudents, ClassStates,
    Assignments, Submissions,
    AttendanceSessions, AttendanceRecords, AttendanceRoster,
    Questions,
    Uploads, CourseMaterials, ClassRecordings, CourseSchedules, CrashReports,
    Recordings, RecordingSessions
)
