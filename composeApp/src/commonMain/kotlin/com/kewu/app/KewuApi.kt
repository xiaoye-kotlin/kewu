package com.kewu.app

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import kotlinx.coroutines.CancellationException
import io.ktor.client.request.forms.ChannelProvider
import kotlinx.coroutines.sync.withLock
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.PartData
import io.ktor.http.contentType
import io.ktor.http.URLBuilder
import io.ktor.utils.io.core.ByteReadPacket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 课悟后端对接层（Kotlin 多平台，commonMain）。
 *
 * ★ 严格遵守后端文档的「两个坑」：
 *   1. Ktor 遇到 4xx/5xx 抛 [ResponseException]，必须 catch 后读 [ResponseException.response]；
 *   2. 项目**没开 kotlinx.serialization 编译插件**，所以**绝不**写 `@Serializable` + `receive<T>()`，
 *      统一手工拼 JSON 字符串 + [Json.parseToJsonElement] 解析（照抄文档给的写法）。
 *
 * 统一响应壳（8080 业务后端，第 1 节）：`{code, message, data}`。判断成败只看 `code`，不看 HTTP 状态。
 * 8081 服务**不套壳**，直接返回业务 JSON，失败是裸 `{"error":"..."}`（见第 9 节），单独处理。
 *
 * token：登录成功后存进来，之后业务接口自动带 `Authorization: Bearer`。持久化走 [kvGet]/[kvSet]。
 */

// ───────────────────────── 数据模型（手搓，无 @Serializable） ─────────────────────────

data class UserInfo(
    val id: String, val username: String, val nickname: String?, val role: String = "",
    /** 学校 / 班级（2026-10-26 起存后端，换机不丢）。 */
    val school: String? = null, val className: String? = null,
)
data class CourseInfo(
    val id: String, val name: String, val description: String, val teacher: String,
)
data class ClassNoteInfo(
    val id: String, val courseId: String, val title: String, val content: String,
    val author: String, val createdAt: String,
)
data class UserMe(val user: UserInfo?, val courses: List<CourseInfo>)

data class AssignmentInfo(
    val id: String, val courseId: String, val title: String, val description: String,
    val deadline: String, val totalScore: Int, val knowledgePoints: List<String>,
    val publisher: String, val createdAt: String,
    /** 题目列表（2026-10-25 作业题目系统）。后端若落原生字段则用之，否则从 description 透传块提取。 */
    val questions: List<Question> = emptyList(),
)
data class AssignmentStats(
    val submittedCount: Int, val reviewedCount: Int, val pendingCount: Int,
    val avgScore: Double, val maxScore: Double, val minScore: Double,
    val scoreDistribution: List<ScoreBucket>, val avgVersions: Double,
    val aiGapAvg: Double, val highRiskCount: Int,
)
data class ScoreBucket(val label: String, val count: Int)

data class SubmissionVersion(val version: Int, val content: String, val submittedAt: String)
data class SubmissionInfo(
    val id: String, val assignmentId: String, val studentId: String, val studentName: String,
    val content: String, val version: Int, val submittedAt: String, val status: String,
    val score: Double?, val comment: String?, val reviewer: String?, val reviewedAt: String?,
    val history: List<SubmissionVersion>, val ai: AiSuggestion?,
    /** 按题作答（文档 3.12，老提交为空）。 */
    val answers: List<QuestionAnswer> = emptyList(),
    /** 逐题判定结果（客观题自动判分，主观题 correct=null 等老师）。 */
    val questionResults: List<QuestionResult> = emptyList(),
    /** 提交附件（图片 / 文件 / 录音，2026-10-26；后端未上线时为空）。 */
    val attachments: List<AttachmentInfo> = emptyList(),
    /** 作答用时（秒，2026-10-26 起前端计时随提交上报；老提交为 null）。 */
    val durationSec: Int? = null,
)
/** 录音雷达里的一台设备。 */
data class RecordingDevice(
    val deviceId: String, val type: String, val status: String, val qualityScore: Int,
    val name: String = "", val role: String = "",
)
data class AiReason(val item: String, val detail: String, val score: Double)
data class AiSuggestion(
    val suggestedScore: Double, val confidence: Double,
    val hitKnowledgePoints: List<String>, val missedKnowledgePoints: List<String>,
    val reasons: List<AiReason>, val riskLevel: String, val summary: String,
    /** 客观题自动判分合计（文档 3.12：ai.objectiveScore / objectiveFullScore，老师一眼看客观题拿了多少）。 */
    val objectiveScore: Double = 0.0,
    val objectiveFullScore: Double = 0.0,
    /** 逐题判定结果（同 submission.questionResults 内容一致）。 */
    val questionResults: List<QuestionResult> = emptyList(),
)
/** 按题作答（文档 3.11/3.12：answers = [{questionId, value}]）。 */
data class QuestionAnswer(val questionId: String, val value: String)
/** 逐题判定结果（文档 3.12：correct=true/false/null，autoScore/fullScore，judgedBy=AUTO/TEACHER/PENDING）。 */
data class QuestionResult(
    val questionId: String, val type: String, val stem: String,
    val studentValue: String, val standardAnswer: String,
    val correct: Boolean?, val autoScore: Double, val finalScore: Double?, val fullScore: Double,
    val judgedBy: String,
)
data class SimilarityPair(
    val submissionA: String, val studentA: String,
    val submissionB: String, val studentB: String,
    val similarity: Double, val sharedFragments: List<String>,
)
data class ReviewQueueItem(
    val submissionId: String, val studentId: String, val studentName: String,
    val version: Int, val priority: Double, val reasons: List<String>, val aiSuggestedScore: Double,
)
data class KnowledgeMastery(val point: String, val sampleCount: Int, val avgScoreRate: Double)
data class Analytics(
    val courseId: String, val assignmentCount: Int, val submissionCount: Int,
    val knowledgeMastery: List<KnowledgeMastery>, val weakPoints: List<String>,
    val scoreTrend: List<ScoreTrendItem>,
)
data class ScoreTrendItem(val label: String, val avgScore: Double)

/** 跨课程待批阅里的一份提交（文档 3.12 `items[]`，已按 priority 降序）。 */
data class PendingReviewItem(
    val submissionId: String, val assignmentId: String, val assignmentTitle: String,
    val courseId: String, val courseName: String, val studentId: String, val studentName: String,
    val submittedAt: String, val waitHours: Double, val version: Int, val status: String,
    val aiSuggestedScore: Double?, val riskLevel: String, val priority: Double, val urgent: Boolean,
    val reasons: List<String>,
)
data class PendingAssignmentGroup(
    val assignmentId: String, val title: String, val deadline: String,
    val pendingCount: Int, val urgentCount: Int,
)
data class PendingCourseGroup(
    val courseId: String, val courseName: String, val pendingCount: Int, val assignmentCount: Int,
    val urgentCount: Int, val oldestWaitHours: Double, val assignments: List<PendingAssignmentGroup>,
)
/** 跨课程待批阅聚合（文档 3.12：scope=TEACHER 只看该老师的班级对应课程 / ALL 全部课程）。 */
data class PendingReview(
    val teacherName: String, val scope: String, val total: Int, val courseCount: Int,
    val assignmentCount: Int, val studentCount: Int, val urgentCount: Int, val highRiskCount: Int,
    val oldestWaitHours: Double, val avgWaitHours: Double,
    val groups: List<PendingCourseGroup>, val items: List<PendingReviewItem>,
)

// 8081 服务
data class RecordingUploadResult(
    val recordingId: String, val url: String, val score: Double, val storedAt: String,
    /** 回显的端角色 teacher / student（10.3）。 */
    val role: String = "",
    /** 平均电平 dBFS（-20 左右为正常音量），服务端从 WAV 波形算出。 */
    val avgLevelDb: Double? = null,
    /** 信噪比 dB，仅 WAV/PCM 有值。 */
    val snrDb: Double? = null,
)
/** 8081 健康检查（10.2 + 10.5：`redis` 字段 up/down，down 时纪要/会话/简易签到写入会 503）。 */
data class ObsHealth(val status: String, val redis: String, val time: String)
data class SessionInfo(
    val sessionId: String, val classId: String, val startedAt: String, val state: String,
)
data class RecordingFuseItem(
    val recordingId: String, val classId: String, val sessionId: String, val deviceId: String,
    val format: String? = null, val sampleRate: Int? = null, val durationSec: Double? = null,
    val sizeBytes: Long? = null, val storedUrl: String? = null,
    /** teacher / student；不传按学生端（10.4）。 */
    val role: String? = null,
    /** 建议把上传返回的这两个值原样带回（WAV 的 SNR 只有上传时算得出来）。 */
    val avgLevelDb: Double? = null, val snrDb: Double? = null,
)
data class RecordingFuseResult(
    val sessionId: String, val primaryRecordingId: String,
    val mergedSources: List<String>, val strategy: String,
    val selectedRole: String = "", val fallback: Boolean = false,
    val abnormalIds: List<String> = emptyList(),
)
data class AttendanceQuiz(val question: String, val hint: String, val sourceNoteTitle: String)
data class AttendanceSession(
    val id: String, val courseId: String, val title: String, val mode: String,
    val state: String, val publisher: String, val createdAt: String, val expireAt: String,
    val lateAfterSeconds: Int, val quiz: AttendanceQuiz?,
)
data class AttendanceCode(
    val sessionId: String, val code: String, val qrContent: String,
    val windowSeconds: Int, val remainingSeconds: Int,
)
data class AttendanceRecord(
    val id: String, val sessionId: String, val studentId: String, val studentName: String,
    val checkedInAt: String, val status: String, val method: String, val answer: String?,
    val secondsAfterStart: Long, val distanceMeters: Double?, val deviceId: String?,
    val riskScore: Int, val riskReasons: List<String>,
)
data class AttendanceStats(
    val expected: Int, val checked: Int, val late: Int, val absent: Int,
    val checkRate: Double, val avgSeconds: Double,
)
data class AttendanceRisk(
    val recordId: String, val studentId: String, val studentName: String,
    val riskScore: Int, val reasons: List<String>, val checkedInAt: String,
)
data class AttendanceRealtime(
    val expected: Int, val checked: Int, val checkRate: Double, val elapsedSeconds: Long,
    val latest: List<AttendanceRealtimeItem>,
)
data class AttendanceRealtimeItem(
    val studentName: String, val secondsAfterStart: Long, val status: String,
)

// ── 教师端（文档第 5 节：角色体系 + 建课建班 + 控制台聚合 + 开课下课） ──

/** 需要同时拿「数据 + 失败文案」的接口用这个包一层（建课/建班/入班/开课下课）。 */
class ApiResult<T>(val data: T?, val error: String?)

/** 班级（含邀请码）。 */
data class ClassRoomInfo(
    val id: String, val name: String, val courseId: String, val courseName: String,
    val teacherName: String, val term: String, val inviteCode: String,
    val studentCount: Int, val createdAt: String,
)

/** 我教了哪些班（顶部概览三数 + 班级列表）。 */
data class TeacherOverview(
    val teacherId: String, val teacherName: String,
    val classCount: Int, val studentCount: Int, val courseCount: Int,
    val classes: List<ClassRoomInfo>,
)

data class ClassStudent(val studentId: String, val name: String)

/** 控制台聚合里的今日课程项（state: ON=上课中 / OFF=未开课；todayCheckRate 可能为 null=今日未发签到）。 */
data class TeacherTodayCourse(
    val courseId: String, val courseName: String, val classId: String, val className: String,
    val studentCount: Int, val state: String, val startedAt: String, val todayCheckRate: Double?,
)

/** 控制台聚合（教师端首屏一次返回：今日课程 + 待批阅红点 + 今日签到率）。 */
data class TeacherConsole(
    val teacherId: String, val teacherName: String, val date: String,
    val todayCourses: List<TeacherTodayCourse>,
    val pendingReview: Int, val hasPending: Boolean, val todayCheckRate: Double,
    val todaySessionCount: Int, val checkedToday: Int, val expectedToday: Int,
)

/** 课堂上课状态（state: ON / OFF）。 */
data class ClassState(
    val courseId: String, val courseName: String, val state: String,
    val startedAt: String?, val endedAt: String?,
)

// ───────────────────────── 解析辅助 ─────────────────────────

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/** 从 8080 的壳里取出 data（code==0 时），保留数组、对象、基本值和 null。 */
private fun JsonElement.asDataOrThrow(): JsonElement {
    val obj = this as? JsonObject
        ?: throw ApiException("服务器响应异常，请稍后重试")
    val code = obj["code"]?.jsonPrimitive?.content?.toIntOrNull()
        ?: throw ApiException("服务器响应异常，请稍后重试")
    if (code != 0) {
        val msg = obj["message"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: "操作失败，请稍后重试"
        throw ApiException(msg)
    }
    return obj["data"] ?: JsonNull
}

/** 取 data 数组（没有数组 data 时返回空数组）。 */
private fun JsonElement.dataArrayOrEmpty(@Suppress("UNUSED_PARAMETER") name: String): JsonArray =
    asDataOrThrow() as? JsonArray ?: JsonArray(emptyList())

/** 取 data 对象（data 不是对象时返回 null）。 */
private fun JsonElement.dataObjectOrNull(@Suppress("UNUSED_PARAMETER") name: String): JsonObject? =
    asDataOrThrow() as? JsonObject

/** 8081 裸 JSON 的错误处理：失败体是 `{"error":"..."}`。 */
private fun JsonElement.asRawOrThrow(): JsonObject {
    val obj = this as? JsonObject ?: throw ApiException("服务器响应异常，请稍后重试")
    obj["error"]?.jsonPrimitive?.content?.let { throw ApiException(it) }
    return obj
}

private fun String.opt(): String = if (isNullOrBlank()) "" else this

private fun JsonElement?.str(): String = this?.jsonPrimitive?.content ?: ""
private fun JsonElement?.strOrNull(): String? {
    val s = this?.jsonPrimitive?.content
    return if (s.isNullOrBlank()) null else s
}
private fun JsonElement?.num(): Double = this?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
private fun JsonElement?.int(): Int = this?.jsonPrimitive?.content?.toIntOrNull() ?: 0
private fun JsonElement?.bool(): Boolean = this?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

class ApiException(message: String) : Exception(message)

// ───────────────────────── 主对象 ─────────────────────────

object KewuApi {
    val BASE_URL = "http://47.99.42.168:8080"
    // 2026-10-26：原 8081 录音 / 纪要 / 会话 / AI 服务已并入 8080，对外只剩一个端口；
    // 保留 OBS_URL 这个名字只为少改调用处（这些接口仍是不套壳的裸 JSON，解析方式不变）
    val OBS_URL = BASE_URL
    // ★ 2026-10-26：业务请求统一 20s 超时（平台引擎 read 超时是给大模型用的 120s，业务接口不该挂那么久）；
    //   录音 / 附件上传在调用处单独放宽。
    private val client = platformHttpClient().config {
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 20_000
        }
    }

    /** 登录失效事件（任一接口返回 401 时 +1）；App 根部监听后提示并回到登录页。 */
    val sessionExpired = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** 同一时刻只拉一次 /user/me，30s 内重复调用直接复用结果（8 个页面都会「没有就拉」）。 */
    private val meLock = kotlinx.coroutines.sync.Mutex()
    private var meFetchedAt = 0L

    var token: String? = kvGet("kewu_token")
    var username: String? = kvGet("kewu_username")
    var currentUser: UserMe? = null

    /**
     * 昵称本地缓存（按账号，2026-10-26）：自动登录后 /user/me 还没回来或失败（后端宕机 / 弱网）时，
     * 旧版各页面回退显示登录账号（学号 "1" 之类），首页问候栏就成了「1」。现在登录 / me 成功即落 kv，
     * 拿不到就用缓存昵称，再没有就用调用方给的称呼（「同学」「老师」），不再把账号当名字展示。
     */
    private fun nickKey(user: String) = "kewu_nick_$user"

    private fun cacheNickname(nick: String?) {
        val u = username ?: return
        if (!nick.isNullOrBlank()) kvSet(nickKey(u), nick)
    }

    /** 展示用名字：/user/me 昵称 → 本账号缓存昵称 → [fallback]。 */
    fun displayName(fallback: String): String =
        currentUser?.user?.nickname
            ?: username?.let { kvGet(nickKey(it)) }?.takeIf { it.isNotBlank() }
            ?: fallback

    /** 提交给后端的姓名（签到 / 发布人等）：昵称 → 缓存昵称 → 账号。 */
    fun nameForServer(): String = displayName(username.orEmpty())

    private fun authHeaders(): Map<String, String> =
        if (token != null) mapOf(HttpHeaders.Authorization to "Bearer $token") else emptyMap()

    /** 通用 GET（8080 壳）。返回原始 JsonElement；业务错误抛 [ApiException]。 */
    private suspend fun getJson(url: String, auth: Boolean = false): JsonElement {
        // 带 token 就带上（后端整改鉴权后所有业务接口都要；未整改前多带一个头无副作用）
        val resp = client.get(url) { authHeaders().forEach { (k, v) -> header(k, v) } }
        return parseBody(resp)
    }

    /**
     * 统一解析响应（2026-10-26）：Ktor 默认不对 4xx/5xx 抛异常，旧代码 catch ResponseException 是死代码，
     * 非 JSON 的错误页（网关 502 HTML 等）解析失败只能显示「网络异常」。现在：
     * 401 → 清登录态 + [sessionExpired] 事件；其它非 2xx 且不是 JSON → 明确的「服务器开小差（HTTP xxx）」。
     */
    private suspend fun parseBody(resp: io.ktor.client.statement.HttpResponse): JsonElement {
        val code = resp.status.value
        if (code == 401) {
            onUnauthorized()
            throw ApiException("登录已过期，请重新登录")
        }
        val text = resp.bodyAsText()
        return try {
            json.parseToJsonElement(text)
        } catch (e: CancellationException) { throw e }
    catch (e: Exception) {
            if (code !in 200..299) throw ApiException("服务器开小差了（HTTP $code），请稍后重试")
            throw e
        }
    }

    private fun onUnauthorized() {
        if (token == null) return
        token = null
        kvSet("kewu_token", null)
        sessionExpired.value = sessionExpired.value + 1
    }

    /** 退出登录：清 token / 账号 / 内存里的用户信息（各本地缓存由调用方清）。 */
    fun logout() {
        token = null
        username = null
        currentUser = null
        meFetchedAt = 0L
        kvSet("kewu_token", null)
        kvSet("kewu_username", null)
    }

    /** 通用 POST（8080 壳）。[bodyObj] 为 Kotlin Map/List，转 JSON 字符串发送。 */
    private suspend fun postJson(url: String, bodyObj: Any?, auth: Boolean = false): JsonElement {
        val resp = client.post(url) {
            authHeaders().forEach { (k, v) -> header(k, v) }
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonElement.serializer(), toJsonElement(bodyObj)))
        }
        return parseBody(resp)
    }

    /** 从错误响应体里尽量抽出 message / error 字段。 */
    private fun parseError(text: String): String {
        return try {
            val el = json.parseToJsonElement(text)
            (el as? JsonObject)?.get("message")?.jsonPrimitive?.content
                ?: (el as? JsonObject)?.get("error")?.jsonPrimitive?.content
                ?: text
        } catch (e: CancellationException) { throw e }
    catch (_: Exception) { text }
    }

    /** 把普通 Kotlin 值转成 kotlinx JsonElement（用于 POST body）。
     *  ★ 项目**没开** kotlinx.serialization 编译插件，不能写 `encodeToJsonElement(map)`
     *    （Map<String,Any?> 在编译期没有序列化器会直接编不过）。这里手搓 JsonObject/JsonPrimitive。 */
    private fun toJsonElement(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Map<*, *> -> JsonObject(
            v.mapNotNull { (k, value) -> if (k is String) k to jsonValue(value) else null }.toMap(),
        )
        else -> jsonValue(v)
    }

    private fun jsonValue(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> toJsonElement(value)
        is List<*> -> JsonArray(value.map { jsonValue(it) })
        else -> JsonPrimitive(value.toString())
    }

    private fun queryUrl(path: String, name: String, value: String): String =
        URLBuilder(path).apply { parameters.append(name, value) }.buildString()

    private fun pathSegment(value: String): String =
        URLBuilder("$BASE_URL/").apply { pathSegments = listOf(value) }
            .buildString().substringAfter("$BASE_URL/")

    private fun safeFilename(value: String): String =
        value.replace(Regex("[\\\\\"\\r\\n]"), "_").ifBlank { "recording.bin" }

    // ── 用户模块 ──

    /** 登录：成功返回 null，失败返回适合直接展示的 message。
     *  后端 2026-10-20 起在响应里返回 `role`（顶层与 user 内都有，TEACHER/STUDENT），
     *  成功时落 kv `kewu_role`（小写 teacher/student），App.kt 据此分流教师端/学生端。 */
    suspend fun login(username: String, password: String): String? = try {
        val el = postJson(
            "$BASE_URL/api/user/login",
            mapOf("username" to username, "password" to password),
        )
        val data = el.asDataOrThrow() as? JsonObject
            ?: throw ApiException("服务器响应异常，请稍后重试")
        token = data["token"].str().opt().ifEmpty { null }
        if (token == null) throw ApiException("登录状态异常，请稍后重试")
        this.username = username
        currentUser = null
        kvSet("kewu_token", token)
        kvSet("kewu_username", username)
        // 服务端角色为准（登录页的角色选择只是注册默认值，见 5.1）
        val role = data["role"].str().ifEmpty { (data["user"] as? JsonObject)?.get("role").str() }
        if (role.isNotBlank()) kvSet("kewu_role", role.lowercase())
        cacheNickname(data["nickname"].strOrNull() ?: (data["user"] as? JsonObject)?.get("nickname").strOrNull())
        null
    } catch (e: CancellationException) { throw e }
    catch (e: ApiException) { officialAuthMessage(e.message, isRegister = false) }
    catch (e: Exception) { networkErrorMessage(e) }

    /** 注册：成功返回 null，失败返回 message。注册后不会返回 token，需再调 login。
     *  [role] 传 "TEACHER"/"STUDENT"（后端 2026-10-20 新增，不传默认 STUDENT）。 */
    suspend fun register(username: String, password: String, nickname: String, role: String = "", school: String = ""): String? = try {
        val body = mutableMapOf<String, Any?>(
            "username" to username, "password" to password, "nickname" to nickname,
        )
        if (role.isNotBlank()) body["role"] = role
        // 2026-10-26：注册选的学校存后端（旧版只写手机本地 kv，换手机 / 重装后「未填写学校」）
        if (school.isNotBlank()) body["school"] = school
        val el = postJson("$BASE_URL/api/user/register", body)
        el.asDataOrThrow()
        null
    } catch (e: CancellationException) { throw e }
    catch (e: ApiException) { officialAuthMessage(e.message, isRegister = true) }
    catch (e: Exception) { networkErrorMessage(e) }

    /**
     * 把后端口语化的原始 message 改写为对用户正式的提示（2026-10-19 用户要求：
     * 「错误提示要官方一点」，如后端登录失败原文「用户名或密码不对」过于口语）。
     * 已知模式按 [officialAuthMessage] 映射；未命中模式保留原文（多为已足够正式的规则文案，
     * 如「课程不存在」）。空白 message 兜底为通用失败提示。
     */
    private fun officialAuthMessage(raw: String?, isRegister: Boolean): String {
        val m = raw?.trim().orEmpty()
        val fallback = if (isRegister) "注册失败，请检查输入信息" else "登录失败，请稍后重试"
        if (m.isEmpty()) return fallback
        // ★ 后端会把多条校验失败拼进同一条 message（实测：「用户名不能为空, 密码至少 6 位」）。
        //   早期实现只映射命中的第一条，导致「密码不足 6 位」被误报成「请输入学号」。
        //   这里按逗号/中文逗号拆分成多条，各自映射后用「；」合并，一条不漏地告诉用户。
        val parts = m.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return m
        val mapped = parts.map { mapSingleAuthMessage(it) }.distinct()
        return mapped.joinToString("；")
    }

    /** 单条校验文案的正式化映射（[officialAuthMessage] 拆分后逐条调用）。 */
    private fun mapSingleAuthMessage(m: String): String {
        val id = idLabel()
        return when {
            "用户名或密码不对" in m || "密码错误" in m || "密码不对" in m || "密码不正确" in m ->
                "${id}或密码不正确，请重新输入"
            "已经被注册" in m || "已存在" in m || "重复" in m ->
                "该${id}已注册，请直接登录"
            "用户名不能为空" in m || "用户名为空" in m || "账号为空" in m ->
                "请输入${id}"
            "密码至少" in m || "密码长度" in m ->
                "密码长度至少 6 位，请重新设置"
            "昵称" in m && "空" in m ->
                "请输入姓名"
            else -> m
        }
    }

    /**
     * 登录/注册的身份标识叫法：学生端叫「学号」，教师端叫「工号」（2026-10-21 用户需求）。
     * 依据 kv `kewu_role`（登录页角色选择即写入，App.kt 用同一个键分流两套 UI）。
     */
    fun idLabel(): String = if (kvGet("kewu_role") == ROLE_TEACHER) "工号" else "学号"

    /** 将传输/解析异常转换为面向用户的正式提示：不暴露技术细节与原始异常串。 */
    private fun networkErrorMessage(error: Exception): String {
        val text = error.message.orEmpty().lowercase()
        return when {
            "timeout" in text || "timed out" in text -> "网络连接超时，请稍后重试"
            "cleartext" in text || "transport security" in text -> "当前网络环境不安全，请更换网络后重试"
            "connection refused" in text || "failed to connect" in text || "connectexception" in text ->
                "暂时无法连接服务器，请稍后重试"
            "unable to resolve" in text || "unknownhost" in text || "no address associated" in text ->
                "网络异常，请检查网络连接"
            "json" in text || "unexpected end" in text || "expected" in text ->
                "服务器响应异常，请稍后重试"
            else -> "网络异常，请稍后重试"
        }
    }

    /** 修改个人资料（昵称 / 学校 / 班级，传 null 的不改）。成功返回 null，失败返回原因。 */
    suspend fun updateProfile(nickname: String? = null, school: String? = null, className: String? = null): String? = try {
        val body = mutableMapOf<String, Any?>()
        if (nickname != null) body["nickname"] = nickname
        if (school != null) body["school"] = school
        if (className != null) body["className"] = className
        postJson("$BASE_URL/api/user/profile", body, auth = true).asDataOrThrow()
        meFetchedAt = 0L
        null
    } catch (e: ApiException) { e.message }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { "网络异常，请稍后重试" }

    /** 绑定课程（需 token）。 */
    suspend fun bindCourse(courseId: String): String? = try {
        val el = postJson("$BASE_URL/api/user/bind-course", mapOf("courseId" to courseId), auth = true)
        el.asDataOrThrow()
        null
    } catch (e: ApiException) { e.message }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { "网络异常，请稍后重试" }

    /** 我的资料（需 token），填充 [currentUser]；失败返回 message。 */
    suspend fun me(force: Boolean = false): String? = meLock.withLock { meLocked(force) }

    private suspend fun meLocked(force: Boolean): String? = try {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        if (!force && currentUser != null && now - meFetchedAt < 30_000) return null
        val el = getJson("$BASE_URL/api/user/me", auth = true)
        val data = el.asDataOrThrow() as? JsonObject
            ?: throw ApiException("服务器响应异常，请稍后重试")
        val u = data["user"] as? JsonObject
        val courses = (data["courses"] as? JsonArray)?.mapNotNull { c -> c as? JsonObject }.orEmpty()
            .map { CourseInfo(it["id"].str(), it["name"].str(), it["description"].str(), it["teacher"].str()) }
        currentUser = UserMe(
            user = u?.let {
                UserInfo(
                    it["id"].str(), it["username"].str(), it["nickname"].strOrNull(), it["role"].str(),
                    school = it["school"].strOrNull(), className = it["className"].strOrNull(),
                )
            },
            courses = courses,
        )
        meFetchedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
        cacheNickname(currentUser?.user?.nickname)
        null
    } catch (e: ApiException) { e.message }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { "网络异常，请稍后重试" }

    // ── 课程模块 ──

    /** 课程列表（无需 token）。失败返回 null（调用方降级 mock）。 */
    suspend fun getCourses(): List<CourseInfo>? = try {
        val el = getJson("$BASE_URL/api/courses")
        el.dataArrayOrEmpty("data").mapNotNull { (it as? JsonObject)?.let { o ->
            CourseInfo(o["id"].str(), o["name"].str(), o["description"].str(), o["teacher"].str())
        } }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    /** 课程详情（无需 token）。 */
    suspend fun getCourse(courseId: String): CourseInfo? = try {
        val el = getJson("$BASE_URL/api/courses/${pathSegment(courseId)}")
        val data = el.dataObjectOrNull("data") ?: return null
        CourseInfo(data["id"].str(), data["name"].str(), data["description"].str(), data["teacher"].str())
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    /** 课堂纪要列表（无需 token）。失败返回 null（调用方降级 mock）。 */
    suspend fun getNotes(courseId: String): List<ClassNoteInfo>? = try {
        val el = getJson("$BASE_URL/api/courses/${pathSegment(courseId)}/notes")
        el.dataArrayOrEmpty("data").map { it as JsonObject }.map {
            ClassNoteInfo(
                it["id"].str(), it["courseId"].str(), it["title"].str(), it["content"].str(),
                it["author"].str(), it["createdAt"].str(),
            )
        }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    /** 单条纪要（无需 token）。 */
    suspend fun getNote(courseId: String, noteId: String): ClassNoteInfo? = try {
        val el = getJson("$BASE_URL/api/courses/${pathSegment(courseId)}/notes/${pathSegment(noteId)}")
        val data = el.dataObjectOrNull("data") ?: return null
        ClassNoteInfo(
            data["id"].str(), data["courseId"].str(), data["title"].str(), data["content"].str(),
            data["author"].str(), data["createdAt"].str(),
        )
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    // ── 作业模块 ──

    /** 作业列表（无需 token）。失败返回 null（调用方降级 mock）。 */
    suspend fun getAssignments(courseId: String? = null): List<AssignmentInfo>? = try {
        val suffix = courseId?.let {
            URLBuilder("$BASE_URL/api/assignments").apply {
                parameters.append("courseId", it)
            }.buildString().removePrefix("$BASE_URL/api/assignments")
        } ?: ""
        val el = getJson("$BASE_URL/api/assignments$suffix")
        el.dataArrayOrEmpty("data").mapNotNull { (it as? JsonObject)?.let { obj ->
            AssignmentInfo(
                id = obj["id"].str(), courseId = obj["courseId"].str(), title = obj["title"].str(),
                description = obj["description"].str(), deadline = obj["deadline"].str(), totalScore = obj["totalScore"].int(),
                knowledgePoints = (obj["knowledgePoints"] as? JsonArray).orEmpty().map { it.str() },
                publisher = obj["publisher"].str(), createdAt = obj["createdAt"].str(),
                questions = parseAssignmentQuestions(obj),
            )
        } }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    /** 作业详情（无需 token）。 */
    suspend fun getAssignment(id: String): AssignmentInfo? = try {
        val el = getJson("$BASE_URL/api/assignments/${pathSegment(id)}")
        val wrap = el.dataObjectOrNull("data") ?: return null
        // ★ 2026-10-26：详情接口实际返回 data = { assignment: {...}, stats: {...} }，旧代码直接从 data 取字段，
        //   导致学生端进作业详情看不到题目（questions 为空）、教师端详情字段为空；兼容旧的平铺结构
        val data = (wrap["assignment"] as? JsonObject) ?: wrap
        AssignmentInfo(
            id = data["id"].str(), courseId = data["courseId"].str(), title = data["title"].str(),
            description = data["description"].str(), deadline = data["deadline"].str(),
            totalScore = data["totalScore"].int(),
            knowledgePoints = (data["knowledgePoints"] as? JsonArray).orEmpty().map { it.str() },
            publisher = data["publisher"].str(), createdAt = data["createdAt"].str(),
            questions = parseAssignmentQuestions(data),
        )
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    /**
     * 解析作业题目（2026-10-25）：后端若落原生 `questions` 字段优先用之；否则从 `description`
     * 透传块 `[KEWU_QUESTIONS]…[/KEWU_QUESTIONS]` 提取。兼容历史纯文本作业（返回空）。
     */
    private fun parseAssignmentQuestions(o: JsonObject): List<Question> {
        val native = (o["questions"] as? JsonArray)
        if (native != null && native.isNotEmpty()) {
            return runCatching { QuestionStore.parseQuestionsJson(native.toString()) }
                .getOrDefault(emptyList())
        }
        val desc = o["description"]?.jsonPrimitive?.content ?: return emptyList()
        return QuestionStore.extractFromDescription(desc)
    }

    /** 统计看板（无需 token）。失败返回 null。 */
    suspend fun getAssignmentStats(id: String): AssignmentStats? = try {
        val el = getJson("$BASE_URL/api/assignments/${pathSegment(id)}/stats")
        val d = el.dataObjectOrNull("data") ?: return null
        AssignmentStats(
            submittedCount = d["submittedCount"].int(), reviewedCount = d["reviewedCount"].int(),
            pendingCount = d["pendingCount"].int(), avgScore = d["avgScore"].num(),
            maxScore = d["maxScore"].num(), minScore = d["minScore"].num(),
            scoreDistribution = (d["scoreDistribution"] as? JsonArray).orEmpty().map { b ->
                val bo = b as JsonObject
                ScoreBucket(bo["label"].str(), bo["count"].int())
            },
            avgVersions = d["avgVersions"].num(), aiGapAvg = d["aiGapAvg"].num(),
            highRiskCount = d["highRiskCount"].int(),
        )
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    /** 提交作业（无需 token；同一个学生会自动版本+1）。[answers] 非空时按文档 3.12 原生字段发送（客观题服务端自动判分）。 */
    suspend fun submitAssignment(
        id: String, studentId: String, studentName: String, content: String,
        answers: Map<String, String> = emptyMap(),
        attachments: List<AttachmentInfo> = emptyList(),
        durationSec: Int? = null,
    ): String? = try {
        val body = mutableMapOf<String, Any?>(
            "studentId" to studentId, "studentName" to studentName, "content" to content,
        )
        // 原生按题作答：[{questionId, value}]。content 仍保留可读文本兼容老前端/查重。
        if (answers.isNotEmpty()) {
            body["answers"] = answers.map { (k, v) -> mapOf("questionId" to k, "value" to v) }
        }
        // 作答用时（秒，2026-10-26 后端 durationSec），老师端「学生作业详情」展示
        if (durationSec != null) body["durationSec"] = durationSec
        // 附件（作业附件需求文档 §3）：只传已上传成功的服务端附件
        if (attachments.isNotEmpty()) {
            body["attachments"] = attachments.map { a ->
                mapOf(
                    "id" to a.id, "kind" to a.kind.value, "name" to a.name, "url" to a.url,
                    "mime" to a.mime, "size" to a.size, "durationSec" to a.durationSec,
                )
            }
        }
        val el = postJson("$BASE_URL/api/assignments/${pathSegment(id)}/submissions", body)
        el.asDataOrThrow()
        null
    } catch (e: ApiException) { e.message }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { "网络异常，请稍后重试" }

    /**
     * 上传一个作业附件（作业附件需求文档 §2：`POST /api/uploads`，multipart 字段 `file` + `kind` + `uploader`）。
     * 成功返回 (附件, null)；失败返回 (null, 可展示原因)。后端未上线时（404）给出明确提示，绝不静默丢附件。
     */
    suspend fun uploadAttachment(a: LocalAttachment): Pair<AttachmentInfo?, String?> = try {
        val bytes = platformReadFile(a.path) ?: return null to "读取本地文件失败"
        val content = MultiPartFormDataContent(formData {
            append("kind", a.kind.value)
            append("uploader", username ?: "")
            if (a.durationSec != null) append("durationSec", a.durationSec.toString())
            append("file", bytes, Headers.build {
                append(HttpHeaders.ContentType, a.mime)
                append(HttpHeaders.ContentDisposition, "filename=\"${safeFilename(a.name)}\"")
            })
        })
        val resp = client.post("$BASE_URL/api/uploads") {
            timeout { requestTimeoutMillis = 300_000; socketTimeoutMillis = 120_000 }
            authHeaders().forEach { (k, v) -> header(k, v) }
            setBody(content)
        }
        if (resp.status.value == 404) {
            null to "服务器暂不支持附件上传（后端附件接口未上线）"
        } else {
            val d = json.parseToJsonElement(resp.bodyAsText()).asDataOrThrow() as? JsonObject
                ?: throw ApiException("服务器响应异常，请稍后重试")
            parseAttachment(d)?.let { it to null } ?: (null to "服务器未返回附件地址")
        }
    } catch (e: CancellationException) { throw e }
    catch (e: ApiException) { null to e.message }
    catch (_: Exception) { null to "网络异常，请稍后重试" }

    /**
     * 课前资料（2026-10-26 后端 `POST /api/courses/{id}/materials`）：老师开课前上传本节 PPT / 讲义，
     * AI 后续结合课堂录音分析。[attachments] 必须是 [uploadAttachment] 返回过的。成功返回 null，失败返回原因。
     */
    suspend fun createCourseMaterial(
        courseId: String, title: String, note: String, attachments: List<AttachmentInfo>,
    ): String? = try {
        val body = mapOf(
            "title" to title, "note" to note, "uploader" to nameForServer(),
            "attachments" to attachments.map { a ->
                mapOf(
                    "id" to a.id, "kind" to a.kind.value, "name" to a.name, "url" to a.url,
                    "mime" to a.mime, "size" to a.size, "durationSec" to a.durationSec,
                )
            },
        )
        postJson("$BASE_URL/api/courses/${pathSegment(courseId)}/materials", body, auth = true).asDataOrThrow()
        null
    } catch (e: ApiException) { e.message }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { "网络异常，请稍后重试" }

    private fun parseAttachment(o: JsonObject): AttachmentInfo? {
        val url = o["url"].str().takeIf { it.isNotBlank() } ?: return null
        return AttachmentInfo(
            id = o["id"].str(), kind = AttachmentKind.from(o["kind"].strOrNull()),
            name = o["name"].str().ifBlank { url.substringAfterLast('/') }, url = url,
            mime = o["mime"].str(), size = (o["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
            durationSec = (o["durationSec"] as? JsonPrimitive)?.content?.toIntOrNull(),
        )
    }

    /** 我的提交（版本轨迹）。失败返回 null。 */
    suspend fun getMySubmissions(id: String, studentId: String): SubmissionInfo? = try {
        val el = getJson(queryUrl("$BASE_URL/api/assignments/${pathSegment(id)}/my", "studentId", studentId))
        parseSubmission(el.dataObjectOrNull("data") ?: return null)
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    /** 全部提交（老师看）。失败返回 null。 */
    suspend fun getSubmissions(id: String): List<SubmissionInfo>? = try {
        val el = getJson("$BASE_URL/api/assignments/${pathSegment(id)}/submissions")
        el.dataArrayOrEmpty("data").map { parseSubmission(it as JsonObject) }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    /** 提交详情（含历史版本）。失败返回 null。 */
    suspend fun getSubmission(id: String): SubmissionInfo? = try {
        val el = getJson("$BASE_URL/api/submissions/${pathSegment(id)}")
        parseSubmission(el.dataObjectOrNull("data") ?: return null)
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    /** AI 预批阅（POST {}）。失败返回 null。 */
    suspend fun aiPrereview(submissionId: String): AiSuggestion? = try {
        val el = postJson("$BASE_URL/api/submissions/${pathSegment(submissionId)}/ai-prereview", emptyMap<String, Any?>())
        parseAiSuggestion(el.dataObjectOrNull("data") ?: return null)
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    /** 相似片段检测（查重）。失败返回 null。 */
    suspend fun getSimilarity(id: String): List<SimilarityPair>? = try {
        val el = getJson("$BASE_URL/api/assignments/${pathSegment(id)}/similarity")
        el.dataArrayOrEmpty("data").map { it as JsonObject }.map {
            SimilarityPair(
                submissionA = it["submissionA"].str(), studentA = it["studentA"].str(),
                submissionB = it["submissionB"].str(), studentB = it["studentB"].str(),
                similarity = it["similarity"].num(),
                sharedFragments = (it["sharedFragments"] as? JsonArray).orEmpty().map { f -> f.str() },
            )
        }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    /** 智能批阅队列。失败返回 null。 */
    suspend fun getReviewQueue(id: String): List<ReviewQueueItem>? = try {
        val el = getJson("$BASE_URL/api/assignments/${pathSegment(id)}/review-queue")
        el.dataArrayOrEmpty("data").map { it as JsonObject }.map {
            ReviewQueueItem(
                submissionId = it["submissionId"].str(), studentId = it["studentId"].str(),
                studentName = it["studentName"].str(), version = it["version"].int(),
                priority = it["priority"].num(),
                reasons = (it["reasons"] as? JsonArray).orEmpty().map { r -> r.str() },
                aiSuggestedScore = it["aiSuggestedScore"].num(),
            )
        }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    /** 知识点学情画像。失败返回 null。 */
    suspend fun getAnalytics(courseId: String): Analytics? = try {
        val el = getJson("$BASE_URL/api/analytics/courses/${pathSegment(courseId)}")
        val d = el.dataObjectOrNull("data") ?: return null
        Analytics(
            courseId = d["courseId"].str(), assignmentCount = d["assignmentCount"].int(),
            submissionCount = d["submissionCount"].int(),
            knowledgeMastery = (d["knowledgeMastery"] as? JsonArray).orEmpty().map { m ->
                val mo = m as JsonObject
                KnowledgeMastery(mo["point"].str(), mo["sampleCount"].int(), mo["avgScoreRate"].num())
            },
            weakPoints = (d["weakPoints"] as? JsonArray).orEmpty().map { it.str() },
            scoreTrend = (d["scoreTrend"] as? JsonArray).orEmpty().map { t ->
                val to = t as JsonObject
                ScoreTrendItem(to["label"].str(), to["avgScore"].num())
            },
        )
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    /** 批阅打分 / 打回（无需 token，当前用参数区分身份）。 */
    suspend fun reviewSubmission(
        id: String, score: Double, comment: String, reviewer: String, action: String,
        questionScores: List<Pair<String, Int>>? = null,
    ): String? = try {
        val body = mutableMapOf<String, Any?>(
            "score" to score, "comment" to comment, "reviewer" to reviewer, "action" to action,
        )
        // 按题给分（文档 3.12 ④）：传了 questionScores 可不传 score，服务端自动按题求和当总分。
        if (questionScores != null) {
            body["questionScores"] = questionScores.map { (qid, sc) -> mapOf("questionId" to qid, "score" to sc) }
        }
        val el = postJson("$BASE_URL/api/submissions/${pathSegment(id)}/review", body)
        el.asDataOrThrow()
        null
    } catch (e: ApiException) { e.message }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { "网络异常，请稍后重试" }

    /** 8081 健康检查。成功返回服务原文，失败返回可展示错误。 */
    suspend fun obsHealth(): String? = try {
        val resp = client.get("$OBS_URL/health")
        resp.bodyAsText().trim().ifEmpty { "ok" }
    } catch (e: ResponseException) {
        e.response.bodyAsText().let { parseError(it) }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { networkErrorMessage(e) }

    /** 8081 健康检查（结构化）：`{status, redis, time}`。连不上返回 null。
     *  redis=down 时服务本身仍 up，但纪要 / 会话 / 简易签到写入会返回 503（10.5）。 */
    suspend fun obsHealthInfo(): ObsHealth? = try {
        val o = json.parseToJsonElement(client.get("$OBS_URL/health").bodyAsText()) as? JsonObject
            ?: throw ApiException("服务器响应异常，请稍后重试")
        ObsHealth(o["status"].str(), o["redis"].str(), o["time"].str())
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    /** 8080 是否在线：`/api/health` 返回纯文本 ok 即在线。 */
    suspend fun isBusinessUp(): Boolean = try {
        client.get("$BASE_URL/api/health").bodyAsText().trim().equals("ok", ignoreCase = true)
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { false }

    /**
     * 跨课程待批阅聚合（文档 3.12）。[teacherId] 非空走教师端入口 `/api/teacher/pending-review`
     * （只聚合该老师所教班级对应的课程，scope=TEACHER）；为空走 `/api/assignments/pending-review`（全部课程，scope=ALL）。
     * 失败返回 null（调用方降级为逐作业 getSubmissions 聚合）。
     */
    suspend fun getPendingReview(teacherId: String? = teacherId()): PendingReview? = try {
        val url = if (teacherId.isNullOrBlank()) "$BASE_URL/api/assignments/pending-review"
            else queryUrl("$BASE_URL/api/teacher/pending-review", "teacherId", teacherId)
        val o = getJson(url).asDataOrThrow() as? JsonObject
            ?: throw ApiException("服务器响应异常，请稍后重试")
        PendingReview(
            teacherName = o["teacherName"].str(), scope = o["scope"].str(),
            total = o["total"].int(), courseCount = o["courseCount"].int(),
            assignmentCount = o["assignmentCount"].int(), studentCount = o["studentCount"].int(),
            urgentCount = o["urgentCount"].int(), highRiskCount = o["highRiskCount"].int(),
            oldestWaitHours = o["oldestWaitHours"].num(), avgWaitHours = o["avgWaitHours"].num(),
            groups = (o["groups"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { g ->
                PendingCourseGroup(
                    courseId = g["courseId"].str(), courseName = g["courseName"].str(),
                    pendingCount = g["pendingCount"].int(), assignmentCount = g["assignmentCount"].int(),
                    urgentCount = g["urgentCount"].int(), oldestWaitHours = g["oldestWaitHours"].num(),
                    assignments = (g["assignments"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { a ->
                        PendingAssignmentGroup(
                            a["assignmentId"].str(), a["title"].str(), a["deadline"].str(),
                            a["pendingCount"].int(), a["urgentCount"].int(),
                        )
                    },
                )
            },
            items = (o["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { i ->
                PendingReviewItem(
                    submissionId = i["submissionId"].str(), assignmentId = i["assignmentId"].str(),
                    assignmentTitle = i["assignmentTitle"].str(), courseId = i["courseId"].str(),
                    courseName = i["courseName"].str(), studentId = i["studentId"].str(),
                    studentName = i["studentName"].str(), submittedAt = i["submittedAt"].str(),
                    waitHours = i["waitHours"].num(), version = i["version"].int(), status = i["status"].str(),
                    aiSuggestedScore = (i["aiSuggestedScore"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                    riskLevel = i["riskLevel"].str(), priority = i["priority"].num(), urgent = i["urgent"].bool(),
                    reasons = (i["reasons"] as? JsonArray).orEmpty().map { it.str() },
                )
            },
        )
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    /** 8080 健康检查。文档规定该接口返回纯文本，不套业务壳。 */
    suspend fun health(): String? = try {
        val resp = client.get("$BASE_URL/api/health")
        resp.bodyAsText().trim().ifEmpty { "ok" }
    } catch (e: ResponseException) {
        e.response.bodyAsText().let { parseError(it) }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { networkErrorMessage(e) }

    /** 发布作业（老师入口；服务端仍应自行校验权限）。[questions] 非空时按文档 3.12 原生字段发送。 */
    suspend fun publishAssignment(
        courseId: String, title: String, description: String = "", deadline: String = "",
        totalScore: Int = 100, knowledgePoints: List<String> = emptyList(), publisher: String = "",
        questions: List<Question> = emptyList(),
    ): AssignmentInfo? = try {
        val body = mutableMapOf<String, Any?>(
            "courseId" to courseId, "title" to title, "description" to description,
            "deadline" to deadline, "totalScore" to totalScore,
            "knowledgePoints" to knowledgePoints, "publisher" to publisher,
        )
        // 原生题目字段：直接发结构化数组（后端 3.12 支持，自动判分）。description 不再塞透传块。
        if (questions.isNotEmpty()) {
            body["questions"] = json.parseToJsonElement(QuestionStore.questionsToJson(questions))
        }
        val el = postJson("$BASE_URL/api/assignments", body, auth = true)
        val data = el.asDataOrThrow() as? JsonObject ?: return null
        AssignmentInfo(
            id = data["id"].str(), courseId = data["courseId"].str(), title = data["title"].str(),
            description = data["description"].str(), deadline = data["deadline"].str(),
            totalScore = data["totalScore"].int(),
            knowledgePoints = (data["knowledgePoints"] as? JsonArray).orEmpty().map { it.str() },
            publisher = data["publisher"].str(), createdAt = data["createdAt"].str(),
            questions = parseAssignmentQuestions(data),
        )
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    /** 多端录音融合：8081 只选择主音源，不实际拼接音频。 */
    suspend fun fuseRecordings(
        sessionId: String, recordings: List<RecordingFuseItem>,
    ): RecordingFuseResult? = try {
        val body = mapOf(
            "sessionId" to sessionId,
            "recordings" to recordings.map { r ->
                mapOf(
                    "recordingId" to r.recordingId, "classId" to r.classId,
                    "sessionId" to r.sessionId, "deviceId" to r.deviceId,
                    "format" to r.format, "sampleRate" to r.sampleRate,
                    "durationSec" to r.durationSec, "sizeBytes" to r.sizeBytes,
                    "storedUrl" to r.storedUrl, "role" to r.role,
                    "avgLevelDb" to r.avgLevelDb, "snrDb" to r.snrDb,
                ).filterValues { it != null }
            },
        )
        val resp = client.post("$OBS_URL/api/recordings/fuse") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonElement.serializer(), toJsonElement(body)))
        }
        val el = json.parseToJsonElement(resp.bodyAsText()).asRawOrThrow()
        RecordingFuseResult(
            sessionId = el["sessionId"].str(), primaryRecordingId = el["primaryRecordingId"].str(),
            mergedSources = (el["mergedSources"] as? JsonArray).orEmpty().map { it.str() },
            strategy = el["strategy"].str(),
            selectedRole = el["selectedRole"].str(), fallback = el["fallback"].bool(),
            abnormalIds = (el["abnormalIds"] as? JsonArray).orEmpty().map { it.str() },
        )
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }


    /** 发起完整签到。老师入口，服务端仍须自行校验权限。 */
    suspend fun createAttendance(
        courseId: String, title: String, mode: String = "QR", durationMinutes: Int = 10,
        lateAfterSeconds: Int = 300, latitude: Double? = null, longitude: Double? = null,
        radiusMeters: Int = 200, publisher: String = "",
    ): AttendanceSession? = try {
        val body = mutableMapOf<String, Any?>("courseId" to courseId, "title" to title, "mode" to mode, "durationMinutes" to durationMinutes, "lateAfterSeconds" to lateAfterSeconds, "radiusMeters" to radiusMeters, "publisher" to publisher)
        if (latitude != null) body["latitude"] = latitude
        if (longitude != null) body["longitude"] = longitude
        val data = postJson("$BASE_URL/api/attendance", body, auth = true).asDataOrThrow()
        parseAttendanceSession(data as? JsonObject ?: return null)
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    suspend fun getAttendances(courseId: String): List<AttendanceSession>? = try {
        val data = getJson(queryUrl("$BASE_URL/api/attendance", "courseId", courseId)).asDataOrThrow()
        (data as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseAttendanceSession) }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    suspend fun getAttendance(id: String): AttendanceSession? = try {
        val data = getJson("$BASE_URL/api/attendance/${pathSegment(id)}").asDataOrThrow()
        val o = data as? JsonObject ?: return null
        // 后端该接口现在是 {session:{...}, stats:{...}} 包一层；兼容旧的扁平 session 对象
        val s = (o["session"] as? JsonObject) ?: o
        parseAttendanceSession(s)
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    suspend fun getAttendanceCode(id: String): AttendanceCode? = try {
        val o = getJson("$BASE_URL/api/attendance/${pathSegment(id)}/code").asDataOrThrow() as? JsonObject ?: return null
        AttendanceCode(o["sessionId"].str(), o["code"].str(), o["qrContent"].str(), o["windowSeconds"].int(), o["remainingSeconds"].int())
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    /**
     * 学生签到（第 4 节完整版）。[courseId] 非空时签到成功后顺带把到场状态写进 8081 简易签到缓存
     * （10.5，录音服务内部的课堂状态缓存，教师端上课面板据此显示「录音端已同步到场」），写缓存失败不影响签到结果。
     */
    suspend fun attendanceCheckIn(id: String, studentId: String, studentName: String = studentId, code: String? = null, answer: String? = null, latitude: Double? = null, longitude: Double? = null, courseId: String? = null): String? = try {
        val body = mutableMapOf<String, Any?>("studentId" to studentId, "studentName" to studentName)
        if (code != null) body["code"] = code
        if (answer != null) body["answer"] = answer
        if (latitude != null) body["latitude"] = latitude
        if (longitude != null) body["longitude"] = longitude
        body["deviceId"] = platformDeviceId()
        postJson("$BASE_URL/api/attendance/${pathSegment(id)}/check-in", body, auth = true).asDataOrThrow()
        if (!courseId.isNullOrBlank()) checkin(courseId, studentId, "present")
        null
    } catch (e: CancellationException) { throw e } catch (e: ApiException) { e.message } catch (_: Exception) { "网络异常，请稍后重试" }

    suspend fun getAttendanceRecords(id: String): List<AttendanceRecord>? = try {
        val data = getJson("$BASE_URL/api/attendance/${pathSegment(id)}/records").asDataOrThrow()
        (data as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseAttendanceRecord) }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    suspend fun getMyAttendance(id: String, studentId: String): AttendanceRecord? = try {
        val data = getJson(queryUrl("$BASE_URL/api/attendance/${pathSegment(id)}/my", "studentId", studentId)).asDataOrThrow()
        (data as? JsonObject)?.let(::parseAttendanceRecord)
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    suspend fun closeAttendance(id: String): String? = try {
        postJson("$BASE_URL/api/attendance/${pathSegment(id)}/close", emptyMap<String, Any?>(), auth = true).asDataOrThrow()
        null
    } catch (e: CancellationException) { throw e } catch (e: ApiException) { e.message } catch (_: Exception) { "网络异常，请稍后重试" }

    suspend fun getAttendanceStats(id: String): AttendanceStats? = try {
        val o = getJson("$BASE_URL/api/attendance/${pathSegment(id)}/stats").asDataOrThrow() as? JsonObject ?: return null
        AttendanceStats(o["expected"].int(), o["checked"].int(), o["late"].int(), o["absent"].int(), o["checkRate"].num(), o["avgSeconds"].num())
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    suspend fun getAttendanceRisk(id: String): List<AttendanceRisk>? = try {
        val data = getJson("$BASE_URL/api/attendance/${pathSegment(id)}/risk").asDataOrThrow()
        (data as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseAttendanceRisk) }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    suspend fun getAttendanceRealtime(id: String): AttendanceRealtime? = try {
        val o = getJson("$BASE_URL/api/attendance/${pathSegment(id)}/realtime").asDataOrThrow() as? JsonObject ?: return null
        AttendanceRealtime(o["expected"].int(), o["checked"].int(), o["checkRate"].num(), o["elapsedSeconds"].int().toLong(), (o["latest"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseAttendanceRealtimeItem) })
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    /** 上传录音（multipart）。[bytes] 为音频字节，[filename] 含扩展名。失败返回 null.
     *  ★ Ktor 2.3.12 已移除 2-arg `submitFormWithBinaryData(url, block)`，必须改用
     *    [MultiPartFormDataContent] + `post { setBody(...) }`。
     *  ★ 不要用裸 `PartData.FormItem/BinaryItem` 手搓 parts——Content-Disposition 必须带
     *    `form-data; ` 前缀，漏了服务端收不到字段（实测 8081 返回 400「缺少 audio 文件或 classId」）。
     *    用 `formData { }` 构建器它会自动补全。也不要手动 `contentType(MultiPart.FormData)`，
     *    会丢掉 boundary，让 [MultiPartFormDataContent] 自己设。 */
    suspend fun uploadRecording(
        path: String, filename: String, classId: String, sessionId: String?, durationSec: Double?,
        role: String = if (kvGet("kewu_role") == ROLE_TEACHER) "teacher" else "student",
        avgLevel: Double? = null,
    ): RecordingUploadResult? = try {
        val content = MultiPartFormDataContent(formData {
            append("classId", classId)
            append("deviceId", platformDeviceId())
            append("sampleRate", "44100")
            append("format", "wav")
            // 10.3：role 不传默认 student；教师端必须显式传 teacher，融合时才会「教师端优先」
            append("role", role)
            if (sessionId != null) append("sessionId", sessionId)
            if (durationSec != null) append("durationSec", durationSec.toString())
            if (avgLevel != null) append("avgLevel", avgLevel.toString())
            // ★ 2026-10-26：流式上传（ChannelProvider 按需读文件），不再把整段录音读进内存
            val size = platformFileSize(path)
            if (size <= 0) throw ApiException("录音文件不存在或为空")
            append("audio", ChannelProvider(size) { platformFileChannel(path) }, Headers.build {
                append(HttpHeaders.ContentType, "audio/wav")
                append(HttpHeaders.ContentDisposition, "filename=\"${safeFilename(filename)}\"")
            })
        })
        val resp = try {
            client.post("$OBS_URL/api/recordings/upload") {
                // 录音文件可能上百 MB：上传单独放宽到 10 分钟
                timeout { requestTimeoutMillis = 600_000; socketTimeoutMillis = 120_000 }
                setBody(content)
            }
        } catch (e: ResponseException) {
            throw ApiException(e.response.bodyAsText().let { parseError(it) })
        }
        val el = json.parseToJsonElement(resp.bodyAsText()).asRawOrThrow()
        RecordingUploadResult(
            recordingId = el["recordingId"].str(), url = el["url"].str(),
            score = el["score"].num(), storedAt = el["storedAt"].str(),
            role = el["role"].str(),
            avgLevelDb = (el["avgLevelDb"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
            snrDb = (el["snrDb"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
        )
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    /**
     * 8081 Redis 缓存类写接口（纪要 / 会话 / 简易签到）的统一 POST：成功返回 null，失败返回可展示文案。
     * 2026-10-21 起 Redis 不可用时返回 `503 {"ok":false,"degraded":true,"error":...}`（不再假成功），
     * 这里把 degraded 翻译成正式提示，让用户明确知道「没存上」。
     */
    private suspend fun postObsCache(path: String, body: Map<String, Any?>): String? = try {
        val resp = client.post("$OBS_URL$path") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonElement.serializer(), toJsonElement(body)))
        }
        val o = json.parseToJsonElement(resp.bodyAsText()) as? JsonObject
            ?: throw ApiException("服务器响应异常，请稍后重试")
        when {
            o["degraded"].bool() -> "课堂缓存服务暂不可用，本次内容未保存，请稍后重试"
            o["ok"].bool() -> null
            else -> o["error"].strOrNull() ?: "保存失败，请稍后重试"
        }
    } catch (e: CancellationException) { throw e }
    catch (e: ApiException) { e.message }
    catch (e: Exception) { networkErrorMessage(e) }

    /** 简易签到（8081 Redis 缓存版，10.5；正式签到走第 4 节）。[status] 默认 "present"。成功返回 null。 */
    suspend fun checkin(classId: String, studentId: String, status: String = "present"): String? =
        postObsCache("/api/checkin", mapOf("classId" to classId, "studentId" to studentId, "status" to status))

    /** 查询签到状态（返回 studentId->status 的 map）。失败返回 null。 */
    suspend fun getCheckins(classId: String): Map<String, String>? = try {
        val resp = client.get("$OBS_URL/api/checkin/${pathSegment(classId)}")
        val el = json.parseToJsonElement(resp.bodyAsText()).asRawOrThrow()
        val checks = el["checkins"] as? JsonObject ?: return emptyMap()
        checks.mapValues { it.value.str() }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    /** 缓存课堂纪要（8081，24h 过期）。成功返回 null，失败返回文案。 */
    suspend fun postNote(classId: String, content: String): String? =
        postObsCache("/api/notes", mapOf("classId" to classId, "content" to content))

    /** 读课堂纪要（GET，无纪要时 404 返回 null 不报错）。 */
    suspend fun getNoteCache(classId: String): String? = try {
        val resp = client.get("$OBS_URL/api/notes/${pathSegment(classId)}")
        val el = json.parseToJsonElement(resp.bodyAsText()).asRawOrThrow()
        el["content"].strOrNull()
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    /** 创建课堂录音协同会话（8081，24h 过期；响应只有 `{ok:true}`）。成功返回 null，失败返回文案。 */
    suspend fun createSession(sessionId: String, classId: String): String? =
        postObsCache("/api/sessions", mapOf("sessionId" to sessionId, "classId" to classId, "state" to "active"))

    /**
     * 课堂协同会话 ID 约定：同一门课同一天的教师端与学生端算出同一个 ID，多端录音据此归到同一会话再融合。
     * [date] 形如 `2026-10-23`。
     */
    fun classSessionId(courseId: String, date: String): String = "$courseId-${date.replace("-", "")}"

    /**
     * 录音设备心跳（2026-10-26 课堂录音雷达）：录音中每 10 秒报一次 status=recording，停止时报 stopped。
     * 后端只画 45 秒内有心跳、且不是 stopped 的设备。时间戳由服务端打（不带客户端时间，避免手机时钟偏差）。
     */
    suspend fun sendRecordingHeartbeat(sessionId: String, status: String, qualityScore: Int = 80): Boolean = try {
        val resp = client.post("$OBS_URL/api/teacher/recording/devices/heartbeat") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("sessionId", sessionId)
                put("deviceId", platformDeviceId())
                put("type", "phone")
                put("status", status)
                put("qualityScore", qualityScore)
                // 雷达上显示名字；老师的点画成黄色
                put("name", nameForServer())
                put("role", if (currentRole() == ROLE_TEACHER) "teacher" else "student")
            }.toString())
        }
        resp.status.value in 200..299
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { false }

    /** 同一课堂会话里正在录音的设备（雷达点）。失败返回 null。 */
    suspend fun getRecordingDevices(sessionId: String): List<RecordingDevice>? = try {
        val resp = client.get(queryUrl("$OBS_URL/api/teacher/recording/devices", "sessionId", sessionId))
        if (resp.status.value !in 200..299) null
        else {
            val o = json.parseToJsonElement(resp.bodyAsText()) as? JsonObject
            (o?.get("devices") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map {
                RecordingDevice(
                    it["deviceId"].str(), it["type"].str(), it["status"].str(), it["qualityScore"].int(),
                    name = it["name"].str(), role = it["role"].str(),
                )
            }
        }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    /** 查课堂会话（GET，无会话 404 返回 null）。 */
    suspend fun getSession(sessionId: String): SessionInfo? = try {
        val resp = client.get("$OBS_URL/api/sessions/${pathSegment(sessionId)}")
        val el = json.parseToJsonElement(resp.bodyAsText()).asRawOrThrow()
        SessionInfo(
            sessionId = el["sessionId"].str(), classId = el["classId"].str(),
            startedAt = el["startedAt"].str(), state = el["state"].str(),
        )
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    // ── 教师端（文档第 5 节，2026-10-20 新增） ──
    //
    // teacherId 约定：后端「可以传用户 id 也可以传用户名」，前端统一传 username（稳定可读）。

    /** 当前用户的教师标识（id 或 username 后端都认）。 */
    fun teacherId(): String = username ?: currentUser?.user?.id ?: ""

    // ── 通知系统（后端由队员实现；当前为本地 kv 兜底，结构对齐需求文档）──
    // ★ 仅服务首页铃铛「系统通知」。AI（小悟）消息走 ChatHistory，不在此列。
    suspend fun getNotifications(): List<Notifications.Info> =
        Notifications.load()

    suspend fun markNotificationRead(id: String) {
        Notifications.markRead(id)
    }

    suspend fun markAllNotificationsRead() {
        Notifications.markAllRead()
    }

    /** 老师新建课程：成功 data 为课程对象（立刻出现在 /api/courses）。 */
    suspend fun createTeacherCourse(name: String, description: String, teacherId: String = teacherId()): ApiResult<CourseInfo> = try {
        val el = postJson(
            "$BASE_URL/api/teacher/courses",
            mapOf(
                "name" to name, "description" to description,
                "teacher" to nameForServer(),
                "teacherId" to teacherId,
            ),
        )
        val o = el.asDataOrThrow() as? JsonObject
            ?: throw ApiException("服务器响应异常，请稍后重试")
        ApiResult(CourseInfo(o["id"].str(), o["name"].str(), o["description"].str(), o["teacher"].str()), null)
    } catch (e: CancellationException) { throw e }
    catch (e: ApiException) { ApiResult(null, e.message) }
    catch (e: Exception) { ApiResult(null, networkErrorMessage(e)) }

    /** 老师开班级（自动生成 6 位邀请码；仅 TEACHER 角色，非老师会失败并返回后端文案）。 */
    suspend fun createTeacherClass(courseId: String, name: String, term: String, teacherId: String = teacherId()): ApiResult<ClassRoomInfo> = try {
        val el = postJson(
            "$BASE_URL/api/teacher/classes",
            mapOf("courseId" to courseId, "name" to name, "term" to term, "teacherId" to teacherId),
        )
        val o = el.asDataOrThrow() as? JsonObject
            ?: throw ApiException("服务器响应异常，请稍后重试")
        ApiResult(parseClassRoom(o), null)
    } catch (e: CancellationException) { throw e }
    catch (e: ApiException) { ApiResult(null, e.message) }
    catch (e: Exception) { ApiResult(null, networkErrorMessage(e)) }

    /** 我教了哪些班哪些课（班数/学生数/课程数概览 + 班级列表）。失败返回 null。 */
    suspend fun getTeacherClasses(teacherId: String = teacherId()): TeacherOverview? = try {
        val el = getJson(queryUrl("$BASE_URL/api/teacher/classes", "teacherId", teacherId))
        val o = el.asDataOrThrow() as? JsonObject
            ?: throw ApiException("服务器响应异常，请稍后重试")
        TeacherOverview(
            teacherId = o["teacherId"].str(),
            teacherName = o["teacherName"].str(),
            classCount = o["classCount"].int(),
            studentCount = o["studentCount"].int(),
            courseCount = o["courseCount"].int(),
            classes = (o["classes"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.map { parseClassRoom(it) }.orEmpty(),
        )
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    /** 班级学生名单。失败返回 null。 */
    suspend fun getClassStudents(classId: String): List<ClassStudent>? = try {
        val el = getJson("$BASE_URL/api/teacher/classes/${pathSegment(classId)}/students")
        el.dataArrayOrEmpty("data").mapNotNull { it as? JsonObject }
            .map { ClassStudent(it["studentId"].str(), it["name"].str()) }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    /** 学生用邀请码加入班级（成功后自动把课程绑到学生名下）。data=后端成功文案（如「已加入《云计算 1 班》」）。 */
    suspend fun joinClassByInvite(inviteCode: String, studentId: String = teacherId()): ApiResult<String> = try {
        val el = postJson(
            "$BASE_URL/api/classes/join",
            mapOf("studentId" to studentId, "inviteCode" to inviteCode.trim().uppercase()),
        )
        el.asDataOrThrow()
        ApiResult((el as? JsonObject)?.get("message").str().ifEmpty { "已加入班级" }, null)
    } catch (e: CancellationException) { throw e }
    catch (e: ApiException) { ApiResult(null, e.message) }
    catch (e: Exception) { ApiResult(null, networkErrorMessage(e)) }

    /** 控制台聚合：教师端首屏一次拿全（今日课程 + 待批阅红点 + 今日签到率）。失败返回 null。 */
    suspend fun getTeacherConsole(teacherId: String = teacherId()): TeacherConsole? = try {
        val el = getJson(queryUrl("$BASE_URL/api/teacher/console", "teacherId", teacherId))
        val o = el.asDataOrThrow() as? JsonObject
            ?: throw ApiException("服务器响应异常，请稍后重试")
        TeacherConsole(
            teacherId = o["teacherId"].str(),
            teacherName = o["teacherName"].str(),
            date = o["date"].str(),
            todayCourses = (o["todayCourses"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.map { c ->
                TeacherTodayCourse(
                    courseId = c["courseId"].str(),
                    courseName = c["courseName"].str(),
                    classId = c["classId"].str(),
                    className = c["className"].str(),
                    studentCount = c["studentCount"].int(),
                    state = c["state"].str(),
                    startedAt = c["startedAt"].str(),
                    todayCheckRate = (c["todayCheckRate"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                )
            }.orEmpty(),
            pendingReview = o["pendingReview"].int(),
            hasPending = o["hasPending"].bool(),
            todayCheckRate = o["todayCheckRate"].num(),
            todaySessionCount = o["todaySessionCount"].int(),
            checkedToday = o["checkedToday"].int(),
            expectedToday = o["expectedToday"].int(),
        )
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    /** 开课/下课（action: START / END）。成功 data 为最新课堂状态。 */
    suspend fun setClassState(courseId: String, action: String, teacherId: String = teacherId()): ApiResult<ClassState> = try {
        val el = postJson(
            "$BASE_URL/api/teacher/class-state",
            mapOf("courseId" to courseId, "action" to action, "teacherId" to teacherId),
        )
        val o = el.asDataOrThrow() as? JsonObject
            ?: throw ApiException("服务器响应异常，请稍后重试")
        ApiResult(parseClassState(o), null)
    } catch (e: CancellationException) { throw e }
    catch (e: ApiException) { ApiResult(null, e.message) }
    catch (e: Exception) { ApiResult(null, networkErrorMessage(e)) }

    /** 查某门课当前是否在上课（没开过课返回 state=OFF，不会 404）。失败返回 null。 */
    suspend fun getClassState(courseId: String): ClassState? = try {
        val el = getJson(queryUrl("$BASE_URL/api/teacher/class-state", "courseId", courseId))
        val o = el.asDataOrThrow() as? JsonObject
            ?: throw ApiException("服务器响应异常，请稍后重试")
        parseClassState(o)
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    private fun parseClassRoom(o: JsonObject) = ClassRoomInfo(
        id = o["id"].str(),
        name = o["name"].str(),
        courseId = o["courseId"].str(),
        courseName = o["courseName"].str(),
        teacherName = o["teacherName"].str(),
        term = o["term"].str(),
        inviteCode = o["inviteCode"].str(),
        studentCount = o["studentCount"].int(),
        createdAt = o["createdAt"].str(),
    )

    private fun parseClassState(o: JsonObject) = ClassState(
        courseId = o["courseId"].str(),
        courseName = o["courseName"].str(),
        state = o["state"].str(),
        startedAt = o["startedAt"].strOrNull(),
        endedAt = o["endedAt"].strOrNull(),
    )

    private fun parseAttendanceSession(o: JsonObject): AttendanceSession {
        val q = (o["quiz"] as? JsonObject)?.let { AttendanceQuiz(it["question"].str(), it["hint"].str(), it["sourceNoteTitle"].str()) }
        return AttendanceSession(o["id"].str(), o["courseId"].str(), o["title"].str(), o["mode"].str(), o["state"].str(), o["publisher"].str(), o["createdAt"].str(), o["expireAt"].str(), o["lateAfterSeconds"].int(), q)
    }

    private fun parseAttendanceRecord(o: JsonObject): AttendanceRecord = AttendanceRecord(
        o["id"].str(), o["sessionId"].str(), o["studentId"].str(), o["studentName"].str(),
        o["checkedInAt"].str(), o["status"].str(), o["method"].str(), o["answer"].strOrNull(),
        o["secondsAfterStart"].num().toLong(),
        o["distanceMeters"]?.num(), o["deviceId"].strOrNull(), o["riskScore"].int(),
        (o["riskReasons"] as? JsonArray).orEmpty().map { it.str() },
    )

    private fun parseAttendanceRisk(o: JsonObject): AttendanceRisk = AttendanceRisk(
        o["recordId"].str(), o["studentId"].str(), o["studentName"].str(), o["riskScore"].int(),
        (o["reasons"] as? JsonArray).orEmpty().map { it.str() }, o["checkedInAt"].str(),
    )

    private fun parseAttendanceRealtimeItem(o: JsonObject): AttendanceRealtimeItem = AttendanceRealtimeItem(
        o["studentName"].str(), o["secondsAfterStart"].num().toLong(), o["status"].str(),
    )

    // ── 解析：Submission / AiSuggestion ──

    private fun parseSubmission(o: JsonObject): SubmissionInfo {
        val history = (o["history"] as? JsonArray).orEmpty().map { h ->
            val ho = h as JsonObject
            SubmissionVersion(
                version = ho["version"].int(), content = ho["content"].str(),
                submittedAt = ho["submittedAt"].str(),
            )
        }
        val ai = (o["ai"] as? JsonObject)?.let { parseAiSuggestion(it) }
        return SubmissionInfo(
            id = o["id"].str(), assignmentId = o["assignmentId"].str(),
            studentId = o["studentId"].str(), studentName = o["studentName"].str(),
            content = o["content"].str(), version = o["version"].int(),
            submittedAt = o["submittedAt"].str(), status = o["status"].str(),
            score = o["score"]?.takeUnless { it == JsonNull }?.num(),
            comment = o["comment"].strOrNull(), reviewer = o["reviewer"].strOrNull(),
            reviewedAt = o["reviewedAt"].strOrNull(), history = history, ai = ai,
            answers = parseQuestionAnswers(o["answers"]),
            questionResults = parseQuestionResults(o["questionResults"]),
            attachments = (o["attachments"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.let(::parseAttachment) },
            durationSec = (o["durationSec"] as? JsonPrimitive)?.content?.toIntOrNull(),
        )
    }

    private fun parseAiSuggestion(o: JsonObject): AiSuggestion {
        val reasons = (o["reasons"] as? JsonArray).orEmpty().map { r ->
            val ro = r as JsonObject
            AiReason(ro["item"].str(), ro["detail"].str(), ro["score"].num())
        }
        return AiSuggestion(
            suggestedScore = o["suggestedScore"].num(), confidence = o["confidence"].num(),
            hitKnowledgePoints = (o["hitKnowledgePoints"] as? JsonArray).orEmpty().map { it.str() },
            missedKnowledgePoints = (o["missedKnowledgePoints"] as? JsonArray).orEmpty().map { it.str() },
            reasons = reasons, riskLevel = o["riskLevel"].str(), summary = o["summary"].str(),
            objectiveScore = o["objectiveScore"].num(), objectiveFullScore = o["objectiveFullScore"].num(),
            questionResults = parseQuestionResults(o["questionResults"]),
        )
    }

    /** 解析按题作答 [{questionId, value}]（文档 3.12）。 */
    private fun parseQuestionAnswers(o: JsonElement?): List<QuestionAnswer> {
        val arr = (o as? JsonArray) ?: return emptyList()
        return arr.mapNotNull { it as? JsonObject }.map { QuestionAnswer(it["questionId"].str(), it["value"].str()) }
    }

    /** 解析逐题判定结果（文档 3.12：correct 可 null；autoScore/fullScore；judgedBy）。 */
    private fun parseQuestionResults(o: JsonElement?): List<QuestionResult> {
        val arr = (o as? JsonArray) ?: return emptyList()
        return arr.mapNotNull { it as? JsonObject }.map { q ->
            val correct = q["correct"]
            QuestionResult(
                questionId = q["questionId"].str(), type = q["type"].str(), stem = q["stem"].str(),
                studentValue = q["studentValue"].str(), standardAnswer = q["standardAnswer"].str(),
                correct = if (correct == null || correct is JsonNull) null else (correct as? JsonPrimitive)?.content?.toBooleanStrictOrNull(),
                autoScore = q["autoScore"].num(),
                finalScore = q["finalScore"]?.takeUnless { it == JsonNull }?.num(),
                fullScore = q["fullScore"].num(), judgedBy = q["judgedBy"].str(),
            )
        }
    }
}
