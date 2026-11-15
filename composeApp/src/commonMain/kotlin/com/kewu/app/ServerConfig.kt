package com.kewu.app

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/**
 * 服务器域名远程配置（2026-11-16）。
 *
 * 目的：服务器地址不再写死在 App 里，改为从 Gitee 仓库的 server_url 文件云端下发，
 * 队友改那个文件即可切换服务器，用户不用重装 App。
 *
 * 工作流程：
 *   1. [baseUrl] 每次读取都返回当前生效地址（内存值优先，其次本地 kv 缓存，最后兜底）；
 *   2. App 启动 / 下拉刷新时调 [refresh]，从 Gitee raw 拉最新地址，成功后写内存 + kv；
 *   3. 拉取失败（网络 / 反爬 / 格式错）一律静默保留旧值，绝不让 App 连不上或崩溃。
 *
 * ★ 兼容三种文件内容：纯 URL（`http://x:8080`）、带引号、JSON（`{"server_url":"..."}` /
 *   `{"url":"..."}` / 直接一个字符串）。末尾斜杠统一去掉，避免拼出 `//api`。
 */
object ServerConfig {
    // 云端配置文件（Gitee raw）。用户名是 xiaoyeaowoaini（带 y），master 分支。
    private const val CONFIG_URL =
        "https://gitee.com/xiaoyeaowoaini/kewu/raw/master/server_url"

    // 兜底地址：新服务器。只有「从没联网成功过 + 本地无缓存」才会用到。
    private const val FALLBACK_URL = "http://8.136.151.139:8080"

    private const val KV_KEY = "kewu_server_url"

    // 内存中的当前地址；为空表示尚未加载，走 [effectiveUrl] 的缓存/兜底逻辑。
    @kotlin.concurrent.Volatile
    private var current: String? = null

    private val refreshMutex = Mutex()

    /** 当前生效的服务器基址（不带末尾斜杠）。KewuApi.BASE_URL / AiClient 都读这里。 */
    val baseUrl: String
        get() = effectiveUrl()

    private fun effectiveUrl(): String {
        current?.let { if (it.isNotBlank()) return it }
        val cached = try { kvGet(KV_KEY) } catch (_: Exception) { null }
        if (!cached.isNullOrBlank() && isValidUrl(cached)) return normalize(cached)
        return FALLBACK_URL
    }

    /** App 启动时调用：优先用本地 kv 缓存立即生效，再异步拉云端（调用方可 fire-and-forget）。 */
    fun loadCached() {
        if (current != null) return
        val cached = try { kvGet(KV_KEY) } catch (_: Exception) { null }
        if (!cached.isNullOrBlank() && isValidUrl(cached)) current = normalize(cached)
    }

    /**
     * 从 Gitee 拉取最新地址。成功返回新地址，失败返回当前生效地址（静默降级）。
     * 用独立的短超时客户端，不跟业务请求抢连接池；拉完即关。
     */
    suspend fun refresh(): String = refreshMutex.withLock {
        val client = platformHttpClient().config {
            install(HttpTimeout) {
                requestTimeoutMillis = 8_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 8_000
            }
        }
        try {
            val resp = client.get(CONFIG_URL)
            if (resp.status.value in 200..299) {
                val parsed = parseUrl(resp.bodyAsText())
                if (parsed != null) {
                    current = parsed
                    try { kvSet(KV_KEY, parsed) } catch (_: Exception) {}
                    return parsed
                }
            }
        } catch (_: Exception) {
            // 网络不通 / Gitee 反爬 403 / 超时：保留旧值，不影响 App 正常使用
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
        effectiveUrl()
    }

    /** 兼容纯文本 URL、带引号、JSON 对象的解析。无法识别返回 null。 */
    private fun parseUrl(raw: String): String? {
        val text = raw.trim().trim('\uFEFF').trim()
        if (text.isEmpty()) return null
        // 清理 Markdown 代码围栏 ```...```
        val cleaned = text.removePrefix("```").removeSuffix("```")
            .removePrefix("json").trim().removePrefix("\"").removeSuffix("\"").trim()
        // JSON：{ "server_url": "http://..." } / { "url": "..." } / "http://..."
        val candidate = try {
            val el = Json.parseToJsonElement(cleaned)
            when {
                el is kotlinx.serialization.json.JsonObject ->
                    listOf("server_url", "url", "baseUrl", "base_url", "serverUrl")
                        .firstNotNullOfOrNull { k -> el[k]?.jsonPrimitive?.content }
                else -> el.jsonPrimitive.content
            }
        } catch (_: Exception) {
            cleaned // 不是 JSON，就当纯文本 URL
        }
        return if (candidate != null && isValidUrl(candidate)) normalize(candidate) else null
    }

    private fun isValidUrl(s: String): Boolean {
        val v = s.trim().removePrefix("\"").removeSuffix("\"").trim()
        return (v.startsWith("http://") || v.startsWith("https://")) &&
            v.length > 12 && !v.contains("\n") && !v.contains("<")
    }

    private fun normalize(s: String): String =
        s.trim().removePrefix("\"").removeSuffix("\"").trim().trimEnd('/')

    /** 调试用：当前地址来自哪一层。 */
    fun describe(): String = "baseUrl=${effectiveUrl()}, current=$current, kv=${try { kvGet(KV_KEY) } catch (_: Exception) { null }}"
}
