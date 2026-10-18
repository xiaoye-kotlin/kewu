package com.kewu.server

import com.kewu.server.config.loadConfig
import com.kewu.server.plugins.configureHttp
import com.kewu.server.plugins.configureMonitoring
import com.kewu.server.plugins.configureRouting
import com.kewu.server.plugins.configureSerialization
import com.kewu.server.plugins.configureStatusPages
import com.kewu.server.services.ai.AiService
import com.kewu.server.services.ai.NoOpAiService
import com.kewu.server.services.AudioAnalyzer
import com.kewu.server.services.AudioFusion
import com.kewu.server.services.QualityScorer
import com.kewu.server.services.RedisService
import com.kewu.server.services.storage.AudioStorage
import com.kewu.server.services.storage.LocalStorageService
import com.kewu.server.services.storage.ObsStorageService
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.netty.EngineMain
import io.ktor.util.AttributeKey

// 后端入口：用 EngineMain 读 application.conf 起 Netty，module() 里把各块拼起来
fun main(args: Array<String>): Unit = EngineMain.main(args)

fun Application.module() {
    val config = loadConfig()
    installServices(config)
    configureSerialization()
    configureMonitoring()
    configureStatusPages()
    configureHttp()
    configureRouting()
}

// 把几个核心服务攒成一个对象塞进 Application，路由里随手就能拿出来用
class Services(
    val storage: AudioStorage,
    val redis: RedisService,
    val scorer: QualityScorer,
    val fusion: AudioFusion,
    val analyzer: AudioAnalyzer,
    val ai: AiService
)

val ServicesKey = AttributeKey<Services>("kewu.services")

// 按配置选存储方式：没配 OBS 就先用本地磁盘，部署时切 obs 即可
fun Application.installServices(config: com.kewu.server.config.AppConfig) {
    val storage: AudioStorage = if (config.storageMode == "obs") {
        ObsStorageService(config.obs)
    } else {
        LocalStorageService(config.localDir)
    }
    attributes.put(
        ServicesKey,
        Services(
            storage = storage,
            redis = RedisService(config.redis),
            scorer = QualityScorer(),
            fusion = AudioFusion(),
            analyzer = AudioAnalyzer(),
            ai = NoOpAiService()
        )
    )
}
