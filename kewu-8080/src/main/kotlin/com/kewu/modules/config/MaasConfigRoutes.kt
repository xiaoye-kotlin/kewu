package com.kewu.modules.config

import com.kewu.infra.SecretStore
import com.kewu.modules.admin.service.AdminService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 兼容旧客户端 / 总控端的 MaaS 配置接口（2026-11-05）。
 *
 * 早期版本读 app_configs 表明文表；现在总控端把密钥加密存 system_config，统一由 [SecretStore]
 * 读写（App 也已改成服务器中转，不再需要自己拿 key）。保留这几个路径只是为了让老版本 App、
 * 老总控端页面在后端升级后不 404，不重新引入旧表、不覆盖队友的新配置模块。
 */
fun Route.maasConfigRoutes(adminService: AdminService) {

    // 旧版 App / 外部探测用：公开读当前生效的 MaaS 配置。
    // 2026-11-05 安全修复：这个路径无鉴权，以前会把完整 API Key 明文吐给公网。
    // 最新 App 已改成服务器中转、根本不调它，所以保留字段结构但只回脱敏值；
    // 真要读完整密钥走下面有管理员鉴权的 /api/admin/config/maas。
    get("/api/config/maas") {
        val apiUrl = SecretStore.get("MAAS_API_URL")?.trim().orEmpty()
        val apiKey = SecretStore.get("MAAS_API_KEY")?.trim().orEmpty()
        val modelName = SecretStore.get("MAAS_MODEL_NAME")?.trim().orEmpty()
        val body = buildJsonObject {
            if (apiUrl.isBlank() || apiKey.isBlank() || modelName.isBlank()) {
                put("code", 1)
                put("message", "AI 配置未就绪，请在总控端「密钥配置」填写 MAAS_API_URL / MAAS_API_KEY / MAAS_MODEL_NAME")
            } else {
                put("code", 0)
                put("message", "ok（公开接口不返回完整密钥，管理员请走 /api/admin/config/maas）")
                put("data", buildJsonObject {
                    put("apiUrl", apiUrl)
                    put("apiKey", maskKey(apiKey))
                    put("modelName", modelName)
                    put("masked", true)
                })
            }
        }
        // 手拼 JSON 直接回，避开 ApiResponse 泛型序列化（项目里 respond 泛型容器踩过 500）
        call.respondText(body.toString(), ContentType.Application.Json)
    }

    // 旧总控端「AI 直连配置」页：转发到新版 SecretStore，鉴权方式与 /api/admin/config 一致
    get("/api/admin/config/maas") {
        if (!adminService.isAdmin(call)) {
            call.respondText(UNAUTHORIZED, ContentType.Application.Json, HttpStatusCode.Unauthorized)
            return@get
        }
        val apiUrl = SecretStore.get("MAAS_API_URL")?.trim().orEmpty()
        val apiKey = SecretStore.get("MAAS_API_KEY")?.trim().orEmpty()
        val modelName = SecretStore.get("MAAS_MODEL_NAME")?.trim().orEmpty()
        call.respondText(buildJsonObject {
            put("code", 0); put("message", "ok")
            put("data", buildJsonObject {
                put("apiUrl", apiUrl); put("apiKey", apiKey); put("modelName", modelName)
                put("updatedAt", "")
            })
        }.toString(), ContentType.Application.Json)
    }

    post("/api/admin/config/maas") {
        if (!adminService.isAdmin(call)) {
            call.respondText(UNAUTHORIZED, ContentType.Application.Json, HttpStatusCode.Unauthorized)
            return@post
        }
        val root = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
        val patch = mutableMapOf<String, String>()
        listOf("apiUrl" to "MAAS_API_URL", "apiKey" to "MAAS_API_KEY", "modelName" to "MAAS_MODEL_NAME")
            .forEach { (field, key) ->
                root?.get(field)?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { patch[key] = it }
            }
        if (patch.isEmpty()) {
            call.respondText(
                """{"code":1,"message":"请求体需含 apiUrl / apiKey / modelName 中至少一项"}""",
                ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            return@post
        }
        val changed = runCatching { SecretStore.set(patch) }.getOrElse { emptyList() }
        call.respondText(buildJsonObject {
            put("code", 0)
            put("message", if (changed.isEmpty()) "未产生变更" else "已保存，App 下次拉取即生效")
        }.toString(), ContentType.Application.Json)
    }
}

private const val UNAUTHORIZED = """{"code":401,"message":"未授权：需要管理员凭证（Bearer 管理员Token 或 X-Admin-Key）"}"""

/** 只留头 4 位 + 尾 4 位，够确认「配的是哪把 key」，又拿不去调用。 */
private fun maskKey(key: String): String =
    if (key.length > 12) "${key.take(4)}****${key.takeLast(4)}" else "****"
