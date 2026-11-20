package com.kewu.admin

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
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
import java.net.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AdminApiException(message: String) : Exception(message)

class AdminApi(
    baseUrl: String = System.getProperty("KEWU_BASE_URL")
        ?: System.getenv("KEWU_BASE_URL")
        ?: "http://8.136.151.139:8080",
) {
    /** 实际使用的后端地址。总控端跑在服务器本机时，公网 IP 无 NAT 回环不可达，
     *  需由 [resolveBaseUrl] 探测后切到 127.0.0.1。 */
    var baseUrl: String = baseUrl
        private set

    /** 一键部署选择的动态端口（0 = 未指定，走默认 8080 候选）。
     *  部署完成或自检命中后由 [useDeployedPort] 注入，登录探测时优先命中该端口。 */
    private var deployedPort: Int = 0

    /** 由部署/自检流程告知总控端后端实际监听端口，并立即把 baseUrl 切到本机地址。 */
    fun useDeployedPort(port: Int) {
        if (port in 1..65535) {
            deployedPort = port
            baseUrl = "http://127.0.0.1:$port"
        }
    }

    /** 当前后端端口：优先部署/自检注入值，否则从生效的 baseUrl 解析。供首页显著展示（§81）。 */
    fun currentPort(): Int {
        if (deployedPort in 1..65535) return deployedPort
        return runCatching {
            val afterScheme = baseUrl.substringAfter("://", "")
            val hostPort = afterScheme.substringBefore("/")
            hostPort.substringAfterLast(':').toIntOrNull()?.takeIf { it in 1..65535 }
                ?: if (baseUrl.startsWith("https", ignoreCase = true)) 443 else 80
        }.getOrDefault(8080)
    }
    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        // 强制直连：服务器上装有 CCProxy 等系统代理时，OkHttp 默认会走系统代理，
        // 连 127.0.0.1:8080 / 公网 8080 都可能被代理劫持而报「无法连接服务器」。
        engine { proxy = Proxy.NO_PROXY }
        install(HttpTimeout) {
            // 2026-11-17 §82：服务器可能是 1 核 2G 的小机器，冷启动首次登录（BCrypt + 连接池预热）
            // 实测要 29.5s，原来的 30s 正好卡在超时线上导致「连接不上」。放宽到 60s。
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
    }

    private var bearerToken: String? = null

    /** 登录前探活：优先本机 127.0.0.1（服务器本机运行时公网 IP 回环不通），其次当前 baseUrl。
     *  每个候选的结果（含失败原因）都会写入本地诊断日志；全部不可达时返回原地址。 */
    suspend fun resolveBaseUrl(): String {
        // 2026-11-16：本机优先，云端兜底。
        // 总控端常驻服务器本机时 127.0.0.1 秒通；若一上来就拉 Gitee（服务器访问常被拦，8s 超时）
        // 会白白拖慢每次登录。故把云端拉取放到本地候选全部失败后才做。
        val portCandidates = linkedSetOf<Int>().apply {
            if (deployedPort in 1..65535) add(deployedPort)
            add(8080)
        }
        val localCandidates = (portCandidates.flatMap { listOf("http://127.0.0.1:$it", "http://localhost:$it") } + baseUrl).distinct()
        val results = mutableListOf<Pair<String, String>>()
        for (candidate in localCandidates) {
            val error = probeHealthError(candidate)
            if (error == null) {
                baseUrl = candidate
                results += candidate to "OK"
                AdminDiagnostics.record("PROBE", results.joinToString("\n") { (c, r) -> "  $c -> $r" } + "\n  selected: $candidate")
                return candidate
            }
            results += candidate to "FAIL: $error"
        }
        // 本地都不通（总控端跑在别的机器上）→ 最后才拉云端 server_url 兜底
        val cloud = ServerConfig.fetch()
        if (cloud != null && !localCandidates.contains(cloud)) {
            val error = probeHealthError(cloud)
            if (error == null) {
                baseUrl = cloud
                results += cloud to "OK (cloud)"
                AdminDiagnostics.record("PROBE", results.joinToString("\n") { (c, r) -> "  $c -> $r" } + "\n  selected: $cloud (cloud)")
                return cloud
            }
            results += cloud to "FAIL (cloud): $error"
        }
        AdminDiagnostics.record("PROBE", results.joinToString("\n") { (c, r) -> "  $c -> $r" } + "\n  selected: none (keep $baseUrl)")
        return baseUrl
    }

    /** 返回 null 表示健康；否则返回简短失败原因（写入日志用）。 */
    private suspend fun probeHealthError(url: String): String? = try {
        val response = client.request("$url/api/health") { method = HttpMethod.Get }
        if (response.status.value in 200..299) null else "HTTP ${response.status.value}"
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AdminDiagnostics.recordError("PROBE_DETAIL", e, mapOf("url" to "$url/api/health"))
        e.message ?: e.javaClass.simpleName
    }

    private val obsBucketUrl: String = System.getProperty("KEWU_OBS_BUCKET_URL")
        ?: System.getenv("KEWU_OBS_BUCKET_URL")
        ?: "https://kewu2.obs.cn-north-4.myhuaweicloud.com"

    suspend fun login(username: String, password: String): AdminSession {
        // 登录是「冷启动最慢」的请求（BCrypt 校验 + 数据库连接池首次建连 + JIT 预热）。
        // 低配服务器首次可能接近 30s，这里给 120s 超时，并允许超时自动重试一次（第二次已预热会很快）。
        val data = requestWithRetry(
            path = "/api/user/login",
            body = buildJsonObject {
                put("username", username)
                put("password", password)
            },
            timeoutMs = 120_000,
            attempts = 2,
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

    // ───── 密钥与服务配置（全部密钥只存在云端服务器，总控端只能看到打码值） ─────

    /** 修改当前管理员密码：已登录即凭 Bearer Token 鉴权，无需旧密码；成功后本地凭证缓存需刷新。 */
    suspend fun changePassword(newPassword: String) {
        request(
            path = "/api/admin/change-password",
            method = HttpMethod.Post,
            body = buildJsonObject {
                put("newPassword", newPassword)
            },
        )
    }

    suspend fun configItems(): List<ConfigItem> = parseConfigItems(getData("/api/admin/config"))

    /** 只提交改动过的键；值为 "" 表示删除云端覆盖值（回落到服务器环境变量 / 默认值）。返回更新后的完整列表。 */
    suspend fun saveConfig(changes: Map<String, String>): List<ConfigItem> = parseConfigItems(
        request(
            path = "/api/admin/config",
            method = HttpMethod.Put,
            body = buildJsonObject {
                put("items", buildJsonObject { changes.forEach { (key, value) -> put(key, value) } })
            },
        ),
    )

    suspend fun testConfig(group: String): ConfigTestResult = parseConfigTestResult(
        request(
            path = "/api/admin/config/test",
            method = HttpMethod.Post,
            body = buildJsonObject { put("group", group) },
        ),
    )

    private suspend fun getData(path: String, params: Map<String, String?> = emptyMap()) =
        request(path, HttpMethod.Get, params = params)

    private suspend fun request(
        path: String,
        method: HttpMethod,
        body: JsonObject? = null,
        params: Map<String, String?> = emptyMap(),
        authenticate: Boolean = true,
        timeoutMs: Long? = null,
    ) = AdminJson.parseToJsonElement(
        requestText(path, method, body, params, authenticate, envelope = true, timeoutMs = timeoutMs),
    ).let { root ->
        val envelope = root as? JsonObject ?: throw AdminApiException("服务器响应格式异常")
        val code = envelope.long("code", -1)
        if (code != 0L) throw AdminApiException(envelope.str("message", "操作失败，请稍后重试"))
        envelope["data"]
    }

    /**
     * 带超时重试的 POST（仅用于登录等冷启动敏感请求）。
     * 服务器低配时首次请求可能超时，但服务端其实仍在处理并最终成功（§82 实测 29.5s 返回 200）。
     * 因此超时后重试一次是安全的：命中已预热的连接与 JIT，通常几秒内返回。
     */
    private suspend fun requestWithRetry(
        path: String,
        body: JsonObject,
        timeoutMs: Long,
        attempts: Int,
    ): kotlinx.serialization.json.JsonElement? {
        var lastError: Exception? = null
        repeat(attempts) { index ->
            try {
                return request(
                    path = path,
                    method = HttpMethod.Post,
                    body = body,
                    authenticate = false,
                    timeoutMs = timeoutMs,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: AdminApiException) {
                // 业务错误（密码错等）不重试，直接抛
                if (!isTimeoutLike(e)) throw e
                lastError = e
            } catch (e: Exception) {
                lastError = e
            }
            if (index < attempts - 1) kotlinx.coroutines.delay(1200)
        }
        throw lastError ?: AdminApiException("登录失败，请稍后重试")
    }

    private fun isTimeoutLike(e: Throwable): Boolean {
        val msg = e.message.orEmpty()
        return msg.contains("超时", true) || msg.contains("timeout", true) || msg.contains("无法连接", true)
    }

    private suspend fun requestText(
        path: String,
        method: HttpMethod,
        body: JsonObject? = null,
        params: Map<String, String?> = emptyMap(),
        authenticate: Boolean = true,
        envelope: Boolean,
        timeoutMs: Long? = null,
    ): String {
        try {
            val response = client.request("$baseUrl$path") {
                this.method = method
                params.forEach { (key, value) -> if (!value.isNullOrBlank()) parameter(key, value) }
                if (authenticate) bearerToken?.let { header("Authorization", "Bearer $it") }
                if (body != null) setBody(TextContent(body.toString(), ContentType.Application.Json))
                if (timeoutMs != null) {
                    timeout {
                        requestTimeoutMillis = timeoutMs
                        socketTimeoutMillis = timeoutMs
                    }
                }
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
            AdminDiagnostics.recordError(
                "REQUEST",
                e,
                mapOf(
                    "url" to "$baseUrl$path",
                    "method" to method.value,
                    "baseUrl" to baseUrl,
                    "log" to AdminDiagnostics.logFile.absolutePath,
                ),
            )
            val isTimeout = e is io.ktor.client.network.sockets.SocketTimeoutException ||
                e is io.ktor.client.plugins.HttpRequestTimeoutException ||
                e.message?.contains("timeout", true) == true
            throw AdminApiException(
                if (isTimeout) {
                    "服务器响应超时（当前后端地址 $baseUrl）。\n低配服务器首次请求较慢，已自动放宽等待时间，请再点一次登录重试。\n详情见日志：${AdminDiagnostics.logFile.absolutePath}"
                } else {
                    "暂时无法连接服务器（当前后端地址 $baseUrl）。\n请确认后端已启动；详情见日志：${AdminDiagnostics.logFile.absolutePath}"
                },
            )
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
