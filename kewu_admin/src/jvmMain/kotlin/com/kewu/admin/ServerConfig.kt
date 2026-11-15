package com.kewu.admin

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import java.net.Proxy

/**
 * 总控端服务器域名远程配置（2026-11-16）。
 * 与 App 端读同一个 Gitee server_url 文件，队友改一处两端同时生效。
 * 总控端常跑在服务器本机，公网 IP 可能无 NAT 回环，所以这里只把云端地址作为
 * AdminApi.resolveBaseUrl() 的候选之一（优先级最低），本机 127.0.0.1 探测仍优先。
 */
object ServerConfig {
    private const val CONFIG_URL =
        "https://gitee.com/xiaoyeaowoaini/kewu/raw/master/server_url"

    /** 拉云端地址；失败（网络 / 反爬 / 格式错）返回 null，绝不抛异常。 */
    suspend fun fetch(): String? {
        val client = HttpClient(OkHttp) {
            engine { proxy = Proxy.NO_PROXY }
            install(HttpTimeout) {
                requestTimeoutMillis = 8_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 8_000
            }
        }
        try {
            val resp = client.get(CONFIG_URL)
            if (resp.status.value in 200..299) return parseUrl(resp.bodyAsText())
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
        return null
    }

    private fun parseUrl(raw: String): String? {
        val text = raw.trim().trim('\uFEFF').trim()
        if (text.isEmpty()) return null
        val cleaned = text.removePrefix("```").removeSuffix("```")
            .removePrefix("json").trim().removePrefix("\"").removeSuffix("\"").trim()
        val candidate = try {
            val el = Json.parseToJsonElement(cleaned)
            when {
                el is kotlinx.serialization.json.JsonObject ->
                    listOf("server_url", "url", "baseUrl", "base_url", "serverUrl")
                        .firstNotNullOfOrNull { k -> el[k]?.jsonPrimitive?.content }
                else -> el.jsonPrimitive.content
            }
        } catch (_: Exception) {
            cleaned
        }
        return if (candidate != null && isValid(candidate)) normalize(candidate) else null
    }

    private fun isValid(s: String): Boolean {
        val v = s.trim().removePrefix("\"").removeSuffix("\"").trim()
        return (v.startsWith("http://") || v.startsWith("https://")) &&
            v.length > 12 && !v.contains("\n") && !v.contains("<")
    }

    private fun normalize(s: String): String =
        s.trim().removePrefix("\"").removeSuffix("\"").trim().trimEnd('/')
}
