package com.kewu.recserver.services.storage

import com.kewu.recserver.config.ObsConfig
import com.obs.services.ObsClient
import com.obs.services.ObsConfiguration
import com.obs.services.model.ObjectMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

// 华为云 OBS 存储：生产环境把录音存对象存储，ak/sk 运行时从环境变量拿，不写死在代码里
class ObsStorageService(private val cfg: ObsConfig) : AudioStorage {
    private val client by lazy {
        val config = ObsConfiguration().apply { endPoint = cfg.endpoint }
        ObsClient(cfg.ak ?: "", cfg.sk ?: "", config)
    }

    override suspend fun save(recordingId: String, bytes: ByteArray, ext: String, metadata: Map<String, String>): String {
        val key = "recordings/$recordingId.$ext"
        val obsMeta = ObjectMetadata().apply { metadata.forEach { (k, v) -> addUserMetadata(k, v) } }
        withContext(Dispatchers.IO) { client.putObject(cfg.bucket, key, ByteArrayInputStream(bytes), obsMeta) }
        val base = cfg.endpoint.removePrefix("https://").removePrefix("http://").removeSuffix("/")
        return "https://${cfg.bucket}.$base/$key"
    }

    override suspend fun get(url: String): ByteArray? {
        val key = url.removePrefix("obs://")
        return withContext(Dispatchers.IO) {
            runCatching { client.getObject(cfg.bucket, key).objectContent.readBytes() }.getOrNull()
        }
    }
}
