package com.kewu.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/** 由 [MainActivity.onCreate] 注入，供 `actual fun haptic` 取 Vibrator。 */
internal lateinit var appContext: Context

actual fun haptic(kind: HapticKind) {
    // 震动只是"锦上添花"的反馈：任何异常（缺权限 / 无马达 / 服务拒绝）都绝不能拖垮 app。
    try {
        val ctx = appContext
        val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !vibrator.hasVibrator()) return
        // 时长方、振幅低：线性马达一点脆响即可，不喧宾夺主
        val (ms, amp) = when (kind) {
            HapticKind.Tick -> 7L to 35
            HapticKind.Light -> 14L to 55
            HapticKind.Medium -> 22L to 95
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(ms, amp))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(ms)
        }
    } catch (_: Exception) {
        // 缺 VIBRATE 权限 / 服务不可用等：静默降级，不影响交互
    }
}
