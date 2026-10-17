package com.kewu.app

import io.ktor.client.HttpClient

/** 平台 HTTP 客户端（Android=OkHttp / iOS=Darwin）。 */
expect fun platformHttpClient(): HttpClient

/** 稳定设备标识（录音上传 deviceId 用）。 */
expect fun platformDeviceId(): String

/** 极简键值存储：跨重启保留 token / 用户名。Android=SharedPreferences，iOS=NSUserDefaults。 */
expect fun kvGet(key: String): String?
expect fun kvSet(key: String, value: String?)
