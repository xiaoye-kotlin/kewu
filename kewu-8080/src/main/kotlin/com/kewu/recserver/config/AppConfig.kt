package com.kewu.recserver.config

// 录音子系统配置（原 8081 读 application.conf 的 app.*；并入 8080 后统一走环境变量，见 start-8080-service.bat）
data class AppConfig(
    val storageMode: String,
    val localDir: String,
    val obs: ObsConfig,
    val redis: RedisConfig,
    val sis: SisConfig
)

data class ObsConfig(
    val endpoint: String,
    val bucket: String,
    val ak: String?,
    val sk: String?,
    val region: String
)

data class RedisConfig(
    val host: String,
    val port: Int,
    val password: String?
)

// 2026-11-03：先读总控端写入的云端值，再读环境变量（见 infra/SecretStore）
private fun env(name: String): String? = com.kewu.infra.SecretStore.get(name)

fun loadRecConfig(): AppConfig = AppConfig(
    storageMode = env("STORAGE_MODE") ?: "obs",
    localDir = env("STORAGE_LOCAL_DIR") ?: "./uploads",
    obs = ObsConfig(
        endpoint = env("OBS_ENDPOINT") ?: "https://obs.cn-north-4.myhuaweicloud.com",
        bucket = env("OBS_BUCKET") ?: "kewu2",
        ak = env("OBS_AK"),
        sk = env("OBS_SK"),
        region = env("OBS_REGION") ?: "cn-north-4"
    ),
    redis = RedisConfig(
        host = env("KEWU_REDIS_HOST") ?: "127.0.0.1",
        port = env("KEWU_REDIS_PORT")?.toIntOrNull() ?: 6379,
        password = env("REDIS_PASSWORD")
    ),
    // 华为云 SIS 录音文件识别（2026-10-26 队友交付）：AK/SK 默认复用 OBS 那对；SIS_PROJECT_ID 必填（kewu.env）
    sis = loadSisConfig()
)

/** SIS 配置现读（总控端改了 SIS 密钥后文件转写 / 实时识别立即生效，不用重启）。 */
fun loadSisConfig(): SisConfig = SisConfig(
        region = env("SIS_REGION") ?: env("OBS_REGION") ?: "cn-north-4",
        projectId = env("SIS_PROJECT_ID") ?: "",
        // ★ 2026-10-27：SIS 的公网域名是 sis-ext.{region}，旧默认 sis.{region} 根本没有 DNS 记录，转写一律「网络或超时」
        endpoint = env("SIS_ENDPOINT") ?: "https://sis-ext.${env("SIS_REGION") ?: env("OBS_REGION") ?: "cn-north-4"}.myhuaweicloud.com",
        ak = env("SIS_AK") ?: env("OBS_AK"),
        sk = env("SIS_SK") ?: env("OBS_SK")
    )
