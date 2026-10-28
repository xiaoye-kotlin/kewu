package com.kewu.recserver.config

// SIS（华为云语音交互服务）配置：全部走环境变量，密钥不写死在代码里。
// ak/sk 默认复用 OBS 的（同一华为云账号），也可以单独用 SIS_AK/SIS_SK 覆盖。
data class SisConfig(
    val region: String,
    val projectId: String,
    val endpoint: String,
    val ak: String?,
    val sk: String?
)
