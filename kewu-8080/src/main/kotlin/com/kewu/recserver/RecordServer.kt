package com.kewu.recserver

import com.kewu.recserver.config.loadRecConfig
import com.kewu.recserver.routes.aiRoutes
import com.kewu.recserver.routes.classroomRoutes
import com.kewu.recserver.routes.healthRoutes
import com.kewu.recserver.routes.recordingRoutes
import com.kewu.recserver.services.AudioAnalyzer
import com.kewu.recserver.services.AudioFusion
import com.kewu.recserver.services.QualityScorer
import com.kewu.recserver.services.RecordingDeviceService
import com.kewu.recserver.services.RedisService
import com.kewu.recserver.services.ai.AiService
import com.kewu.recserver.services.ai.MaasAiService
import com.kewu.recserver.services.storage.AudioStorage
import com.kewu.recserver.services.storage.LocalStorageService
import com.kewu.recserver.services.storage.ObsStorageService
import io.ktor.server.application.Application
import io.ktor.server.routing.Route
import io.ktor.util.AttributeKey

// 录音 / 课堂缓存 / AI 子系统（原 8081 独立服务，2026-10-26 并入 8080，对外只剩一个端口）。
// 接口路径与响应格式保持原样（不套 {code,message,data} 壳），老客户端只需把地址从 8081 换成 8080。
class RecServices(
    val storage: AudioStorage,
    val redis: RedisService,
    val scorer: QualityScorer,
    val fusion: AudioFusion,
    val analyzer: AudioAnalyzer,
    val ai: AiService,
    val deviceMonitor: RecordingDeviceService
)

val RecServicesKey = AttributeKey<RecServices>("kewu.rec.services")

// ★ 必须在 configureRouting() 之前调用：各路由在注册时就从 attributes 取服务，
//   顺序反了启动即崩（No instance for key AttributeKey: kewu.rec.services，2026-10-26 15:07 线上事故）
fun Application.installRecServices() {
    val config = loadRecConfig()
    val storage: AudioStorage = if (config.storageMode == "obs") {
        ObsStorageService(config.obs)
    } else {
        LocalStorageService(config.localDir)
    }
    val redis = RedisService(config.redis)
    attributes.put(
        RecServicesKey,
        RecServices(
            storage = storage,
            redis = redis,
            scorer = QualityScorer(),
            fusion = AudioFusion(),
            analyzer = AudioAnalyzer(),
            ai = MaasAiService(),
            deviceMonitor = RecordingDeviceService(redis)
        )
    )
}

// 在 routing { } 里调用，挂上原 8081 的全部接口
fun Route.recordingModule() {
    healthRoutes()
    recordingRoutes()
    classroomRoutes()
    aiRoutes()
}
