package com.kewu.modules.teacher.models

import kotlinx.serialization.Serializable

// 教师端的数据结构: 老师开课 -> 建班(自动生成邀请码) -> 学生用码加入

// 一个教学班: 某门课在某个学期由某位老师开的一个班
@Serializable
data class ClassRoom(
    val id: String,
    val name: String,                 // 班级名, 如"软件工程 1 班"
    val courseId: String,
    val courseName: String = "",
    val teacherId: String,
    val teacherName: String = "",
    val term: String = "",            // 学期, 如"2026 秋"
    val inviteCode: String = "",      // 学生拿这个码加入
    val studentIds: MutableSet<String> = mutableSetOf(),
    val createdAt: String = ""
)

// 班级对外结构: 多带一个学生数, 方便列表页直接显示
@Serializable
data class ClassRoomInfo(
    val id: String,
    val name: String,
    val courseId: String,
    val courseName: String = "",
    val teacherId: String,
    val teacherName: String = "",
    val term: String = "",
    val inviteCode: String = "",
    val studentCount: Int = 0,
    val createdAt: String = ""
)

// 老师看到的"我教的班"汇总
@Serializable
data class TeacherOverview(
    val teacherId: String,
    val teacherName: String = "",
    val classCount: Int = 0,
    val studentCount: Int = 0,       // 去重后的学生总数
    val courseCount: Int = 0,        // 教了几门不同的课
    val classes: List<ClassRoomInfo> = emptyList()
)

@Serializable
data class StudentBrief(val studentId: String, val name: String = "", val studentNo: String = "") // studentNo = 学号（登录账号）

// ---- 请求体 ----

@Serializable
data class CreateCourseRequest(
    val name: String,
    val description: String = "",
    val teacher: String = "",      // 任课老师名, 写在课程上
    val teacherId: String = ""     // 建课的人(老师账号 id 或用户名)
)

@Serializable
data class CreateClassRequest(
    val courseId: String,
    val name: String,
    val term: String = "",
    val teacherId: String = ""
)

@Serializable
data class JoinClassRequest(
    val studentId: String,
    val inviteCode: String,
    val studentName: String = ""
)

// ---- 控制台聚合 + 开课下课(教师端第二步) ----

// 控制台首屏一次性返回的东西
@Serializable
data class TeacherConsole(
    val teacherId: String = "",
    val teacherName: String = "",
    val date: String = "",                  // 今天, 格式 2026-10-20
    val todayCourses: List<TodayCourseItem> = emptyList(),
    val pendingReview: Int = 0,             // 待批阅作业总数
    val hasPending: Boolean = false,         // 红点: true 就点亮
    val todayCheckRate: Double = 0.0,        // 今日最新签到率 0~1
    val todaySessionCount: Int = 0,          // 今天发起了几场签到
    val checkedToday: Int = 0,               // 今天实际签到了多少人次
    val expectedToday: Int = 0               // 今天应该来多少人
)

// 今日课程里的一门课
@Serializable
data class TodayCourseItem(
    val courseId: String = "",
    val courseName: String = "",
    val classId: String = "",
    val className: String = "",
    val studentCount: Int = 0,
    val state: String = ClassStateValue.OFF, // 上课中 ON / 未开课 OFF
    val startedAt: String? = null,
    val todayCheckRate: Double? = null       // 这门课今天的签到率, 没发过签到就是 null
)

// 课堂开关状态的取值
object ClassStateValue {
    const val ON = "ON"
    const val OFF = "OFF"
}

// 一堂课的开关状态(开课/下课落到这里的记录)
@Serializable
data class ClassState(
    val courseId: String = "",
    val courseName: String = "",
    val state: String = ClassStateValue.OFF,
    val teacherId: String = "",
    val teacherName: String = "",
    val startedAt: String? = null,
    val endedAt: String? = null,
    val updatedAt: String = ""
)

@Serializable
data class ClassStateRequest(
    val courseId: String,
    val action: String,          // START = 开课, END = 下课
    val teacherId: String = ""
)

fun ClassRoom.toInfo() = ClassRoomInfo(
    id = id, name = name, courseId = courseId, courseName = courseName,
    teacherId = teacherId, teacherName = teacherName, term = term,
    inviteCode = inviteCode, studentCount = studentIds.size, createdAt = createdAt
)
