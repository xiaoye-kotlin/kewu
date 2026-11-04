package com.kewu.modules.voicechat

import com.kewu.infra.HuaweiSigner
import com.kewu.infra.SecretStore
import com.kewu.recserver.config.loadSisConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * SIS 鉴权凭证管理：IAM Token 缓存 24 小时，避免每来一条语音就去 IAM 换一次 token。
 *
 * 三种模式自动选（从上往下，能跑通就用哪个）：
 *   1. SIS_IAM_TOKEN 直接给了 Token（临时联调用）→ 缓存 24h；
 *   2. 配了 SIS_IAM_USER / SIS_IAM_PASSWORD / SIS_IAM_DOMAIN → 调 IAM 换一次 Token，
 *      取响应头 X-Subject-Token 缓存 24h，提前 10 分钟自动刷新；
 *   3. 都没配 → 回落到现有工程一直在用的 AK/SK 本地签名（HuaweiSigner）。
 *      签名是纯本地计算、不联网，本来就没有"重复鉴权"的网络开销，这里同样把凭证缓存 24h。
 *
 * 并发安全：Mutex 双检，同一时刻只有一个协程去换 Token，其余等待复用，不会打爆 IAM。
 * 配置热更：凭证带配置指纹，总控端改了 AK/SK 或 Token 立即失效重取。
 * 失效重试：调 SIS 拿到 401/403 时业务层调 invalidate()，下一次请求会重新换 Token。
 */
class SisTokenManager {

    private val log = LoggerFactory.getLogger(SisTokenManager::class.java)
    private val mutex = Mutex()

    @Volatile
    private var cached: Credential? = null

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        // 跟 MaasAiService 一样强制直连，别继承系统里残留的代理设置
        .proxy(java.net.ProxySelector.of(null))
        .build()

    private enum class Mode { IAM_TOKEN, AK_SK }

    private data class Credential(
        val mode: Mode,
        val token: String = "",
        val ak: String = "",
        val sk: String = "",
        val expireAt: Long = 0L,
        val fingerprint: String = "",
    )

    companion object {
        // IAM Token 官方有效期 24 小时；提前 10 分钟刷新，别卡在刚好过期的那一秒
        private const val TTL_MS = 24L * 60 * 60 * 1000
        private const val REFRESH_AHEAD_MS = 10L * 60 * 1000
    }

    /**
     * 拿到本次请求要带的全部鉴权头。
     * 注意不返回 Host：JDK HttpClient 把 Host 列为受限头，手动 setHeader 会报错，
     * 让它自动带即可（域名本来就与签名用的 host 一致，现有转写服务也是这么跑的）。
     */
    suspend fun authHeaders(
        method: String, uri: String, body: String,
        host: String, region: String, service: String = "sis",
    ): Map<String, String> {
        val c = credential()
        return if (c.mode == Mode.IAM_TOKEN) {
            mapOf("X-Auth-Token" to c.token)
        } else {
            val date = nowUtc()
            mapOf(
                "X-Sdk-Date" to date,
                "Authorization" to HuaweiSigner.authHeader(
                    c.ak, c.sk, method, uri, body, host, region, service, date
                ),
            )
        }
    }

    /** SIS 返回 401/403 时调用：丢缓存，下次请求重新换 Token。 */
    fun invalidate() {
        cached = null
    }

    private suspend fun credential(): Credential {
        val now = System.currentTimeMillis()
        val fp = fingerprint()
        cached?.takeIf { it.expireAt > now + REFRESH_AHEAD_MS && it.fingerprint == fp }?.let { return it }
        return mutex.withLock {
            cached?.takeIf { it.expireAt > now + REFRESH_AHEAD_MS && it.fingerprint == fp }?.let { return@withLock it }
            buildCredential(fp).also { cached = it }
        }
    }

    /** 把所有会影响鉴权的配置拼成一个指纹，任一变化都让旧缓存作废。 */
    private fun fingerprint(): String {
        val cfg = loadSisConfig()
        return listOf(
            cfg.projectId, cfg.region, cfg.ak.orEmpty(), cfg.sk.orEmpty(),
            get("SIS_IAM_TOKEN"), get("SIS_IAM_USER"), get("SIS_IAM_PASSWORD"),
            get("SIS_IAM_DOMAIN"), get("SIS_IAM_PROJECT_NAME"),
        ).joinToString("|")
    }

    private suspend fun buildCredential(fp: String): Credential {
        val expireAt = System.currentTimeMillis() + TTL_MS

        // 1) 直接给了 Token（一般是外部系统换好写进来的，省一次 IAM 调用）
        get("SIS_IAM_TOKEN")?.let {
            return Credential(Mode.IAM_TOKEN, token = it, expireAt = expireAt, fingerprint = fp)
        }

        // 2) 给了 IAM 账密：真的去换一次 Token，之后 24 小时都复用
        val user = get("SIS_IAM_USER")
        val password = get("SIS_IAM_PASSWORD")
        if (!user.isNullOrBlank() && !password.isNullOrBlank()) {
            requestIamToken(user, password)?.let {
                log.info("SIS IAM Token 获取成功，缓存 24h")
                return Credential(Mode.IAM_TOKEN, token = it, expireAt = expireAt, fingerprint = fp)
            }
            log.warn("SIS IAM 换 Token 失败，回落 AK/SK 签名")
        }

        // 3) 默认：与现有录音转写完全一致的 AK/SK 签名，生产环境不用改任何配置就能跑
        val cfg = loadSisConfig()
        if (cfg.ak.isNullOrBlank() || cfg.sk.isNullOrBlank() || cfg.projectId.isBlank()) {
            throw VoiceChatException(
                "SIS 未配置：请在服务器配 SIS_PROJECT_ID / SIS_AK / SIS_SK（或复用 OBS_AK/OBS_SK），" +
                    "或配 SIS_IAM_TOKEN / SIS_IAM_USER + SIS_IAM_PASSWORD"
            )
        }
        return Credential(Mode.AK_SK, ak = cfg.ak, sk = cfg.sk, expireAt = expireAt, fingerprint = fp)
    }

    /** IAM 换 Token：POST /v3/auth/tokens，Token 在响应头 X-Subject-Token 里（不在 body）。 */
    private suspend fun requestIamToken(user: String, password: String): String? = withContext(Dispatchers.IO) {
        val cfg = loadSisConfig()
        val domain = get("SIS_IAM_DOMAIN") ?: user
        val projectName = get("SIS_IAM_PROJECT_NAME") ?: cfg.region
        val body = buildJsonObject {
            put("auth", buildJsonObject {
                put("identity", buildJsonObject {
                    put("methods", buildJsonArray { add("password") })
                    put("password", buildJsonObject {
                        put("user", buildJsonObject {
                            put("name", user)
                            put("password", password)
                            put("domain", buildJsonObject { put("name", domain) })
                        })
                    })
                })
                put("scope", buildJsonObject {
                    put("project", buildJsonObject { put("name", projectName) })
                })
            })
        }.toString()
        runCatching {
            val req = HttpRequest.newBuilder(URI.create("https://iam.${cfg.region}.myhuaweicloud.com/v3/auth/tokens"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            val token = resp.headers().firstValue("X-Subject-Token").orElse(null)
            if (resp.statusCode() in 200..299 && !token.isNullOrBlank()) token else {
                log.warn("IAM 换 Token 失败 HTTP ${resp.statusCode()}：${resp.body().take(200)}")
                null
            }
        }.onFailure { log.warn("IAM 换 Token 异常：${it.message}") }.getOrNull()
    }

    private fun get(key: String): String? = SecretStore.get(key)?.trim()?.takeIf { it.isNotBlank() }

    private fun nowUtc(): String =
        ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
}

/** 语音对话链路自己的业务异常，路由层统一兜底，绝不对外抛 500。 */
class VoiceChatException(message: String) : Exception(message)
