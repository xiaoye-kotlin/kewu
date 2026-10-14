package com.kewu.app.permissions

import androidx.compose.runtime.Composable

// 软件需要的前台/录音等权限，统一列在这里，启动后逐步向用户申请
enum class KewuPermission(
    val key: String,
    val title: String,
    val reason: String,
) {
    RecordAudio(
        "android.permission.RECORD_AUDIO",
        "麦克风",
        "课堂录音会用上麦克风，方便 AI 后续帮你整理要点。",
    ),
    ForegroundService(
        "android.permission.FOREGROUND_SERVICE",
        "前台服务",
        "让录音以后台方式稳稳跑着，不容易被系统收掉。",
    ),
    ForegroundMic(
        "android.permission.FOREGROUND_SERVICE_MICROPHONE",
        "麦克风前台服务",
        "Android 14 以上，录音前台服务必须单独声明这一项。",
    ),
    PostNotifications(
        "android.permission.POST_NOTIFICATIONS",
        "通知",
        "录音状态、待办提醒会通过通知告诉你。",
    ),
    WakeLock(
        "android.permission.WAKE_LOCK",
        "保持唤醒",
        "录制时让 CPU 不休眠，断录更稳。",
    ),
    ModifyAudio(
        "android.permission.MODIFY_AUDIO_SETTINGS",
        "音频设置",
        "需要调整音频通路，拿到更干净的录音。",
    ),
    CoarseLocation(
        "android.permission.ACCESS_COARSE_LOCATION",
        "大致位置",
        "部分课堂签到、定位场景会用到。",
    ),
    FineLocation(
        "android.permission.ACCESS_FINE_LOCATION",
        "精确定位",
        "可选：只有需要更精确位置时才用得上。",
    ),
}
