package com.kewu.app

import io.ktor.client.HttpClient

/** 平台 HTTP 客户端（Android=OkHttp / iOS=Darwin）。 */
expect fun platformHttpClient(): HttpClient

/** 稳定设备标识（录音上传 deviceId 用）。 */
expect fun platformDeviceId(): String

/** 极简键值存储：跨重启保留 token / 用户名。Android=SharedPreferences，iOS=NSUserDefaults。 */
expect fun kvGet(key: String): String?
expect fun kvSet(key: String, value: String?)

/** 应用级 Context（Android=Application Context），用于构造录音器等需要 Context 的组件。 */
expect fun platformAppContext(): Any

/** 当前 Activity（用于运行时权限请求）；未进入界面 / 非 Android 时为 null。 */
expect fun platformActivity(): Any?

/** 可写缓存目录绝对路径（录音临时文件落盘用）。 */
expect fun platformCacheDir(): String

/** 读取本地文件字节（录音 WAV 读出后上传用）。不存在 / 读取失败返回 null。 */
expect fun platformReadFile(path: String): ByteArray?

/** 文件大小（字节），不存在返回 -1（2026-10-26：大文件上传前判断大小，不整读）。 */
expect fun platformFileSize(path: String): Long

/** 以流读取文件：录音 / 附件上传用，避免把上百 MB 的文件整个读进内存。 */
expect fun platformFileChannel(path: String): io.ktor.utils.io.ByteReadChannel

/** 删除本地文件（上传成功后清理缓存副本），失败静默。 */
expect fun platformDeleteFile(path: String)

/** 相机权限申请（扫码签到用）。Android 弹系统授权框；其它平台直接回调 false。 */
expect fun requestCameraPermission(activity: Any, onResult: (Boolean) -> Unit)

/** 通知权限是否已授予（Android 13+ 是运行时权限；无通知权限概念的平台恒 true）。 */
expect fun isNotificationPermissionGranted(): Boolean

/** 申请通知权限（Android 弹系统授权框；其它平台直接回调 false）。 */
expect fun requestNotificationPermission(activity: Any, onResult: (Boolean) -> Unit)

/** 麦克风权限是否已授予（录音用）。 */
expect fun isAudioPermissionGranted(): Boolean

/** 相机权限是否已授予。 */
expect fun isCameraPermissionGranted(): Boolean

/** 跳转本应用的系统设置页（权限管理「去设置」用；不支持的平台 no-op）。 */
expect fun openAppSettings()

/** 系统分享：调起系统分享面板把文本分享到任意 App。Android=ACTION_SEND+Chooser；其它平台 no-op。 */
expect fun shareText(text: String)

/** 退出应用（一级界面双击返回键的第二下）。Android=finish 当前 Activity；iOS 不主动退出（no-op）。 */
expect fun platformExitApp()
