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
data class FocusSuggestion(
    val recommendedMinutes: Int,
    val restMinutes: Int,
    val fatigueIndex: Int,
    val advice: String,
    val source: String,
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
