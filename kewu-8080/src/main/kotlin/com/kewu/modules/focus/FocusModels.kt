package com.kewu.modules.focus

import kotlinx.serialization.Serializable

// 专注番茄钟的请求 / 结果模型。前端只传「动作」，所有时间戳都由服务端 System.currentTimeMillis() 生成，
// 这里没有任何 startTime / endTime 入参，避免客户端自己算时间造成前后端对不上。

@Serializable
data class FocusStartRequest(
    val taskId: String? = null,
    val plannedMinutes: Int = 25,
)

@Serializable
data class FocusFinishRequest(
    val taskDifficulty: Int = 3,
    val fatigueLevel: Int = 3,
)

// 一次进行中的专注会话，存在 Redis Hash 里；Redis 不可用时进程内存镜像用同一份结构
data class ActiveFocus(
    val taskId: String?,
    val startTime: Long,
    val plannedMinutes: Int,
    val distractionCount: Int = 0,
)

// focus_records 表对应的一条记录
data class FocusRecord(
    val id: String,
    val userId: String,
    val taskId: String?,
    val startTime: Long,
    val endTime: Long,
    val plannedMinutes: Int,
    val actualMinutes: Int,
    val distractionCount: Int,
    val taskDifficulty: Int,
    val fatigueLevel: Int,
    val status: String,
)

enum class FocusStatus(val value: String) {
    COMPLETED("COMPLETED"),
    CANCELLED("CANCELLED"),
}

// 推荐结果。三个数值是后端强校验后的安全值；source 只用于联调时区分是 AI 给的还是兜底
/**
 * AI 动态专注背景的视觉配置（嵌套在 ai-suggestion 的 visual_config 里）。
 * 三态背景由前端渲染：深灰蓝纯色 / 模糊自然图 / 深蓝到黑渐变。
 * 范围常量在这里定死，后端解析和前端渲染共用同一套语义（前端另做一次 coerce 双保险）。
 */
data class FocusVisualConfig(
    val bgStyle: String,
    val blurRadius: Int,
    val darknessOverlay: Int,
) {
    companion object {
        const val STYLE_DARK_BLUE_SOLID = "DARK_BLUE_SOLID"
        const val STYLE_BLURRED_NATURE = "BLURRED_NATURE"
        const val STYLE_DARK_GRADIENT = "DARK_GRADIENT"

        val ALLOWED_STYLES = setOf(STYLE_DARK_BLUE_SOLID, STYLE_BLURRED_NATURE, STYLE_DARK_GRADIENT)

        const val BLUR_MIN = 0
        const val BLUR_MAX = 20
        const val DARKNESS_MIN = 10
        const val DARKNESS_MAX = 30

        // AI 缺失 / 解析失败时的兜底：深灰蓝纯色（纯色不加 blur，最低端机也零性能开销）
        val DEFAULT = FocusVisualConfig(STYLE_DARK_BLUE_SOLID, 15, 20)
    }
}

data class FocusSuggestion(
    val recommendedMinutes: Int,
    val restMinutes: Int,
    val fatigueIndex: Int,
    val advice: String,
    val source: String,
    val visualConfig: FocusVisualConfig = FocusVisualConfig.DEFAULT,
) {
    companion object {
        val DEFAULT = FocusSuggestion(
            recommendedMinutes = 25,
            restMinutes = 5,
            fatigueIndex = 0,
            advice = "记录还太少，先用经典的 25 分钟专注 + 5 分钟休息开始吧。",
            source = "cold_start",
        )

        // AI 超时 / 解析失败时用同一份默认值，只把来源标成 fallback
        fun fallback() = DEFAULT.copy(source = "fallback")
    }
}

data class FocusStartResult(
    val startTime: Long,
    val plannedMinutes: Int,
    val redisOk: Boolean,
)

data class FocusFinishResult(
    val ignored: Boolean,
    val record: FocusRecord?,
    val restSeconds: Long,
    val redisOk: Boolean,
)

data class FocusCancelResult(val ignored: Boolean, val record: FocusRecord?)

data class FocusStatusResult(
    val active: ActiveFocus?,
    val elapsedSeconds: Long?,
    val restRemainingSeconds: Long?,
)
