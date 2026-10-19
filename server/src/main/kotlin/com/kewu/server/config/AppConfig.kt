package com.kewu.server.config

import io.ktor.server.application.Application
import io.ktor.server.config.ApplicationConfig

// 把 application.conf 里的 app.* 读进来，OBS 的 ak/sk 优先走环境变量不落代码
data class AppConfig(
    val storageMode: String,
    val localDir: String,
    val obs: ObsConfig,
    val redis: RedisConfig
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

fun Application.loadConfig(): AppConfig {
    val app = environment.config.config("app")
    val storage = app.config("storage")
    val obs = app.config("obs")
    val redis = app.config("redis")
    return AppConfig(
        storageMode = storage.property("mode").getString(),
        localDir = storage.property("localDir").getString(),
        obs = ObsConfig(
            endpoint = obs.property("endpoint").getString(),
            bucket = obs.property("bucket").getString(),
            ak = obs.propertyOrNull("ak")?.getString(),
            sk = obs.propertyOrNull("sk")?.getString(),
            region = obs.property("region").getString()
        ),
        redis = RedisConfig(
            host = redis.property("host").getString(),
            port = redis.property("port").getString().toInt(),
            password = redis.propertyOrNull("password")?.getString()
        )
    )
}
