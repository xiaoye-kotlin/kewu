package com.kewu.infra

import com.kewu.db.SystemConfigs
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// 云端服务配置（2026-11-03 用户：华为云 SIS / OBS / AI 的所有 Key 都在总控端填写、存在云端，App 端不放任何密钥）。
//
// 取值顺序：总控端写入的云端值（system_config 表，AES-GCM 加密）→ 服务器环境变量（kewu.env）→ 内置默认值。
// 读的地方统一走 [get]；表里的值启动时整表载入内存，总控端保存后立即刷新缓存。
// - hot（restart = false）：每次调用现读，保存即生效（AI、SIS 实时识别）；
// - restart = true：对应客户端在启动时构建（OBS / Redis / SIS 文件转写），需要重启后端。
// 加密密钥由 KEWU_CONFIG_KEY（没有就用 JWT_SECRET）派生；换了它旧值解不开，会回落到环境变量。
// 数据库连接串 / 口令、ADMIN_KEY、JWT_SECRET 本身不能放这里（鸡生蛋），仍只在 kewu.env。
object SecretStore {

    data class Def(
        val key: String,
        val group: String,
        val label: String,
        val secret: Boolean,
        val restart: Boolean,
        val hint: String = "",
        val default: String? = null,
    )

    val defs: List<Def> = listOf(
        Def("MAAS_API_URL", "AI", "接口地址", false, false, "OpenAI 兼容 chat/completions 完整地址"),
        Def("MAAS_API_KEY", "AI", "API Key", true, false, "大模型服务的密钥"),
        Def("MAAS_MODEL_NAME", "AI", "模型名", false, false, "例如 DeepSeek-V4.1-Flash-1017"),
        Def("SIS_PROJECT_ID", "SIS", "项目 ID", false, false, "华为云控制台「我的凭证」里该区域的项目 ID"),
        Def("SIS_REGION", "SIS", "区域", false, false, "", "cn-north-4"),
        Def("SIS_ENDPOINT", "SIS", "接口域名", false, false, "留空自动用 https://sis-ext.{区域}.myhuaweicloud.com"),
        Def("SIS_AK", "SIS", "Access Key", true, false, "留空复用 OBS 的 AK"),
        Def("SIS_SK", "SIS", "Secret Key", true, false, "留空复用 OBS 的 SK"),
        Def("SIS_RASR_PROPERTY", "SIS", "实时识别模型", false, false, "App 语音输入用的实时识别模型", "chinese_16k_general"),
        Def("OBS_ENDPOINT", "OBS", "接口地址", false, true, "", "https://obs.cn-north-4.myhuaweicloud.com"),
        Def("OBS_BUCKET", "OBS", "桶名", false, true, "", "kewu2"),
        Def("OBS_REGION", "OBS", "区域", false, true, "", "cn-north-4"),
        Def("OBS_AK", "OBS", "Access Key", true, true),
        Def("OBS_SK", "OBS", "Secret Key", true, true),
        Def("KEWU_REDIS_HOST", "REDIS", "地址", false, true, "", "127.0.0.1"),
        Def("KEWU_REDIS_PORT", "REDIS", "端口", false, true, "", "6379"),
        Def("REDIS_PASSWORD", "REDIS", "密码", true, true),
        Def("KEWU_ASR_MAX", "OTHER", "转写并发上限", false, true, "下课后同时转写的录音数，1–60", "12"),
    )
    private val defByKey = defs.associateBy { it.key }

    private val cache = ConcurrentHashMap<String, String>()

    @kotlinx.serialization.Serializable
    data class TestResult(val ok: Boolean, val message: String)

    @kotlinx.serialization.Serializable
    data class Item(
        val key: String, val group: String, val label: String, val secret: Boolean,
        val value: String, val source: String, val restart: Boolean, val hint: String,
    )

    /** 当前生效值（云端 → 环境变量 → 默认），空串视为未设置。 */
    fun get(name: String): String? =
        cache[name]?.takeIf { it.isNotBlank() }
            ?: System.getenv(name)?.trim()?.takeIf { it.isNotBlank() }
            ?: defByKey[name]?.default

    /** 启动时（数据库连上后）整表载入；解不开的值跳过（回落环境变量）。 */
    fun load() {
        runCatching {
            val rows = transaction { SystemConfigs.selectAll().map { it[SystemConfigs.cfgKey] to it[SystemConfigs.cfgValue] } }
            cache.clear()
            rows.forEach { (k, v) -> decrypt(v)?.let { cache[k] = it } }
            println("SECRET-STORE: loaded ${cache.size} cloud config value(s)")
        }.onFailure { println("SECRET-STORE-ERROR: ${it.message}") }
    }

    /** 总控端保存：空值 = 删除云端值。只接受已登记的键。返回实际改动的键。 */
    fun set(items: Map<String, String>): List<String> {
        val changed = mutableListOf<String>()
        val now = Instant.now().toString()
        transaction {
            items.forEach { (k, raw) ->
                if (k !in defByKey) return@forEach
                val v = raw.trim()
                if (v.isEmpty()) {
                    SystemConfigs.deleteWhere { SystemConfigs.cfgKey eq k }
                    if (cache.remove(k) != null) changed += k
                } else if (cache[k] != v) {
                    SystemConfigs.upsert {
                        it[cfgKey] = k
                        it[cfgValue] = encrypt(v)
                        it[updatedAt] = now
                    }
                    cache[k] = v
                    changed += k
                }
            }
        }
        return changed
    }

    fun isRestartKey(key: String): Boolean = defByKey[key]?.restart == true

    /** 给总控端看的清单：密钥只给掩码，绝不回明文。 */
    fun items(): List<Item> = defs.map { d ->
        val cloud = cache[d.key]?.takeIf { it.isNotBlank() }
        val env = System.getenv(d.key)?.trim()?.takeIf { it.isNotBlank() }
        val (value, source) = when {
            cloud != null -> cloud to "db"
            env != null -> env to "env"
            d.default != null -> d.default to "default"
            else -> "" to "unset"
        }
        Item(d.key, d.group, d.label, d.secret, if (d.secret) mask(value) else value, source, d.restart, d.hint)
    }

    private fun mask(v: String): String = when {
        v.isEmpty() -> ""
        v.length <= 6 -> "***"
        else -> v.take(3) + "***" + v.takeLast(2)
    }

    // ── AES-GCM ──
    private val aesKey: SecretKeySpec by lazy {
        val seed = System.getenv("KEWU_CONFIG_KEY")?.takeIf { it.isNotBlank() }
            ?: System.getenv("JWT_SECRET")?.takeIf { it.isNotBlank() }
            ?: "kewu-dev-secret-change-me"
        SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(("kewu-config:" + seed).toByteArray()), "AES")
    }
    private val rnd = SecureRandom()

    private fun encrypt(plain: String): String {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        return "v1:" + Base64.getEncoder().encodeToString(iv + c.doFinal(plain.toByteArray()))
    }

    private fun decrypt(stored: String): String? = runCatching {
        if (!stored.startsWith("v1:")) return@runCatching null
        val all = Base64.getDecoder().decode(stored.removePrefix("v1:"))
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, all.copyOfRange(0, 12)))
        String(c.doFinal(all.copyOfRange(12, all.size)))
    }.getOrNull()
}
