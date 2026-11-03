package com.kewu.admin

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLPath
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AdminApiException(message: String) : Exception(message)

class AdminApi(
    private val baseUrl: String = System.getProperty("KEWU_BASE_URL")
        ?: System.getenv("KEWU_BASE_URL")
        ?: "http://47.99.42.168:8080",
) {
    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    }

    private var bearerToken: String? = null

    private val obsBucketUrl: String = System.getProperty("KEWU_OBS_BUCKET_URL")
        ?: System.getenv("KEWU_OBS_BUCKET_URL")
        ?: "https://kewu2.obs.cn-north-4.myhuaweicloud.com"

    suspend fun login(username: String, password: String): AdminSession {
        val data = request(
            path = "/api/user/login",
            method = HttpMethod.Post,
            body = buildJsonObject {
                put("username", username)
                put("password", password)
            },
            authenticate = false,
        ).objOrThrow("登录响应缺少数据")
        val role = data.str("role", "").ifBlank { data.obj("user").str("role", "") }
        if (!role.equals("ADMIN", ignoreCase = true)) {
            throw AdminApiException("该账号不是管理员，请使用 ADMIN 账号登录")
        }
        val token = data.str("token", "")
        if (token.isBlank()) throw AdminApiException("登录响应缺少 Token，请稍后重试")
        bearerToken = token
        val user = data.obj("user")
        return AdminSession(
            token = token,
            username = user.str("username", username),
            nickname = user.str("nickname", username),
        )
    }

    fun logout() {
        bearerToken = null
    }

    suspend fun overview(): JsonObject = getData("/api/admin/overview").objOrThrow("概览响应格式异常")
    suspend fun health(): JsonObject = getData("/api/admin/system/health").objOrThrow("健康响应格式异常")

    /**
     * 直接分页读取 OBS 桶对象，补齐后端 recordings 表尚未落库的历史录音。
     * 当前桶策略允许匿名列举 recordings/ 前缀；若日后收紧，需要后端提供签名列表接口。
     */
    suspend fun obsRecordings(prefix: String = "recordings/"): List<JsonObject> {
        val all = mutableListOf<JsonObject>()
        var marker: String? = null
        var guard = 0
        while (guard++ < 20) {
            val response = client.request(obsBucketUrl) {
                method = HttpMethod.Get
                parameter("prefix", prefix)
                parameter("max-keys", "1000")
                if (!marker.isNullOrBlank()) parameter("marker", marker)
            }
            val text = response.bodyAsText()
            if (response.status.value >= 400) {
                throw AdminApiException("OBS 录音列表读取失败（HTTP ${response.status.value}）")
            }
            val page = parseObsListing(text, obsBucketUrl)
            all += page.items
            if (!page.truncated || page.nextMarker.isBlank()) break
            marker = page.nextMarker
        }
        return all
    }

    suspend fun page(path: String, params: Map<String, String?> = emptyMap()): PageResult {
        val data = getData(path, params).objOrThrow("分页响应格式异常")
        return PageResult(
            items = data.array("items"),
            total = data.long("total").toInt(),
            page = data.long("page", 1).toInt(),
            size = data.long("size", 20).toInt(),
        )
    }

    suspend fun list(path: String, params: Map<String, String?> = emptyMap()): List<JsonObject> =
        getData(path, params).asObjectList()

    suspend fun detail(path: String): JsonObject = getData(path).objOrThrow("详情响应格式异常")

    suspend fun create(path: String, body: JsonObject): JsonObject =
        request(path, HttpMethod.Post, body).let { it as? JsonObject ?: buildJsonObject { } }

    suspend fun update(path: String, body: JsonObject): JsonObject =
        request(path, HttpMethod.Put, body).let { it as? JsonObject ?: buildJsonObject { } }

    suspend fun action(path: String, body: JsonObject = buildJsonObject { }): JsonObject =
        request(path, HttpMethod.Post, body).let { it as? JsonObject ?: buildJsonObject { } }

    suspend fun remove(path: String) {
        request(path, HttpMethod.Delete)
    }

    suspend fun exportAttendance(id: String): String = requestText(
        path = "/api/admin/attendance/${id.encodeURLPath()}/export",
        method = HttpMethod.Get,
        envelope = false,
    )

    private suspend fun getData(path: String, params: Map<String, String?> = emptyMap()) =
        request(path, HttpMethod.Get, params = params)

    private suspend fun request(
        path: String,
        method: HttpMethod,
        body: JsonObject? = null,
        params: Map<String, String?> = emptyMap(),
        authenticate: Boolean = true,
    ) = AdminJson.parseToJsonElement(
        requestText(path, method, body, params, authenticate, envelope = true),
    ).let { root ->
        val envelope = root as? JsonObject ?: throw AdminApiException("服务器响应格式异常")
        val code = envelope.long("code", -1)
        if (code != 0L) throw AdminApiException(envelope.str("message", "操作失败，请稍后重试"))
        envelope["data"]
    }

    private suspend fun requestText(
        path: String,
        method: HttpMethod,
        body: JsonObject? = null,
        params: Map<String, String?> = emptyMap(),
        authenticate: Boolean = true,
        envelope: Boolean,
    ): String {
        try {
            val response = client.request("$baseUrl$path") {
                this.method = method
                params.forEach { (key, value) -> if (!value.isNullOrBlank()) parameter(key, value) }
                if (authenticate) bearerToken?.let { header("Authorization", "Bearer $it") }
                if (body != null) setBody(TextContent(body.toString(), ContentType.Application.Json))
            }
            val text = response.bodyAsText()
            if (!envelope && response.status.value >= 400) {
                val message = runCatching {
                    (AdminJson.parseToJsonElement(text) as? JsonObject)?.str("message", text) ?: text
                }.getOrElse { text }
                throw AdminApiException(message.ifBlank { "导出失败，请稍后重试" })
            }
            return text
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdminApiException) {
            throw e
        } catch (e: Exception) {
            throw AdminApiException("暂时无法连接服务器，请检查网络后重试")
        }
    }

    fun close() = client.close()
}

private fun kotlinx.serialization.json.JsonElement?.objOrThrow(message: String): JsonObject =
    this as? JsonObject ?: throw AdminApiException(message)

fun jsonBody(vararg values: Pair<String, String?>): JsonObject = buildJsonObject {
    values.forEach { (key, value) -> if (value != null) put(key, value) }
}

fun jsonBodyNumber(vararg values: Pair<String, Number?>): JsonObject = buildJsonObject {
    values.forEach { (key, value) -> if (value != null) put(key, JsonPrimitive(value)) }
}
