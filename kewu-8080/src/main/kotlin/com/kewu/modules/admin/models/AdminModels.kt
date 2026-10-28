package com.kewu.modules.admin.models

import com.kewu.modules.assignment.models.Question
import com.kewu.modules.user.models.UserRole
import kotlinx.serialization.Serializable

// 总控端（管理后台）前后端约定：统一响应壳在 com.kewu.models.ApiResponse，这里只放业务 DTO

// 用户列表项
@Serializable
data class AdminUserDTO(
    val id: String,
    val username: String,
    val nickname: String?,
    val role: String,
    val status: String
)

// 用户详情：附带基础统计（提交数/录音数按 studentId 关联，缺字段时可能是 0）
@Serializable
data class AdminUserDetailDTO(
    val id: String,
    val username: String,
    val nickname: String?,
    val role: String,
    val status: String,
    val courseCount: Int,
    val submissionCount: Int,
    val recordingCount: Int,
    val lastActive: String? = null
)

// 管理端新建用户（可指定 ADMIN 角色）
@Serializable
data class AdminCreateUserRequest(
    val username: String,
    val password: String,
    val nickname: String? = null,
    val role: String = UserRole.STUDENT
)

// 管理端编辑用户：只传要改的字段
@Serializable
data class AdminUpdateUserRequest(
    val nickname: String? = null,
    val role: String? = null,
    val status: String? = null
)

@Serializable
data class AdminResetPasswordRequest(val newPassword: String)

// 分页通用壳
@Serializable
data class PagedData<T>(
    val items: List<T>,
    val total: Int,
    val page: Int,
    val size: Int
)

// 录音列表项
@Serializable
data class AdminRecordingDTO(
    val id: String,
    val classId: String,
    val sessionId: String?,
    val courseId: String?,
    val studentName: String?,
    val role: String,
    val url: String,
    val score: Double?,
    val durationSec: Double?,
    val format: String?,
    val createdAt: String,
    val deleted: Boolean
)

// 录音详情：playUrl/downloadUrl 暂用直链（OBS 桶若为私有，后续补签名短链）
@Serializable
data class AdminRecordingDetailDTO(
    val id: String,
    val classId: String,
    val sessionId: String?,
    val courseId: String?,
    val studentId: String?,
    val studentName: String?,
    val role: String,
    val url: String,
    val playUrl: String,
    val downloadUrl: String,
    val score: Double?,
    val avgLevelDb: Double?,
    val snrDb: Double?,
    val durationSec: Double?,
    val format: String?,
    val sizeBytes: Long?,
    val createdAt: String
)

// 平台概览统计
@Serializable
data class AdminOverviewDTO(
    val userCount: Int,
    val courseCount: Int,
    val classCount: Int,
    val recordingCount: Int,
    val assignmentCount: Int,
    val submissionCount: Int,
    val recordingBytes: Long
)

// ---------- §5.4 课程与班级 ----------

@Serializable
data class AdminCourseDTO(
    val id: String,
    val name: String,
    val description: String,
    val teacher: String,
    val coverUrl: String?,
    val classCount: Int,
    val studentCount: Int
)

@Serializable
data class AdminClassDTO(
    val id: String,
    val name: String,
    val courseId: String,
    val courseName: String,
    val teacherId: String,
    val teacherName: String,
    val term: String,
    val inviteCode: String,
    val studentCount: Int,
    val createdAt: String
)

// 班级名单里的学生（含入班时间；老数据 joinedAt 为空）
@Serializable
data class AdminClassStudentDTO(
    val studentId: String,
    val studentName: String,
    val joinedAt: String?
)

@Serializable
data class AdminUpdateCourseRequest(
    val name: String? = null,
    val description: String? = null,
    val teacher: String? = null,
    val coverUrl: String? = null
)

@Serializable
data class AdminUpdateClassRequest(
    val name: String? = null,
    val term: String? = null,
    val teacherId: String? = null
)

@Serializable
data class AdminClassStudentRequest(val studentId: String)

// ---------- §5.5 作业与批阅 ----------

@Serializable
data class AdminAssignmentDTO(
    val id: String,
    val courseId: String,
    val courseName: String,
    val title: String,
    val description: String,
    val deadline: String,
    val totalScore: Int,
    val publisher: String,
    val createdAt: String,
    val questionCount: Int,
    val submissionCount: Int
)

// 作业详情：题目 + 批阅统计（人工覆盖批阅的数据源）
@Serializable
data class AdminAssignmentDetailDTO(
    val id: String,
    val courseId: String,
    val courseName: String,
    val title: String,
    val description: String,
    val deadline: String,
    val totalScore: Int,
    val knowledgePoints: List<String>,
    val publisher: String,
    val createdAt: String,
    val questions: List<Question>,
    val submissionCount: Int,
    val reviewedCount: Int,
    val pendingCount: Int,
    val avgScore: Double?
)

@Serializable
data class AdminSubmissionDTO(
    val id: String,
    val assignmentId: String,
    val studentId: String,
    val studentName: String,
    val version: Int,
    val submittedAt: String,
    val status: String,
    val score: Double?,
    val comment: String,
    val reviewer: String,
    val reviewedAt: String
)

// ---------- §5.6 签到 ----------

@Serializable
data class AdminAttendanceSessionDTO(
    val id: String,
    val courseId: String,
    val courseName: String,
    val title: String,
    val mode: String,
    val state: String,
    val publisher: String,
    val createdAt: String,
    val expireAt: String,
    val expected: Int,
    val checked: Int,
    val late: Int,
    val absent: Int,
    val checkRate: Double
)

@Serializable
data class AdminAttendanceRecordDTO(
    val id: String,
    val studentId: String,
    val studentName: String,
    val checkedInAt: String,
    val status: String,
    val method: String,
    val secondsAfterStart: Long,
    val riskScore: Int
)

@Serializable
data class AdminAttendanceDetailDTO(
    val session: AdminAttendanceSessionDTO,
    val records: List<AdminAttendanceRecordDTO>
)
