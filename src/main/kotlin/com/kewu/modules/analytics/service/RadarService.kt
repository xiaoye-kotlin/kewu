package com.kewu.modules.analytics.service

import com.kewu.db.RadarRedisClient
import com.kewu.infra.HuaweiMaaSClient
import com.kewu.modules.analytics.repository.QuestionRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

// 学情雷达（AI 问答聚合）：把某门课最近 50 条学生提问丢给大模型提炼核心知识点，
// 返回 [{keyword, count}] 且按 count 降序。
//
// 流程：查 Redis 缓存 →(未命中) SETNX 加分布式锁防击穿 → 查 MySQL 最近 50 条提问 →
// 调华为云 MaaS 提取知识点 → 解析(去 markdown 围栏) → 回写 Redis(TTL 5 分钟) → finally 释放锁。
//
// 容错是这接口的生命线：缓存异常/库异常/AI 超时/AI 返回格式不对/锁抢不到，
// 任何一步出错都打日志 + 降级，绝不抛 500，宁可返回空数组 []。
class RadarService(
    private val redis: RadarRedisClient,
    private val questions: QuestionRepository,
    private val maas: HuaweiMaaSClient
) {
    private val log = LoggerFactory.getLogger(RadarService::class.java)

    private val cacheTtlSeconds = 300L          // 缓存 5 分钟
    private val lockTtlSeconds = 30L            // 锁 30 秒足够一次 AI 调用
    private val emptyJson = "[]"

    // 对外暴露：返回标准 JSON 数组文本（命中缓存直接回，未命中算完回）。任何异常都回 "[]"。
    suspend fun getRadarJson(courseId: String): String {
        if (courseId.isBlank()) return emptyJson
        return runCatching { compute(courseId) }.getOrDefault(emptyJson)
    }

    // 对外暴露：返回 List<Map> 形态（与接口定义一致），给需要结构化数据的调用方用。
    suspend fun getRadarList(courseId: String): List<Map<String, Any?>> {
        return runCatching { parseRadar(compute(courseId)) ?: emptyList() }
            .getOrDefault(emptyList())
    }

    private suspend fun compute(courseId: String): String {
        // 缓存 key 特意用 radar:qa: 前缀，避开 3.8 节“学情画像”接口用的 radar:{courseId}，两者数据不同不能共用一个 key
        val cacheKey = "radar:qa:$courseId"
        val lockKey = "radar:qa:lock:$courseId"

        // ① 查缓存（Redis 异常时 get 返回 null，自然走进“未命中”分支，不抛异常）
        val cached = redis.get(cacheKey)
        if (cached != null) {
            // 缓存命中：能解析就直接回；解析不动说明脏数据，删掉重算
            val parsed = parseRadar(cached)
            if (parsed != null) {
                log.info("Radar: 缓存命中 courseId=$courseId")
                return cached
            }
            log.warn("Radar: 缓存数据损坏，删除后重算 courseId=$courseId")
            redis.del(cacheKey) // 清掉坏 key，下次重算写入好的
        }

        // ② 防击穿加锁：SETNX 只在 key 不存在时写入，保证同一刻只有一个请求去打 AI
        val locked = redis.tryLock(lockKey, lockTtlSeconds)
        if (!locked) {
            // 没抢到锁：说明别的实例正在算，这里直接降级为“无锁计算”，保证人人都有数据、不卡死
            log.info("Radar: 未抢到锁(别的实例在算)，降级直算 courseId=$courseId")
            return doComputeAndCache(courseId, cacheKey)
        }

        // 抢到锁：在 finally 里务必释放，避免持锁进程崩了锁变孤儿
        return try {
            doComputeAndCache(courseId, cacheKey)
        } finally {
            redis.releaseLock(lockKey)
        }
    }

    private suspend fun doComputeAndCache(courseId: String, cacheKey: String): String {
        // ③ 查库：最近 50 条提问
        val recent = questions.recentQuestions(courseId, 50)
        if (recent.isEmpty()) {
            log.info("Radar: 该课没有提问记录，返回空 courseId=$courseId")
            return emptyJson
        }

        // ④ 调 AI 语义提取（异常/未配置都返回 null，这里降级返回空数组）
        val raw = maas.extractKeywords(recent.joinToString("\n"))
        if (raw.isNullOrBlank()) {
            log.warn("Radar: AI 未返回内容，降级返回空 courseId=$courseId")
            return emptyJson
        }

        // ⑤ 解析：先去 markdown 围栏，再抽 JSON 数组
        val cleaned = stripMarkdownFences(raw)
        val list = parseRadar(cleaned)
        if (list.isNullOrEmpty()) {
            log.warn("Radar: AI 返回无法解析为知识点数组，降级返回空（raw 前 200 字=${raw.take(200)}）")
            return emptyJson
        }

        // 按 count 降序
        val sorted = list.sortedByDescending { (it["count"] as? Int) ?: 0 }
        val json = toJsonArrayString(sorted)

        // ⑥ 回写缓存（写失败只是少一层加速，不阻塞返回）
        redis.setex(cacheKey, cacheTtlSeconds, json)
        return json
    }

    // 去 ```json ... ``` 这类围栏，顺便吃掉开头的语言标识
    private fun stripMarkdownFences(s: String): String {
        var t = s.trim()
        val fence = "```"
        if (t.contains(fence)) {
            val start = t.indexOf(fence) + fence.length
            val end = t.lastIndexOf(fence)
            if (end > start) {
                t = t.substring(start, end).trim()
                if (t.startsWith("json", ignoreCase = true)) t = t.removePrefix("json").trim()
            }
        }
        return t
    }

    // 解析 AI 返回的 JSON 数组为 List<Map>；格式不对返回 null（交给上层降级）
    private fun parseRadar(text: String): List<Map<String, Any?>>? {
        val el = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
        if (el !is JsonArray) return null
        val out = mutableListOf<Map<String, Any?>>()
        for (item in el) {
            if (item !is JsonObject) continue
            val keyword = item["keyword"]?.jsonPrimitive?.contentOrNull ?: continue
            val count = (item["count"]?.jsonPrimitive?.intOrNull ?: 1).coerceAtLeast(1)
            out.add(mapOf("keyword" to keyword, "count" to count))
        }
        return out
    }

    // List<Map> → 裸 JSON 数组字符串（不用 @Serializable，手动拼，符合要求）
    private fun toJsonArrayString(list: List<Map<String, Any?>>): String = buildJsonArray {
        for (m in list) {
            add(buildJsonObject {
                put("keyword", m["keyword"]?.toString() ?: "")
                put("count", (m["count"] as? Int) ?: 0)
            })
        }
    }.toString()
}
