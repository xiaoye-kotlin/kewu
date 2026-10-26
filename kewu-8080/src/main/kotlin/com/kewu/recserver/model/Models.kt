package com.kewu.recserver.model

import kotlinx.serialization.Serializable

// 一段录音的元信息，多端上传时都会带这些字段
@Serializable
data class RecordingMeta(
    val recordingId: String,
    val classId: String,
    val sessionId: String,
    val deviceId: String,
    val role: String = "student",   // teacher = 教师端，student = 学生端，融合时教师端优先
    val format: String? = null,
    val sampleRate: Int? = null,
    val durationSec: Double? = null,
    val sizeBytes: Long? = null,
    val avgLevelDb: Double? = null, // 平均音量电平（dBFS），-20 左右是正常说话音量
    val snrDb: Double? = null,      // 信噪比估计（dB），越高越干净
    val storedUrl: String? = null
)

// 质量打分结果，分数越高说明这段录音越值得留作主音源
@Serializable
data class QualityScore(
    val recordingId: String,
    val score: Double,
    val factors: List<String>
)

// 多端录音融合后的产出，先挑最好的当主干
@Serializable
data class FusionResult(
    val sessionId: String,
    val primaryRecordingId: String?,
    val mergedSources: List<String>,
    val strategy: String,
    val selectedRole: String = "",            // 最终选中哪一端的录音
    val fallback: Boolean = false,            // 教师端是否全异常、回退到了学生端
    val abnormalIds: List<String> = emptyList() // 被判时长/大小异常的教师端录音
)

// 上传接口的返回，前端拿到 url 和分数就能继续后面的流程
@Serializable
data class RecordingUploadResult(
    val recordingId: String,
    val url: String,
    val score: Double,
    val storedAt: String,
    val role: String = "student",
    val avgLevelDb: Double? = null,
    val snrDb: Double? = null
)

// 融合请求：一次把同一节课多端的录音一起喂进来
@Serializable
data class FuseRequest(
    val sessionId: String,
    val recordings: List<RecordingMeta>
)

// 课堂纪要用 Redis 缓存，下课即出结构化要点
@Serializable
data class ClassNote(
    val classId: String,
    val content: String,
    val updatedAt: String = ""
)

// 签到记录，缓存层存一份实时状态
@Serializable
data class CheckInRecord(
    val classId: String,
    val studentId: String,
    val status: String,
    val at: String = ""
)

// 签到查询的返回，前端拿 checkins 直接渲染
@Serializable
data class CheckInView(
    val classId: String,
    val checkins: Map<String, String>
)

// 协同会话状态，多端靠它保持同一份上下文
@Serializable
data class ClassroomSession(
    val sessionId: String,
    val classId: String,
    val startedAt: String = "",
    val state: String = "active"
)

// 录音设备实时状态：设备录音时把心跳、类型、质量分写进 Redis（见 RecordingDeviceService）
@Serializable
data class DeviceStatus(
    val deviceId: String = "",
    val type: String = "",          // "phone" = 手机端，"screen" = 大屏端
    val status: String = "",        // 如 recording / online / offline
    val qualityScore: Int = 0,      // 当前音频质量分（0-100）
    val lastHeartbeat: Long? = null, // 最近一次心跳时间戳（ms），可选
    val name: String = "",          // 设备主人的显示名（雷达点下方的名字，2026-10-26）
    val role: String = ""           // student / teacher（老师的点画成黄色）
)

// 雷达图里展示的一个设备点（接口 devices 列表的项）
@Serializable
data class RecordingDeviceView(
    val deviceId: String,
    val type: String,
    val status: String,
    val qualityScore: Int,
    val name: String = "",
    val role: String = ""
)

// 录音设备雷达与状态监控接口的完整返回
@Serializable
data class RecordingDevicesResponse(
    val totalDevices: Int,          // 当前录音设备总数
    val phoneCount: Int,            // 手机端录音数量
    val screenOnline: Boolean,      // 大屏是否在线（存在且 status=online）
    val bestDeviceId: String?,      // 质量分最高的设备ID；没有设备时为 null
    val devices: List<RecordingDeviceView>  // 设备详细列表，供前端画雷达点
)

// 设备心跳上报请求：App/大屏端录音时定时把自身状态打上来（POST /heartbeat 用）
@Serializable
data class DeviceHeartbeat(
    val sessionId: String = "",
    val deviceId: String = "",
    val type: String = "",
    val status: String = "",
    val qualityScore: Int = 0,
    val lastHeartbeat: Long? = null,
    val name: String = "",
    val role: String = ""
)
