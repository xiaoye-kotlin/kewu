package com.kewu.modules.assignment.models

import com.kewu.modules.upload.models.Attachment
import kotlinx.serialization.Serializable

// 作业模块的数据结构: 发布作业 -> 学生提交(可多版) -> 教师批阅, 外加几个 AI 增值功能

// 作业状态用的字符串常量, 免得各处写错
object SubmissionStatus {
    const val PENDING = "PENDING"   // 待批阅
    const val REVIEWED = "REVIEWED" // 已批阅
    const val RETURNED = "RETURNED" // 被打回, 等学生改完再交
}

// 题目类型, 前端拿 type 分发渲染; 以后加新题型只要扩这里
object QuestionType {
    const val SINGLE_CHOICE = "SINGLE_CHOICE"     // 单选
    const val MULTIPLE_CHOICE = "MULTIPLE_CHOICE" // 多选
    const val TRUE_FALSE = "TRUE_FALSE"           // 判断
    const val FILL_BLANK = "FILL_BLANK"           // 填空
    const val SHORT_ANSWER = "SHORT_ANSWER"       // 简答

    val ALL = listOf(SINGLE_CHOICE, MULTIPLE_CHOICE, TRUE_FALSE, FILL_BLANK, SHORT_ANSWER)
    // 能自动判分的(选择/判断/填空), 简答留给老师
    fun isObjective(type: String) =
        type == SINGLE_CHOICE || type == MULTIPLE_CHOICE || type == TRUE_FALSE || type == FILL_BLANK
}

// 一道题。options/answer 对选择判断题必填, 填空简答可以留空
@Serializable
data class Question(
    val id: String = "",
    val type: String = QuestionType.SHORT_ANSWER,
    val stem: String = "",
    val options: List<String> = emptyList(),
    val answer: String = "",
    val score: Int = 0,
    val analysis: String = ""
)

// 学生一道题的作答: 选择判断存选项序号(A/B/C 或 true/false), 填空简答存文本
@Serializable
data class QuestionAnswer(val questionId: String = "", val value: String = "")

// 判题结果: 客观题服务端自动判, 主观题留空等老师
@Serializable
data class QuestionResult(
    val questionId: String = "",
    val type: String = "",
    val stem: String = "",
    val studentValue: String = "",
    val standardAnswer: String = "",
    val correct: Boolean? = null,    // null = 还没判(主观题)
    val autoScore: Double = 0.0,     // 自动判出来的分
    val finalScore: Double? = null,  // 老师改过的分, 没有就用 autoScore
    val fullScore: Int = 0,
    val judgedBy: String = JudgedBy.PENDING // AUTO / TEACHER / PENDING
)

object JudgedBy {
    const val AUTO = "AUTO"       // 机器判的客观题
    const val TEACHER = "TEACHER" // 老师按题给过分
    const val PENDING = "PENDING" // 主观题, 等老师
}

// 老师按题给分用
@Serializable
data class QuestionScoreItem(val questionId: String = "", val score: Int = 0)

// 一次作业
@Serializable
data class Assignment(
    val id: String,
    val courseId: String,
    val title: String,
    val description: String = "",
    val deadline: String = "",
    val totalScore: Int = 100,
    val knowledgePoints: List<String> = emptyList(), // 知识点标签, 学情分析全靠它
    val publisher: String = "",
    val createdAt: String = "",
    val questions: List<Question> = emptyList() // 结构化题目; 为空就是老版纯文本作业, 按老样子处理
)

// 提交的版本快照 —— 留着学生的修改过程, 用来画"成长曲线"
@Serializable
data class SubmissionVersion(
    val version: Int,
    val content: String,
    val submittedAt: String,
    val ai: AiSuggestion? = null,
    val attachments: List<Attachment> = emptyList(),
    val durationSec: Int? = null
)

// 一次提交(一名学生在一道作业上只保留一条主记录, 反复提交走 version + history)
@Serializable
data class Submission(
    val id: String,
    val assignmentId: String,
    val studentId: String,
    val studentName: String = "",
    val content: String,
    val version: Int = 1,
    val submittedAt: String,
    val status: String = SubmissionStatus.PENDING,
    val score: Double? = null,
    val comment: String = "",
    val reviewer: String = "",
    val reviewedAt: String = "",
    val history: List<SubmissionVersion> = emptyList(),
    val ai: AiSuggestion? = null,
    val answers: List<QuestionAnswer> = emptyList(),       // 结构化作答, 老提交没有就是空
    val questionResults: List<QuestionResult> = emptyList(), // 逐题判定结果, 前端回显"第1题✓"
    val attachments: List<Attachment> = emptyList(),        // 本版附件(图片/文件/录音), 无附件为 []
    val durationSec: Int? = null                             // 本版作答用时(秒), 老提交没有
)

// 打分细项, 让 AI 给的每一分都能说清楚来历(可解释 AI, 不是黑盒给个分)
@Serializable
data class ScoreReason(val item: String, val detail: String, val score: Double)

// AI 预批阅结果: 建议分 + 把握度 + 命中的知识点 + 逐条理由 + 风险等级
@Serializable
data class AiSuggestion(
    val suggestedScore: Double,
    val confidence: Double,
    val hitKnowledgePoints: List<String> = emptyList(),
    val missedKnowledgePoints: List<String> = emptyList(),
    val reasons: List<ScoreReason> = emptyList(),
    val riskLevel: String = "LOW", // LOW / MID / HIGH, 主要看疑似抄袭和字数异常
    val summary: String = "",
    val questionResults: List<QuestionResult> = emptyList(), // 逐题判定, 和 submission 顶层那份一样
    val objectiveScore: Double? = null, // 客观题自动判出来的合计分, 老师可以参考
    val objectiveFullScore: Int = 0     // 客观题满分合计
)

// 相似片段报告里的一对可疑提交
@Serializable
data class SimilarityPair(
    val submissionA: String,
    val studentA: String,
    val submissionB: String,
    val studentB: String,
    val similarity: Double,
    val sharedFragments: List<String> = emptyList()
)

@Serializable
data class ScoreBucket(val label: String, val count: Int)

// 单个作业的统计看板
@Serializable
data class AssignmentStats(
    val assignmentId: String,
    val title: String = "",
    val submittedCount: Int = 0,
    val reviewedCount: Int = 0,
    val pendingCount: Int = 0,
    val avgScore: Double? = null,
    val maxScore: Double? = null,
    val minScore: Double? = null,
    val scoreDistribution: List<ScoreBucket> = emptyList(),
    val avgVersions: Double = 0.0,          // 平均修改次数, 看学生投入程度
    val aiGapAvg: Double? = null,           // AI 建议分和老师实际分的平均差
    val highRiskCount: Int = 0              // 疑似抄袭的对数
)

// 知识点掌握情况
@Serializable
data class KnowledgeMastery(
    val point: String,
    val sampleCount: Int,
    val avgScoreRate: Double // 0~1
)

@Serializable
data class ScorePoint(val label: String, val avgScore: Double)

// 一门课的学情画像
@Serializable
data class CourseAnalytics(
    val courseId: String,
    val assignmentCount: Int = 0,
    val submissionCount: Int = 0,
    val knowledgeMastery: List<KnowledgeMastery> = emptyList(),
    val weakPoints: List<String> = emptyList(), // 掌握率最低的几个知识点
    val scoreTrend: List<ScorePoint> = emptyList()
)

// 智能批阅队列里的一项: 谁该先改
@Serializable
data class ReviewQueueItem(
    val submissionId: String,
    val studentId: String,
    val studentName: String,
    val version: Int,
    val priority: Double,
    val reasons: List<String> = emptyList(),
    val aiSuggestedScore: Double? = null
)

// ---- 跨课程待批阅聚合（教师端视角拓展）----

// 一条待批阅: 带上它属于哪门课哪道作业, 以及建议先改的理由
@Serializable
data class PendingReviewItem(
    val submissionId: String,
    val assignmentId: String,
    val assignmentTitle: String,
    val courseId: String,
    val courseName: String,
    val studentId: String,
    val studentName: String,
    val submittedAt: String,
    val waitHours: Double = 0.0,          // 交上来多久了还没改
    val version: Int = 1,
    val status: String = SubmissionStatus.PENDING,
    val aiSuggestedScore: Double? = null,
    val riskLevel: String = "LOW",
    val priority: Double = 50.0,
    val urgent: Boolean = false,
    val reasons: List<String> = emptyList()
)

// 聚合里的一门课: 这门课积压了多少
@Serializable
data class PendingCourseGroup(
    val courseId: String,
    val courseName: String,
    val pendingCount: Int = 0,
    val assignmentCount: Int = 0,
    val urgentCount: Int = 0,
    val oldestWaitHours: Double = 0.0,
    val assignments: List<PendingAssignmentGroup> = emptyList()
)

// 聚合里的一道作业
@Serializable
data class PendingAssignmentGroup(
    val assignmentId: String,
    val title: String,
    val deadline: String = "",
    val pendingCount: Int = 0,
    val urgentCount: Int = 0
)

// 跨课程待批阅总览: 一个接口把老师所有课的积压一次说清
@Serializable
data class PendingReviewOverview(
    val teacherId: String = "",
    val teacherName: String = "",
    val scope: String = "ALL",            // ALL 全部课程 / TEACHER 只看该老师教的课
    val total: Int = 0,
    val courseCount: Int = 0,
    val assignmentCount: Int = 0,
    val studentCount: Int = 0,
    val urgentCount: Int = 0,             // 优先级高、建议先改的
    val highRiskCount: Int = 0,           // 疑似雷同的
    val oldestWaitHours: Double = 0.0,
    val avgWaitHours: Double = 0.0,
    val groups: List<PendingCourseGroup> = emptyList(),
    val items: List<PendingReviewItem> = emptyList()
)

// ---- 请求体 ----

@Serializable
data class CreateAssignmentRequest(
    val courseId: String,
    val title: String,
    val description: String = "",
    val deadline: String = "",
    val totalScore: Int = 100,
    val knowledgePoints: List<String> = emptyList(),
    val publisher: String = "",
    val questions: List<Question> = emptyList() // 可选, 不传就是老版纯文本作业
)

@Serializable
data class SubmitRequest(
    val studentId: String,
    val studentName: String = "",
    val content: String,
    val answers: List<QuestionAnswer> = emptyList(), // 可选, 结构化作答
    val attachments: List<Attachment> = emptyList(), // 可选, 先 POST /api/uploads 拿到的附件对象, 最多 9 个
    val durationSec: Int? = null                      // 可选, 从打开作业到提交的作答用时(秒)
)

@Serializable
data class ReviewRequest(
    val score: Double? = null, // 传了 questionScores 时可以省略, 会自动按题求和
    val comment: String = "",
    val reviewer: String = "",
    val action: String = "ACCEPT", // ACCEPT 定分 / RETURN 打回重做
    val questionScores: List<QuestionScoreItem> = emptyList() // 可选, 按题给分
)

// 带统计的作业详情, 前端列表页直接用
@Serializable
data class AssignmentDetail(
    val assignment: Assignment,
    val stats: AssignmentStats
)
