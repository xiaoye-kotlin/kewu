package com.kewu.admin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.StringReader
import java.net.URLEncoder
import java.text.NumberFormat
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

val AdminJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
}

fun JsonObject?.str(key: String, fallback: String = "—"): String {
    val value = this?.get(key) as? JsonPrimitive ?: return fallback
    if (value is JsonNull) return fallback
    return value.contentOrNull?.takeIf { it.isNotBlank() } ?: fallback
}

fun JsonObject?.long(key: String, fallback: Long = 0L): Long {
    val primitive = this?.get(key) as? JsonPrimitive ?: return fallback
    return primitive.longOrNull ?: primitive.content.toLongOrNull()
        ?: primitive.doubleOrNull?.toLong() ?: fallback
}

fun JsonObject?.double(key: String, fallback: Double = 0.0): Double {
    val primitive = this?.get(key) as? JsonPrimitive ?: return fallback
    return primitive.doubleOrNull ?: primitive.content.toDoubleOrNull() ?: fallback
}

fun JsonObject?.bool(key: String, fallback: Boolean = false): Boolean {
    val primitive = this?.get(key) as? JsonPrimitive ?: return fallback
    return primitive.booleanOrNull ?: primitive.content.equals("true", true) || fallback
}

fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject

fun JsonObject?.array(key: String): List<JsonObject> =
    (this?.get(key) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

fun JsonElement?.asObjectList(): List<JsonObject> = when (this) {
    is JsonArray -> mapNotNull { it as? JsonObject }
    is JsonObject -> (this["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    else -> emptyList()
}

fun formatCount(value: Long): String = NumberFormat.getIntegerInstance(Locale.CHINA).format(value)

fun formatBytes(value: Long): String {
    if (value <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var size = value.toDouble()
    var index = 0
    while (size >= 1024.0 && index < units.lastIndex) {
        size /= 1024.0
        index++
    }
    return if (index == 0) "${size.toLong()} ${units[index]}" else "%.1f %s".format(size, units[index])
}

fun formatPercent(value: Double): String = "${(value * 100).coerceIn(0.0, 100.0).toInt()}%"

fun roleLabel(role: String): String = when (role.uppercase()) {
    "ADMIN" -> "管理员"
    "TEACHER" -> "教师"
    "STUDENT" -> "学生"
    else -> role.ifBlank { "未知" }
}

fun statusLabel(status: String): String = when (status.uppercase()) {
    "ACTIVE" -> "正常"
    "DISABLED" -> "已禁用"
    "PENDING" -> "待批阅"
    "REVIEWED" -> "已批阅"
    "RETURNED" -> "已打回"
    "OPEN" -> "进行中"
    "CLOSED" -> "已结束"
    else -> status.ifBlank { "未知" }
}

data class PageResult(
    val items: List<JsonObject>,
    val total: Int,
    val page: Int,
    val size: Int,
)

data class AdminSession(
    val token: String,
    val username: String,
    val nickname: String,
)

data class ObsListingPage(
    val items: List<JsonObject>,
    val truncated: Boolean,
    val nextMarker: String,
)

/** 解析 OBS ListBucketResult XML。当前桶策略允许匿名读/列录音对象，无需把 AK/SK 放进总控端。 */
fun parseObsListing(xml: String, bucketUrl: String): ObsListingPage {
    return try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val contents = document.getElementsByTagName("Contents")
        val items = (0 until contents.length).mapNotNull { index ->
            val node = contents.item(index) as? org.w3c.dom.Element ?: return@mapNotNull null
            val key = node.getElementsByTagName("Key").item(0)?.textContent.orEmpty()
            if (key.isBlank()) return@mapNotNull null
            val id = key.substringAfterLast('/').substringBeforeLast('.')
            val lastModified = node.getElementsByTagName("LastModified").item(0)?.textContent.orEmpty()
            val size = node.getElementsByTagName("Size").item(0)?.textContent?.toLongOrNull() ?: 0L
            val format = key.substringAfterLast('.', "")
            buildJsonObject {
                put("id", id)
                put("obsKey", key)
                put("url", obsObjectUrl(bucketUrl, key))
                put("sizeBytes", size)
                put("lastModified", lastModified)
                put("createdAt", lastModified)
                put("format", format)
                put("source", "OBS")
            }
        }
        val truncated = document.getElementsByTagName("IsTruncated").item(0)?.textContent.equals("true", true)
        val next = document.getElementsByTagName("NextMarker").item(0)?.textContent.orEmpty()
        ObsListingPage(items, truncated, next)
    } catch (e: Exception) {
        throw AdminApiException("OBS 录音列表解析失败，请稍后重试")
    }
}

private fun obsObjectUrl(bucketUrl: String, key: String): String {
    val base = bucketUrl.trim().removeSuffix("/")
    val encodedKey = key.split('/').joinToString("/") {
        URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20")
    }
    return "$base/$encodedKey"
}

// ───────────────────────── 密钥与服务配置（/api/admin/config） ─────────────────────────

/**
 * 一条云端配置项。[value] 对 secret 项是后端打码后的值（如 `AKx***9Q`），永远拿不到明文；
 * [source]：db = 云端覆盖值，env = 服务器环境变量，default = 内置默认，unset = 未设置。
 * [restart] = true 表示修改后需重启后端才生效，false = 即时生效。
 */
data class ConfigItem(
    val key: String,
    val group: String,
    val label: String,
    val secret: Boolean,
    val value: String,
    val source: String,
    val restart: Boolean,
    val hint: String,
)

data class ConfigTestResult(val ok: Boolean, val message: String)

private fun JsonObject.flag(key: String, fallback: Boolean): Boolean {
    val primitive = this[key] as? JsonPrimitive ?: return fallback
    if (primitive is JsonNull) return fallback
    return primitive.booleanOrNull ?: when (primitive.content.lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> fallback
    }
}

fun parseConfigItems(data: JsonElement?): List<ConfigItem> = data.asObjectList().mapNotNull { o ->
    val key = o.str("key", "")
    if (key.isBlank()) return@mapNotNull null
    ConfigItem(
        key = key,
        group = o.str("group", "OTHER").uppercase(),
        label = o.str("label", key),
        secret = o.flag("secret", false),
        value = o.str("value", ""),
        source = o.str("source", "unset").lowercase(),
        // 字段缺失时按「需重启」处理，宁可多提示一次
        restart = o.flag("restart", true),
        hint = o.str("hint", ""),
    )
}

fun parseConfigTestResult(data: JsonElement?): ConfigTestResult {
    val o = data as? JsonObject ?: return ConfigTestResult(false, "测试响应格式异常")
    val ok = o.flag("ok", false)
    return ConfigTestResult(ok, o.str("message", if (ok) "连接正常" else "连接失败"))
}

/** 分组中文名（未知分组原样显示）。 */
fun configGroupLabel(group: String): String = when (group.uppercase()) {
    "AI" -> "AI 大模型"
    "SIS" -> "华为云 SIS 语音识别"
    "OBS" -> "华为云 OBS 存储"
    "REDIS" -> "Redis"
    "OTHER" -> "其他"
    else -> group
}

/** 支持「测试连接」的分组。 */
fun configGroupTestable(group: String): Boolean = group.uppercase() in setOf("AI", "SIS", "OBS")

fun configSourceLabel(source: String): String = when (source.lowercase()) {
    "db" -> "云端配置"
    "env" -> "服务器环境变量"
    "default" -> "内置默认值"
    "unset" -> "未设置"
    else -> source
}
