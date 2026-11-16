package com.kewu.modules.focus

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

// 番茄钟业务层：时间一律 System.currentTimeMillis()，Redis 挂了照常走，AI 结果一律强校验。
// 取消的记录 status=CANCELLED，而 AI 只查 COMPLETED，所以它天然不参与分析。
class FocusService(
    private val repo: FocusRepository,
    private val redis: FocusRedis,
    private val ai: FocusAiClient,
) {
    companion object {
        private const val AI_TIMEOUT_MS = 15_000L
    }

    fun start(userId: String, req: FocusStartRequest): FocusStartResult {
        val planned = req.plannedMinutes.coerceIn(1, 180)
        // 时间是服务端给的，前端一个时间戳都不用传
        val session = ActiveFocus(
            taskId = req.taskId?.trim()?.takeIf { it.isNotEmpty() },
            startTime = System.currentTimeMillis(),
            plannedMinutes = planned,
        )
        return FocusStartResult(session.startTime, planned, redis.start(userId, session))
    }

    fun distraction(userId: String): Int? = redis.distraction(userId)

    fun finish(userId: String, req: FocusFinishRequest): FocusFinishResult {
        // 拿不到会话 = 已经 finish/cancel 过，或 2 小时 TTL 到了：静默成功，不重复落库
        val session = redis.consumeActive(userId)
            ?: return FocusFinishResult(ignored = true, record = null, restSeconds = 0, redisOk = true)

        val endTime = System.currentTimeMillis()
        val actualMinutes = ((endTime - session.startTime) / 60_000.0).roundToInt().coerceAtLeast(0)
        val record = FocusRecord(
            id = UUID.randomUUID().toString(),
            userId = userId,
            taskId = session.taskId,
            startTime = session.startTime,
            endTime = endTime,
            plannedMinutes = session.plannedMinutes,
            actualMinutes = actualMinutes,
            distractionCount = session.distractionCount,
            taskDifficulty = req.taskDifficulty.coerceIn(1, 5),
            fatigueLevel = req.fatigueLevel.coerceIn(1, 5),
            status = FocusStatus.COMPLETED.value,
        )
        val saved = repo.insert(record)
        val redisOk = redis.setRest(userId)
        return FocusFinishResult(
            ignored = false,
            record = record.takeIf { saved },
            restSeconds = FocusRedis.REST_TTL_SECONDS,
            redisOk = redisOk,
        )
    }

    fun cancel(userId: String): FocusCancelResult {
        val session = redis.consumeActive(userId)
            ?: return FocusCancelResult(ignored = true, record = null)

        val endTime = System.currentTimeMillis()
        val record = FocusRecord(
            id = UUID.randomUUID().toString(),
            userId = userId,
            taskId = session.taskId,
            startTime = session.startTime,
            endTime = endTime,
            plannedMinutes = session.plannedMinutes,
            actualMinutes = 0,
            distractionCount = session.distractionCount,
            taskDifficulty = 3,
            fatigueLevel = 3,
            status = FocusStatus.CANCELLED.value,
        )
        val saved = repo.insert(record)
        // 这里绝不调用 redis.setRest()：取消不产生 5 分钟休息倒计时
        return FocusCancelResult(ignored = false, record = record.takeIf { saved })
    }

    fun skipRest(userId: String): Boolean = redis.skipRest(userId)

    fun status(userId: String): FocusStatusResult {
        val now = System.currentTimeMillis()
        val active = redis.active(userId)
        val elapsed = active?.let { ((now - it.startTime) / 1000L).coerceAtLeast(0) }
        return FocusStatusResult(active, elapsed, redis.restRemainingSeconds(userId))
    }

    suspend fun suggestion(userId: String): FocusSuggestion {
        val records = repo.recentCompleted(userId, 5)
        // 冷启动：少于 2 条完成记录不打扰模型，直接给默认番茄钟
        if (records.size < 2) return FocusSuggestion.DEFAULT
        val raw = withTimeoutOrNull(AI_TIMEOUT_MS) { ai.chat(buildPrompt(records)) }
        return parseSuggestion(raw)
    }

    // 两条科学公式不只是写在 prompt 里，后端先算好逐条数值和均值再喂给模型，推荐才有硬依据
    private fun fatigueIndex(minutes: Int, difficulty: Int): Double =
        100.0 * (1.0 - exp(-0.012 * minutes * difficulty))

    private fun restByFormula(minutes: Int, difficulty: Int, distractions: Int): Double =
        5.0 * (minutes / 25.0).pow(1.15) * (difficulty + 0.05 * distractions)

    private fun buildPrompt(records: List<FocusRecord>): String {
        val rows = buildJsonArray {
            records.forEach { r ->
                add(buildJsonObject {
                    put("actual_minutes", r.actualMinutes)
                    put("planned_minutes", r.plannedMinutes)
                    put("task_difficulty", r.taskDifficulty)
                    put("fatigue_level", r.fatigueLevel)
                    put("distraction_count", r.distractionCount)
                    put("fatigue_index_formula", fatigueIndex(r.actualMinutes, r.taskDifficulty).roundToInt())
                    put(
                        "rest_minutes_formula",
                        restByFormula(r.actualMinutes, r.taskDifficulty, r.distractionCount).roundToInt()
                    )
                })
            }
        }
        val data = buildJsonObject {
            put("sample_count", records.size)
            put("avg_actual_minutes", records.map { it.actualMinutes }.average().roundToInt())
            put("avg_task_difficulty", records.map { it.taskDifficulty }.average())
            put("avg_fatigue_level", records.map { it.fatigueLevel }.average())
            put("avg_distraction_count", records.map { it.distractionCount }.average())
            put(
                "avg_fatigue_index_formula",
                records.map { fatigueIndex(it.actualMinutes, it.taskDifficulty) }.average().roundToInt()
            )
            put(
                "avg_rest_minutes_formula",
                records.map { restByFormula(it.actualMinutes, it.taskDifficulty, it.distractionCount) }
                    .average().roundToInt()
            )
            put("records", rows)
        }.toString()

        return """
作为认知科学教练，根据公式：
1. 疲劳指数=100*(1-e^(-0.012*专注时间*任务难度))；
2. 推荐休息=5*(专注时间/25)^1.15*(任务难度+0.05*中断次数)。
分析以下数据：$data。推荐下次专注时长（15-45分钟）。
严格返回纯 JSON 格式：
{"recommended_minutes": 30, "rest_minutes": 8, "fatigue_index": 65, "advice": "...", "visual_config": {"bg_style": "DARK_BLUE_SOLID", "blur_radius": 15, "darkness_overlay": 20}}

要求：
- 数据里 *_formula 字段是后端按上面公式精确算好的值，请以它们和自评疲劳度、中断次数为主要依据；
- recommended_minutes 取 15-45 的整数，rest_minutes 取 3-30 的整数，fatigue_index 取 0-100 的整数；
- advice 用一句简短中文（30 字以内），说明为什么这样安排；
- visual_config 必须返回，用于生成适配当前状态的专注背景：
  · bg_style 只能三选一：DARK_BLUE_SOLID（深灰蓝纯色，疲劳偏低/需稳定时）、BLURRED_NATURE（模糊自然图，疲劳偏高/需放松时）、DARK_GRADIENT（深蓝到黑渐变，中性）；
  · blur_radius 取 0-20 的整数（仅 BLURRED_NATURE 生效，其余样式填 15 即可）；
  · darkness_overlay 取 10-30 的整数（越大越暗，疲劳高时适当加深）；
- 只输出 JSON 本身，绝对不要 Markdown 代码围栏，也不要任何解释文字。
""".trimIndent()
    }

    // 手动 Json.parseToJsonElement 解析：清 Markdown 围栏、抠出 JSON 对象，任何异常/缺字段都回落默认推荐
    private fun parseSuggestion(raw: String?): FocusSuggestion {
        if (raw.isNullOrBlank()) return FocusSuggestion.fallback()
        return try {
            val obj = Json.parseToJsonElement(cleanMarkdown(raw)).jsonObject
            val recommended = obj.readInt("recommended_minutes") ?: return FocusSuggestion.fallback()
            val rest = obj.readInt("rest_minutes") ?: return FocusSuggestion.fallback()
            val fatigue = obj.readInt("fatigue_index") ?: return FocusSuggestion.fallback()
            val advice = (obj["advice"] as? JsonPrimitive)?.content?.trim().orEmpty()
            // 后端强校验：模型说什么都不越界
            FocusSuggestion(
                recommendedMinutes = recommended.coerceIn(15, 45),
                restMinutes = rest.coerceIn(3, 30),
                fatigueIndex = fatigue.coerceIn(0, 100),
                advice = advice.take(200).ifBlank { FocusSuggestion.DEFAULT.advice },
                source = "ai",
                // visual_config 缺失 → 整块给默认值；字段级再做白名单 + coerceIn 修正
                visualConfig = parseVisualConfig(obj),
            )
        } catch (t: Throwable) {
            FocusSuggestion.fallback()
        }
    }

    /**
     * 解析 AI 返回的 visual_config：
     * 1) 缺失或不是对象 → 直接给默认值（DARK_BLUE_SOLID / 15 / 20），绝不让整条推荐失败；
     * 2) bg_style 不在白名单 → 回落默认纯色（防止 AI 编造样式让前端渲染崩）；
     * 3) blur_radius 强制 coerceIn(0,20)、darkness_overlay 强制 coerceIn(10,30)；
     * 4) 单个字段缺失/非法 → 该字段单独回落默认，其余字段照常用。
     */
    private fun parseVisualConfig(root: JsonObject): FocusVisualConfig {
        val vc = root["visual_config"] as? JsonObject ?: return FocusVisualConfig.DEFAULT
        val d = FocusVisualConfig.DEFAULT
        val rawStyle = (vc["bg_style"] as? JsonPrimitive)?.content?.trim()?.uppercase()
        val style = if (rawStyle in FocusVisualConfig.ALLOWED_STYLES) rawStyle!! else d.bgStyle
        val blur = (vc.readInt("blur_radius") ?: d.blurRadius)
            .coerceIn(FocusVisualConfig.BLUR_MIN, FocusVisualConfig.BLUR_MAX)
        val darkness = (vc.readInt("darkness_overlay") ?: d.darknessOverlay)
            .coerceIn(FocusVisualConfig.DARKNESS_MIN, FocusVisualConfig.DARKNESS_MAX)
        return FocusVisualConfig(style, blur, darkness)
    }

    private fun JsonObject.readInt(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun cleanMarkdown(raw: String): String {
        var t = raw.trim()
        val fenceStart = t.indexOf("```")
        if (fenceStart >= 0) {
            t = t.substring(fenceStart + 3).trimStart()
            val newline = t.indexOf('\n')
            if (newline > 0 && t.substring(0, newline).trim().matches(Regex("[A-Za-z0-9_-]*"))) {
                t = t.substring(newline + 1)
            }
            val fenceEnd = t.lastIndexOf("```")
            if (fenceEnd >= 0) t = t.substring(0, fenceEnd)
        }
        // 模型偶尔在 JSON 前后带解释文字，只保留第一个完整对象
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start >= 0 && end > start) t = t.substring(start, end + 1)
        return t.trim()
    }
}
