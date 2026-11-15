package com.kewu.modules.focus

import com.kewu.security.JwtConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// /api/focus/** 七个动作接口。前端只传动作，不传任何时间戳；
// 响应统一手拼 {code,message,data}，避开 Ktor 对 Map<String,Any> / 泛型容器序列化报 500 的老坑。
fun Routing.focusRoutes(service: FocusService) {
    route("/api/focus") {

        // 开始专注：写 Hash + 2 小时 TTL，返回服务端起始时间（仅供展示，计时请用 /status）
        post("/start") {
            val uid = call.focusUserId() ?: return@post call.focusFail(
                HttpStatusCode.Unauthorized, 401, "需要登录，或用 ?userId= 指定用户"
            )
            val req = runCatching { call.receive<FocusStartRequest>() }.getOrDefault(FocusStartRequest())
            val res = service.start(uid, req)
            call.focusOk(
                if (res.redisOk) "专注已开始" else "专注已开始（Redis 暂不可用，已降级到服务端内存）",
                buildJsonObject {
                    put("startTime", res.startTime)
                    put("plannedMinutes", res.plannedMinutes)
                    put("activeTtlSeconds", FocusRedis.ACTIVE_TTL_SECONDS)
                    put("redis", res.redisOk)
                }
            )
        }

        // 上报分心：只在有进行中的专注时 HINCRBY +1，没有就静默忽略
        post("/distraction") {
            val uid = call.focusUserId() ?: return@post call.focusFail(
                HttpStatusCode.Unauthorized, 401, "需要登录，或用 ?userId= 指定用户"
            )
            val count = service.distraction(uid)
            call.focusOk(
                if (count == null) "当前没有进行中的专注，已忽略" else "已记录一次分心",
                buildJsonObject { put("distractionCount", count ?: 0) }
            )
        }

        // 完成专注：算实际时长、落库 COMPLETED、删 active、写 5 分钟休息倒计时
        post("/finish") {
            val uid = call.focusUserId() ?: return@post call.focusFail(
                HttpStatusCode.Unauthorized, 401, "需要登录，或用 ?userId= 指定用户"
            )
            val req = runCatching { call.receive<FocusFinishRequest>() }.getOrDefault(FocusFinishRequest())
            val res = service.finish(uid, req)
            if (res.ignored) {
                call.focusOk("没有进行中的专注，已忽略（幂等）")
                return@post
            }
            call.focusOk(
                "专注已完成" + if (res.record == null) "（记录保存失败，不影响本次结束）" else "",
                buildJsonObject {
                    put("recordId", res.record?.id ?: "")
                    put("actualMinutes", res.record?.actualMinutes ?: 0)
                    put("status", FocusStatus.COMPLETED.value)
                    put("restSeconds", res.restSeconds)
                    put("redis", res.redisOk)
                }
            )
        }

        // 取消专注：落库 CANCELLED + actualMinutes=0，绝不写休息倒计时
        post("/cancel") {
            val uid = call.focusUserId() ?: return@post call.focusFail(
                HttpStatusCode.Unauthorized, 401, "需要登录，或用 ?userId= 指定用户"
            )
            val res = service.cancel(uid)
            if (res.ignored) {
                call.focusOk("没有进行中的专注，已忽略（幂等）")
                return@post
            }
            call.focusOk(
                "专注已取消",
                buildJsonObject {
                    put("recordId", res.record?.id ?: "")
                    put("actualMinutes", 0)
                    put("status", FocusStatus.CANCELLED.value)
                    put("restSeconds", 0)
                }
            )
        }

        // 跳过休息：删掉 rest key，重复点也成功
        post("/skip-rest") {
            val uid = call.focusUserId() ?: return@post call.focusFail(
                HttpStatusCode.Unauthorized, 401, "需要登录，或用 ?userId= 指定用户"
            )
            service.skipRest(uid)
            call.focusOk("已跳过休息")
        }

        // 当前状态：进行中带已过秒数，休息中带剩余秒数
        get("/status") {
            val uid = call.focusUserId() ?: return@get call.focusFail(
                HttpStatusCode.Unauthorized, 401, "需要登录，或用 ?userId= 指定用户"
            )
            val res = service.status(uid)
            call.focusOk(
                "ok",
                buildJsonObject {
                    put("focusing", res.active != null)
                    val elapsed = res.elapsedSeconds
                    if (elapsed != null) put("elapsedSeconds", elapsed) else put("elapsedSeconds", JsonNull)
                    val planned = res.active?.plannedMinutes
                    if (planned != null) put("plannedMinutes", planned) else put("plannedMinutes", JsonNull)
                    val taskId = res.active?.taskId
                    if (taskId != null) put("taskId", taskId) else put("taskId", JsonNull)
                    val distractions = res.active?.distractionCount
                    if (distractions != null) put("distractionCount", distractions)
                    else put("distractionCount", JsonNull)
                    val rest = res.restRemainingSeconds
                    put("resting", rest != null)
                    if (rest != null) put("restRemainingSeconds", rest) else put("restRemainingSeconds", JsonNull)
                }
            )
        }

        // AI 认知习惯推荐：不足 2 条 COMPLETED 直接默认；AI 超时/解析失败也回落默认
        get("/ai-suggestion") {
            val uid = call.focusUserId() ?: return@get call.focusFail(
                HttpStatusCode.Unauthorized, 401, "需要登录，或用 ?userId= 指定用户"
            )
            val s = service.suggestion(uid)
            call.focusOk(
                "ok",
                buildJsonObject {
                    put("recommended_minutes", s.recommendedMinutes)
                    put("rest_minutes", s.restMinutes)
                    put("fatigue_index", s.fatigueIndex)
                    put("advice", s.advice)
                    put("source", s.source)
                }
            )
        }
    }
}

// 优先用登录态里的 userId（防止前端替别人写数据）；没带 token 时允许 ?userId= 兜底，方便队友联调。
// 正式上线若要收紧，把 queryParameters 那一行删掉即可。
private fun ApplicationCall.focusUserId(): String? {
    val auth = request.headers["Authorization"].orEmpty().trim()
    val token = when {
        auth.startsWith("Bearer ", ignoreCase = true) -> auth.substring(7).trim()
        auth.startsWith("bearer ", ignoreCase = true) -> auth.substring(7).trim()
        else -> ""
    }
    val fromToken = if (token.isNotBlank()) JwtConfig.verifyToken(token) else null
    return fromToken ?: request.queryParameters["userId"]?.trim()?.takeIf { it.isNotBlank() }
}

private suspend fun ApplicationCall.focusOk(message: String, data: JsonObject? = null) {
    respondText(
        buildJsonObject {
            put("code", 0)
            put("message", message)
            if (data != null) put("data", data)
        }.toString(),
        ContentType.Application.Json
    )
}

private suspend fun ApplicationCall.focusFail(status: HttpStatusCode, code: Int, message: String) {
    respondText(
        buildJsonObject {
            put("code", code)
            put("message", message)
        }.toString(),
        ContentType.Application.Json,
        status
    )
}
