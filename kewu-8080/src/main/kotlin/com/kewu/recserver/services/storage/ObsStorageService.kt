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

    fun objectKey(recordingId: String, ext: String) = "recordings/$recordingId.$ext"

    fun publicUrl(key: String): String {
        val base = cfg.endpoint.removePrefix("https://").removePrefix("http://").removeSuffix("/")
        return "https://${cfg.bucket}.$base/$key"
    }

    /** 预签名 PUT 直传地址（15 分钟有效）：兼容旧版 /api/recordings/presign 链路。 */
    fun presignPut(recordingId: String, ext: String, expiresSec: Long = 900): Pair<String, String> {
        val key = objectKey(recordingId, ext)
        val req = com.obs.services.model.TemporarySignatureRequest(
            com.obs.services.model.HttpMethodEnum.PUT, expiresSec
        ).apply {
            bucketName = cfg.bucket
            objectKey = key
        }
        return client.createTemporarySignature(req).signedUrl to publicUrl(key)
    }

    override suspend fun save(recordingId: String, bytes: ByteArray, ext: String, metadata: Map<String, String>): String {
        val key = "recordings/$recordingId.$ext"
        val obsMeta = ObjectMetadata().apply { metadata.forEach { (k, v) -> addUserMetadata(k, v) } }
        withContext(Dispatchers.IO) { client.putObject(cfg.bucket, key, ByteArrayInputStream(bytes), obsMeta) }
        val base = cfg.endpoint.removePrefix("https://").removePrefix("http://").removeSuffix("/")
        return "https://${cfg.bucket}.$base/$key"
    }

    override suspend fun saveObject(key: String, bytes: ByteArray, contentType: String): String {
        val obsMeta = ObjectMetadata().apply { this.contentType = contentType; contentLength = bytes.size.toLong() }
        withContext(Dispatchers.IO) { client.putObject(cfg.bucket, key, ByteArrayInputStream(bytes), obsMeta) }
        val base = cfg.endpoint.removePrefix("https://").removePrefix("http://").removeSuffix("/")
        return "https://${cfg.bucket}.$base/$key"
    }

    override suspend fun get(url: String): ByteArray? {
        // 2026-11-04：兼容三种形态——obs://key、https://bucket.endpoint/key（save 返回的）、裸 key
        val key = when {
            url.startsWith("obs://") -> url.removePrefix("obs://")
            url.startsWith("http://") || url.startsWith("https://") ->
                runCatching { java.net.URI(url).path }.getOrNull()?.removePrefix("/") ?: return null
            else -> url
        }
        return withContext(Dispatchers.IO) {
            runCatching { client.getObject(cfg.bucket, key).objectContent.readBytes() }.getOrNull()
        }
    }
}
