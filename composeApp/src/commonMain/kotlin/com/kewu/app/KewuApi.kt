package com.kewu.app

import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import kotlinx.coroutines.CancellationException
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

data class UserInfo(val id: String, val username: String, val nickname: String?)
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
)
data class AiReason(val item: String, val detail: String, val score: Double)
data class AiSuggestion(
    val suggestedScore: Double, val confidence: Double,
    val hitKnowledgePoints: List<String>, val missedKnowledgePoints: List<String>,
    val reasons: List<AiReason>, val riskLevel: String, val summary: String,
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

// 8081 服务
data class RecordingUploadResult(
    val recordingId: String, val url: String, val score: Double, val storedAt: String,
)
data class SessionInfo(
    val sessionId: String, val classId: String, val startedAt: String, val state: String,
)
data class RecordingFuseItem(
    val recordingId: String, val classId: String, val sessionId: String, val deviceId: String,
    val format: String? = null, val sampleRate: Int? = null, val durationSec: Double? = null,
    val sizeBytes: Long? = null, val storedUrl: String? = null,
)
data class RecordingFuseResult(
    val sessionId: String, val primaryRecordingId: String,
    val mergedSources: List<String>, val strategy: String,
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
    val OBS_URL = "http://47.99.42.168:8081"
    private val client = platformHttpClient()

    var token: String? = kvGet("kewu_token")
    var username: String? = kvGet("kewu_username")
    var currentUser: UserMe? = null

    private fun authHeaders(): Map<String, String> =
        if (token != null) mapOf(HttpHeaders.Authorization to "Bearer $token") else emptyMap()

    /** 通用 GET（8080 壳）。返回原始 JsonElement；业务错误抛 [ApiException]。 */
    private suspend fun getJson(url: String, auth: Boolean = false): JsonElement {
        val resp = try {
            client.get(url) { if (auth) authHeaders().forEach { (k, v) -> header(k, v) } }
        } catch (e: ResponseException) {
            throw ApiException(e.response.bodyAsText().let { parseError(it) })
        }
        return json.parseToJsonElement(resp.bodyAsText())
    }

    /** 通用 POST（8080 壳）。[bodyObj] 为 Kotlin Map/List，转 JSON 字符串发送。 */
    private suspend fun postJson(url: String, bodyObj: Any?, auth: Boolean = false): JsonElement {
        val resp = try {
            client.post(url) {
                if (auth) authHeaders().forEach { (k, v) -> header(k, v) }
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(JsonElement.serializer(), toJsonElement(bodyObj)))
            }
        } catch (e: ResponseException) {
            throw ApiException(e.response.bodyAsText().let { parseError(it) })
        }
        return json.parseToJsonElement(resp.bodyAsText())
    }

    /** 从错误响应体里尽量抽出 message / error 字段。 */
    private fun parseError(text: String): String {
        return try {
            val el = json.parseToJsonElement(text)
            (el as? JsonObject)?.get("message")?.jsonPrimitive?.content
                ?: (el as? JsonObject)?.get("error")?.jsonPrimitive?.content
                ?: text
        } catch (_: Exception) { text }
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

    /** 登录：成功返回 null，失败返回适合直接展示的 message。 */
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
        null
    } catch (e: CancellationException) { throw e }
    catch (e: ApiException) { officialAuthMessage(e.message, isRegister = false) }
    catch (e: Exception) { networkErrorMessage(e) }

    /** 注册：成功返回 null，失败返回 message。注册后不会返回 token，需再调 login。 */
    suspend fun register(username: String, password: String, nickname: String): String? = try {
        val el = postJson(
            "$BASE_URL/api/user/register",
            mapOf("username" to username, "password" to password, "nickname" to nickname),
        )
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
        return when {
            "用户名或密码不对" in m || "密码错误" in m || "密码不对" in m || "密码不正确" in m ->
                "学号或密码不正确，请重新输入"
            "已经被注册" in m || "已存在" in m || "重复" in m ->
                "该学号已注册，请直接登录"
            "用户名不能为空" in m || "用户名为空" in m || "账号为空" in m ->
                "请输入学号"
            "密码至少" in m || "密码长度" in m ->
                "密码长度至少 6 位，请重新设置"
            "昵称" in m && "空" in m ->
                "请输入姓名"
            else -> m
        }
    }

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

    /** 绑定课程（需 token）。 */
    suspend fun bindCourse(courseId: String): String? = try {
        val el = postJson("$BASE_URL/api/user/bind-course", mapOf("courseId" to courseId), auth = true)
        el.asDataOrThrow()
        null
    } catch (e: ApiException) { e.message }
    catch (_: Exception) { "网络异常，请稍后重试" }

    /** 我的资料（需 token），填充 [currentUser]；失败返回 message。 */
    suspend fun me(): String? = try {
        val el = getJson("$BASE_URL/api/user/me", auth = true)
        val data = el.asDataOrThrow() as? JsonObject
            ?: throw ApiException("服务器响应异常，请稍后重试")
        val u = data["user"] as? JsonObject
        val courses = (data["courses"] as? JsonArray)?.mapNotNull { c -> c as? JsonObject }.orEmpty()
            .map { CourseInfo(it["id"].str(), it["name"].str(), it["description"].str(), it["teacher"].str()) }
        currentUser = UserMe(
            user = u?.let { UserInfo(it["id"].str(), it["username"].str(), it["nickname"].strOrNull()) },
            courses = courses,
        )
        null
    } catch (e: ApiException) { e.message }
    catch (_: Exception) { "网络异常，请稍后重试" }

    // ── 课程模块 ──

    /** 课程列表（无需 token）。失败返回 null（调用方降级 mock）。 */
    suspend fun getCourses(): List<CourseInfo>? = try {
        val el = getJson("$BASE_URL/api/courses")
        el.dataArrayOrEmpty("data").mapNotNull { (it as? JsonObject)?.let { o ->
            CourseInfo(o["id"].str(), o["name"].str(), o["description"].str(), o["teacher"].str())
        } }
    } catch (_: Exception) { null }

    /** 课程详情（无需 token）。 */
    suspend fun getCourse(courseId: String): CourseInfo? = try {
        val el = getJson("$BASE_URL/api/courses/${pathSegment(courseId)}")
        val data = el.dataObjectOrNull("data") ?: return null
        CourseInfo(data["id"].str(), data["name"].str(), data["description"].str(), data["teacher"].str())
    } catch (_: Exception) { null }

    /** 课堂纪要列表（无需 token）。失败返回 null（调用方降级 mock）。 */
    suspend fun getNotes(courseId: String): List<ClassNoteInfo>? = try {
        val el = getJson("$BASE_URL/api/courses/${pathSegment(courseId)}/notes")
        el.dataArrayOrEmpty("data").map { it as JsonObject }.map {
            ClassNoteInfo(
                it["id"].str(), it["courseId"].str(), it["title"].str(), it["content"].str(),
                it["author"].str(), it["createdAt"].str(),
            )
        }
    } catch (_: Exception) { null }

    /** 单条纪要（无需 token）。 */
    suspend fun getNote(courseId: String, noteId: String): ClassNoteInfo? = try {
        val el = getJson("$BASE_URL/api/courses/${pathSegment(courseId)}/notes/${pathSegment(noteId)}")
        val data = el.dataObjectOrNull("data") ?: return null
        ClassNoteInfo(
            data["id"].str(), data["courseId"].str(), data["title"].str(), data["content"].str(),
            data["author"].str(), data["createdAt"].str(),
        )
    } catch (_: Exception) { null }

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
            )
        } }
    } catch (_: Exception) { null }

    /** 作业详情（无需 token）。 */
    suspend fun getAssignment(id: String): AssignmentInfo? = try {
        val el = getJson("$BASE_URL/api/assignments/${pathSegment(id)}")
        val data = el.dataObjectOrNull("data") ?: return null
        AssignmentInfo(
            id = data["id"].str(), courseId = data["courseId"].str(), title = data["title"].str(),
            description = data["description"].str(), deadline = data["deadline"].str(),
            totalScore = data["totalScore"].int(),
            knowledgePoints = (data["knowledgePoints"] as? JsonArray).orEmpty().map { it.str() },
            publisher = data["publisher"].str(), createdAt = data["createdAt"].str(),
        )
    } catch (_: Exception) { null }

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
    } catch (_: Exception) { null }

    /** 提交作业（无需 token；同一个学生会自动版本+1）。[studentId] 当前用本地 username。 */
    suspend fun submitAssignment(
        id: String, studentId: String, studentName: String, content: String,
    ): String? = try {
        val el = postJson(
            "$BASE_URL/api/assignments/${pathSegment(id)}/submissions",
            mapOf("studentId" to studentId, "studentName" to studentName, "content" to content),
        )
        el.asDataOrThrow()
        null
    } catch (e: ApiException) { e.message }
    catch (_: Exception) { "网络异常，请稍后重试" }

    /** 我的提交（版本轨迹）。失败返回 null。 */
    suspend fun getMySubmissions(id: String, studentId: String): SubmissionInfo? = try {
        val el = getJson(queryUrl("$BASE_URL/api/assignments/${pathSegment(id)}/my", "studentId", studentId))
        parseSubmission(el.dataObjectOrNull("data") ?: return null)
    } catch (_: Exception) { null }

    /** 全部提交（老师看）。失败返回 null。 */
    suspend fun getSubmissions(id: String): List<SubmissionInfo>? = try {
        val el = getJson("$BASE_URL/api/assignments/${pathSegment(id)}/submissions")
        el.dataArrayOrEmpty("data").map { parseSubmission(it as JsonObject) }
    } catch (_: Exception) { null }

    /** 提交详情（含历史版本）。失败返回 null。 */
    suspend fun getSubmission(id: String): SubmissionInfo? = try {
        val el = getJson("$BASE_URL/api/submissions/${pathSegment(id)}")
        parseSubmission(el.dataObjectOrNull("data") ?: return null)
    } catch (_: Exception) { null }

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
    } catch (_: Exception) { null }

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
    } catch (_: Exception) { null }

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
    } catch (_: Exception) { null }

    /** 批阅打分 / 打回（无需 token，当前用参数区分身份）。 */
    suspend fun reviewSubmission(
        id: String, score: Double, comment: String, reviewer: String, action: String,
    ): String? = try {
        val el = postJson(
            "$BASE_URL/api/submissions/${pathSegment(id)}/review",
            mapOf("score" to score, "comment" to comment, "reviewer" to reviewer, "action" to action),
        )
        el.asDataOrThrow()
        null
    } catch (e: ApiException) { e.message }
    catch (_: Exception) { "网络异常，请稍后重试" }

    /** 8081 健康检查。成功返回服务原文，失败返回可展示错误。 */
    suspend fun obsHealth(): String? = try {
        val resp = client.get("$OBS_URL/health")
        resp.bodyAsText().trim().ifEmpty { "ok" }
    } catch (e: ResponseException) {
        e.response.bodyAsText().let { parseError(it) }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { networkErrorMessage(e) }

    /** 8080 健康检查。文档规定该接口返回纯文本，不套业务壳。 */
    suspend fun health(): String? = try {
        val resp = client.get("$BASE_URL/api/health")
        resp.bodyAsText().trim().ifEmpty { "ok" }
    } catch (e: ResponseException) {
        e.response.bodyAsText().let { parseError(it) }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { networkErrorMessage(e) }

    /** 发布作业（老师入口；服务端仍应自行校验权限）。 */
    suspend fun publishAssignment(
        courseId: String, title: String, description: String = "", deadline: String = "",
        totalScore: Int = 100, knowledgePoints: List<String> = emptyList(), publisher: String = "",
    ): AssignmentInfo? = try {
        val el = postJson(
            "$BASE_URL/api/assignments",
            mapOf(
                "courseId" to courseId, "title" to title, "description" to description,
                "deadline" to deadline, "totalScore" to totalScore,
                "knowledgePoints" to knowledgePoints, "publisher" to publisher,
            ), auth = true,
        )
        val data = el.asDataOrThrow() as? JsonObject ?: return null
        AssignmentInfo(
            id = data["id"].str(), courseId = data["courseId"].str(), title = data["title"].str(),
            description = data["description"].str(), deadline = data["deadline"].str(),
            totalScore = data["totalScore"].int(),
            knowledgePoints = (data["knowledgePoints"] as? JsonArray).orEmpty().map { it.str() },
            publisher = data["publisher"].str(), createdAt = data["createdAt"].str(),
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
                    "storedUrl" to r.storedUrl,
                )
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
        )
    } catch (e: ResponseException) { throw ApiException(e.response.bodyAsText().let { parseError(it) }) }
    catch (e: CancellationException) { throw e }
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
    } catch (_: Exception) { null }

    suspend fun getAttendance(id: String): AttendanceSession? = try {
        val data = getJson("$BASE_URL/api/attendance/${pathSegment(id)}").asDataOrThrow()
        val o = data as? JsonObject ?: return null
        // 后端该接口现在是 {session:{...}, stats:{...}} 包一层；兼容旧的扁平 session 对象
        val s = (o["session"] as? JsonObject) ?: o
        parseAttendanceSession(s)
    } catch (_: Exception) { null }

    suspend fun getAttendanceCode(id: String): AttendanceCode? = try {
        val o = getJson("$BASE_URL/api/attendance/${pathSegment(id)}/code").asDataOrThrow() as? JsonObject ?: return null
        AttendanceCode(o["sessionId"].str(), o["code"].str(), o["qrContent"].str(), o["windowSeconds"].int(), o["remainingSeconds"].int())
    } catch (_: Exception) { null }

    suspend fun attendanceCheckIn(id: String, studentId: String, studentName: String = studentId, code: String? = null, answer: String? = null, latitude: Double? = null, longitude: Double? = null): String? = try {
        val body = mutableMapOf<String, Any?>("studentId" to studentId, "studentName" to studentName)
        if (code != null) body["code"] = code
        if (answer != null) body["answer"] = answer
        if (latitude != null) body["latitude"] = latitude
        if (longitude != null) body["longitude"] = longitude
        postJson("$BASE_URL/api/attendance/${pathSegment(id)}/check-in", body, auth = true).asDataOrThrow()
        null
    } catch (e: CancellationException) { throw e } catch (e: ApiException) { e.message } catch (_: Exception) { "网络异常，请稍后重试" }

    suspend fun getAttendanceRecords(id: String): List<AttendanceRecord>? = try {
        val data = getJson("$BASE_URL/api/attendance/${pathSegment(id)}/records").asDataOrThrow()
        (data as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseAttendanceRecord) }
    } catch (_: Exception) { null }

    suspend fun getMyAttendance(id: String, studentId: String): AttendanceRecord? = try {
        val data = getJson(queryUrl("$BASE_URL/api/attendance/${pathSegment(id)}/my", "studentId", studentId)).asDataOrThrow()
        (data as? JsonObject)?.let(::parseAttendanceRecord)
    } catch (_: Exception) { null }

    suspend fun closeAttendance(id: String): String? = try {
        postJson("$BASE_URL/api/attendance/${pathSegment(id)}/close", emptyMap<String, Any?>(), auth = true).asDataOrThrow()
        null
    } catch (e: CancellationException) { throw e } catch (e: ApiException) { e.message } catch (_: Exception) { "网络异常，请稍后重试" }

    suspend fun getAttendanceStats(id: String): AttendanceStats? = try {
        val o = getJson("$BASE_URL/api/attendance/${pathSegment(id)}/stats").asDataOrThrow() as? JsonObject ?: return null
        AttendanceStats(o["expected"].int(), o["checked"].int(), o["late"].int(), o["absent"].int(), o["checkRate"].num(), o["avgSeconds"].num())
    } catch (_: Exception) { null }

    suspend fun getAttendanceRisk(id: String): List<AttendanceRisk>? = try {
        val data = getJson("$BASE_URL/api/attendance/${pathSegment(id)}/risk").asDataOrThrow()
        (data as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseAttendanceRisk) }
    } catch (_: Exception) { null }

    suspend fun getAttendanceRealtime(id: String): AttendanceRealtime? = try {
        val o = getJson("$BASE_URL/api/attendance/${pathSegment(id)}/realtime").asDataOrThrow() as? JsonObject ?: return null
        AttendanceRealtime(o["expected"].int(), o["checked"].int(), o["checkRate"].num(), o["elapsedSeconds"].int().toLong(), (o["latest"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseAttendanceRealtimeItem) })
    } catch (_: Exception) { null }

    /** 上传录音（multipart）。[bytes] 为音频字节，[filename] 含扩展名。失败返回 null.
     *  ★ Ktor 2.3.12 已移除 2-arg `submitFormWithBinaryData(url, block)`，必须改用
     *    [MultiPartFormDataContent] + `post { setBody(...) }`。
     *  ★ 不要用裸 `PartData.FormItem/BinaryItem` 手搓 parts——Content-Disposition 必须带
     *    `form-data; ` 前缀，漏了服务端收不到字段（实测 8081 返回 400「缺少 audio 文件或 classId」）。
     *    用 `formData { }` 构建器它会自动补全。也不要手动 `contentType(MultiPart.FormData)`，
     *    会丢掉 boundary，让 [MultiPartFormDataContent] 自己设。 */
    suspend fun uploadRecording(
        bytes: ByteArray, filename: String, classId: String, sessionId: String?, durationSec: Double?,
    ): RecordingUploadResult? = try {
        val content = MultiPartFormDataContent(formData {
            append("classId", classId)
            append("deviceId", platformDeviceId())
            append("sampleRate", "44100")
            if (sessionId != null) append("sessionId", sessionId)
            if (durationSec != null) append("durationSec", durationSec.toString())
            append("audio", bytes, Headers.build {
                append(HttpHeaders.ContentType, "audio/wav")
                append(HttpHeaders.ContentDisposition, "filename=\"${safeFilename(filename)}\"")
            })
        })
        val resp = try {
            client.post("$OBS_URL/api/recordings/upload") {
                setBody(content)
            }
        } catch (e: ResponseException) {
            throw ApiException(e.response.bodyAsText().let { parseError(it) })
        }
        val el = json.parseToJsonElement(resp.bodyAsText()).asRawOrThrow()
        RecordingUploadResult(
            recordingId = el["recordingId"].str(), url = el["url"].str(),
            score = el["score"].num(), storedAt = el["storedAt"].str(),
        )
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    /** 签到（POST 裸 JSON）。[status] 默认 "present"。失败返回 message 或 null。 */
    suspend fun checkin(classId: String, studentId: String, status: String = "present"): String? = try {
        val resp = client.post("$OBS_URL/api/checkin") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonElement.serializer(), toJsonElement(
                mapOf("classId" to classId, "studentId" to studentId, "status" to status),
            )))
        }
        val el = json.parseToJsonElement(resp.bodyAsText()).asRawOrThrow()
        if (el["ok"].bool()) null else "签到失败"
    } catch (e: ApiException) { e.message }
    catch (_: Exception) { "网络异常，请稍后重试" }

    /** 查询签到状态（返回 studentId->status 的 map）。失败返回 null。 */
    suspend fun getCheckins(classId: String): Map<String, String>? = try {
        val resp = client.get("$OBS_URL/api/checkin/${pathSegment(classId)}")
        val el = json.parseToJsonElement(resp.bodyAsText()).asRawOrThrow()
        val checks = el["checkins"] as? JsonObject ?: return emptyMap()
        checks.mapValues { it.value.str() }
    } catch (_: Exception) { null }

    /** 缓存课堂纪要（POST 裸 JSON）。失败返回 message 或 null。 */
    suspend fun postNote(classId: String, content: String): String? = try {
        val resp = client.post("$OBS_URL/api/notes") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonElement.serializer(), toJsonElement(
                mapOf("classId" to classId, "content" to content),
            )))
        }
        val el = json.parseToJsonElement(resp.bodyAsText()).asRawOrThrow()
        if (el["ok"].bool()) null else "保存失败"
    } catch (e: ApiException) { e.message }
    catch (_: Exception) { "网络异常，请稍后重试" }

    /** 读课堂纪要（GET，无纪要时 404 返回 null 不报错）。 */
    suspend fun getNoteCache(classId: String): String? = try {
        val resp = client.get("$OBS_URL/api/notes/${pathSegment(classId)}")
        val el = json.parseToJsonElement(resp.bodyAsText()).asRawOrThrow()
        el["content"].strOrNull()
    } catch (_: Exception) { null }

    /** 创建课堂会话（POST 裸 JSON）。失败返回 null。 */
    suspend fun createSession(sessionId: String, classId: String): SessionInfo? = try {
        val resp = client.post("$OBS_URL/api/sessions") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonElement.serializer(), toJsonElement(
                mapOf("sessionId" to sessionId, "classId" to classId, "state" to "active"),
            )))
        }
        val el = json.parseToJsonElement(resp.bodyAsText()).asRawOrThrow()
        SessionInfo(
            sessionId = el["sessionId"].str(), classId = el["classId"].str(),
            startedAt = el["startedAt"].str(), state = el["state"].str(),
        )
    } catch (_: Exception) { null }

    /** 查课堂会话（GET，无会话 404 返回 null）。 */
    suspend fun getSession(sessionId: String): SessionInfo? = try {
        val resp = client.get("$OBS_URL/api/sessions/${pathSegment(sessionId)}")
        val el = json.parseToJsonElement(resp.bodyAsText()).asRawOrThrow()
        SessionInfo(
            sessionId = el["sessionId"].str(), classId = el["classId"].str(),
            startedAt = el["startedAt"].str(), state = el["state"].str(),
        )
    } catch (_: Exception) { null }

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
        )
    }
}
