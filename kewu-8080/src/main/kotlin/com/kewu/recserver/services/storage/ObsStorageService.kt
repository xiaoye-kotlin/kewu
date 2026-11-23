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

    /**
     * OBS user metadata 只接受 US-ASCII 可打印字符。华为 OBS SDK 对非 ASCII 值会直接抛
     * `IllegalArgumentException: Unexpected char 0xXXXX in x-obs-meta-... value`（上传 500）。
     * 实际踩坑（2026-11-24）：segGroup 里带了桌面设备名 `desktop-Admin（无密码）` 的全角括号与中文，
     * 导致队员录音一直 500、App 端无限重试停在「上传中」。这里对 value 做 percent-encode，
     * 保证任意 Unicode 都能安全写入；metadata 仅作附加信息，真实分组键在 Redis 记账里独立保存，
     * 不改变语义，也无需读取侧解码。
     */
    private fun asciiSafe(value: String): String = buildString {
        val bytes = value.toByteArray(Charsets.UTF_8)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            // OBS 允许的 header 值：可打印 ASCII 0x20-0x7E，排除分隔/控制相关字符
            if (c in 0x20..0x7E && c != 0x7F) append(c.toChar())
            else append('%').append(c.toString(16).uppercase().padStart(2, '0'))
        }
    }

    override suspend fun save(recordingId: String, bytes: ByteArray, ext: String, metadata: Map<String, String>): String {
        val key = "recordings/$recordingId.$ext"
        val obsMeta = ObjectMetadata().apply { metadata.forEach { (k, v) -> addUserMetadata(k, asciiSafe(v)) } }
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
