package com.kewu.app

import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier

/**
 * 线性马达震动档位。振幅都压得很低——用户要求"不要震得太厉害"，
 * 只是给一点"按到了"的反馈，不是强提醒。
 * - [Tick]：最轻，用于普通点击 / 导航滑行经过某一栏；
 * - [Light]：轻，用于开关 / 选中 / 跳转；
 * - [Medium]：中，用于关键确认（如登录成功变对勾）。
 */
enum class HapticKind { Tick, Light, Medium }

/** 跨平台震动入口；Android 实际实现走 Vibrator（见 Haptics.android.kt）。 */
expect fun haptic(kind: HapticKind)

/**
 * 带线性马达反馈的点击修饰符。封装 [Modifier.clickable]，在 [onClick] 触发前先打一次 [kind] 档震动，
 * 覆盖全 app 的普通点击（课程卡、任务卡、功能行、头像、分段控件等都用它），满足"所有点击都给一点震动"。
 * 玻璃卡（[com.kewu.app.ui.liquidglass.KewuGlassCard]）的点击震动在玻璃组件内统一处理，无需套这个。
 */
fun Modifier.hapticClickable(
    kind: HapticKind = HapticKind.Tick,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = this.clickable(enabled = enabled) {
    haptic(kind)
    onClick()
}
