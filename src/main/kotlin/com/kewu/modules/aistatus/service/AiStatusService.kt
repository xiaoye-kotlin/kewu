package com.kewu.modules.aistatus.service

import com.kewu.db.AiRedisResult
import com.kewu.db.AiStatusRedis
import com.kewu.db.kewuJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

// AI 纪要生成状态轮询：前端拿这个接口驱动“AI 正在生成纪要”的进度条。
// 返回【裸 JSON】，不套 {code,message,data} 壳——前端直接读 status / progress / noteId。
//
// 四种状态（按需求）：
//   PENDING    未开始     {"status":"PENDING"}
//   PROCESSING 处理中     {"status":"PROCESSING","progress":60}   （Redis 里的原值）
//   FINISHED   已完成     {"status":"FINISHED","noteId":"n1020"}  （Redis 里的原值）
//   ERROR      获取失败   {"status":"ERROR","message":"获取状态失败"}
//
// 重要避坑：本项目不依赖 @Serializable 数据类，这里全部用字符串常量 + Json.parseToJsonElement 做校验，
// 最终由路由用 respondText 原样输出，保证 Ktor 不会因序列化插件/配置差异把接口打挂。
class AiStatusService(private val redis: AiStatusRedis) {
    private val log = LoggerFactory.getLogger(AiStatusService::class.java)

    fun getStatusJson(sessionId: String?): String {
        // 1) sessionId 空或格式不对：当没查到，返回 PENDING（绝不报错）
        if (sessionId.isNullOrBlank()) return PENDING_JSON

        val key = "kewu:ai:status:$sessionId"
        return try {
            when (val r = redis.getRaw(key)) {
                // 2) key 不存在（含 Redis 没配）：未开始
                is AiRedisResult.Missing -> PENDING_JSON
                // 3) Redis 连不上 / 超时 / 读异常：明确告诉前端获取失败
                is AiRedisResult.Error -> {
                    log.warn("AiStatus: 读 sessionId=$sessionId 状态失败：${r.e.message}")
                    ERROR_JSON
                }
                // 4) 有值：先校验是不是合法 JSON，再原样返回（progress / noteId 都由写端决定）
                is AiRedisResult.Value -> {
                    runCatching { kewuJson.parseToJsonElement(r.json) }.onFailure {
                        log.warn("AiStatus: sessionId=$sessionId 的 Redis 值不是合法 JSON，按 ERROR 处理：${it.message} | raw=${r.json}")
                        return ERROR_JSON
                    }
                    r.json
                }
            }
        } catch (e: Throwable) {
            // 兜底：任何未预期异常都吞掉，返回 ERROR，绝不漏成 500 让前端崩
            log.warn("AiStatus: 未预期异常 sessionId=$sessionId：${e.message}")
            ERROR_JSON
        }
    }

    companion object {
        // 这几个固定 JSON 用字符串常量写死，避免任何序列化插件 / 配置差异导致的意外
        const val PENDING_JSON = """{"status":"PENDING"}"""
        const val ERROR_JSON = """{"status":"ERROR","message":"获取状态失败"}"""

        // 写端允许的状态白名单（和轮询端四种状态一致）
        val ALLOWED_STATUSES = setOf("PENDING", "PROCESSING", "FINISHED", "ERROR")
    }

    // ===== 写端：后端 AI 纪要生成任务用来更新进度 =====
    // 入参 body 应为裸 JSON 对象，例：{"status":"PROCESSING","progress":60} / {"status":"FINISHED","noteId":"n1020"}
    // 返回也是裸 JSON：{"ok":true} 或 {"ok":false,"message":"..."}（不套壳，和轮询端保持一致）。
    fun updateStatus(sessionId: String?, body: String): String {
        if (sessionId.isNullOrBlank()) {
            return writeResult(false, "sessionId 不能为空")
        }
        val element = runCatching { kewuJson.parseToJsonElement(body) }.getOrNull()
        if (element == null || element !is JsonObject) {
            return writeResult(false, "请求体必须是 JSON 对象")
        }
        val status = element["status"]?.let { if (it is JsonPrimitive && it.isString) it.content else null }
        if (status == null || status !in ALLOWED_STATUSES) {
            return writeResult(false, "status 必须是以下之一: ${ALLOWED_STATUSES.joinToString()}")
        }
        // PROCESSING 用短 TTL 做存活探针（默认 180s），其余（含最终结果）留 1 小时足够前端读取
        val ttl = if (status == "PROCESSING") 180L else 3600L
        val stored = runCatching { redis.setStatus(sessionId, body, ttl) }.getOrDefault(false)
        return if (stored) writeResult(true)
        else writeResult(false, "状态写入失败：Redis 当前不可用")
    }

    private fun writeResult(ok: Boolean, message: String? = null): String =
        if (message == null) """{"ok":$ok}"""
        else """{"ok":$ok,"message":${kewuJson.encodeToString(message)}}"""
}
