package com.kewu.modules.bilibili

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import kotlin.text.Charsets

// B站抓取：view 拿 cid/标题，wbi/play 拿 DASH 音频 baseUrl，再下载音频字节。
//
// 避坑（对应需求）：
//   - 所有 B站请求必须带 User-Agent + Referer(https://www.bilibili.com/)，否则 412 风控；
//   - playurl 现在强制 WBI 签名（nav 拿 img/sub key → 固定置换算 32 位 mixinKey → 对排序参数 md5）；
//   - 不用 @Serializable 数据类：响应一律 Json.parseToJsonElement 手解；
//   - 任何失败都抛带原因的异常，交给上层记 FAILED，绝不把 500 崩给用户。
class BilibiliFetcher {
    private val log = LoggerFactory.getLogger(BilibiliFetcher::class.java)

    // 标准浏览器头：B站风控（412）就靠它，缺一不可
    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val REFERER = "https://www.bilibili.com/"

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    // ---- WBI 签名：nav 拿 img/sub key，按固定置换表算 32 位 mixinKey，再对排序后的参数 md5 ----
    // 这是 B站官方 wbi 算法的标准置换表
    private val mixinKeyEncTab = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14,
        39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59,
        6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    )

    private data class WbiKeys(val imgKey: String, val subKey: String, val ts: Long)
    private var cached: WbiKeys? = null
    private val mutex = Mutex()

    private suspend fun wbiKeys(): WbiKeys {
        cached?.let { if (System.currentTimeMillis() - it.ts < 10 * 60_000) return it }
        return mutex.withLock {
            cached?.let { if (System.currentTimeMillis() - it.ts < 10 * 60_000) return it }
            val resp = client.get("https://api.bilibili.com/x/web-interface/nav") {
                header("User-Agent", UA)
                header("Referer", REFERER)
            }
            val data = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["data"]?.jsonObject
                ?: throw RuntimeException("B站 nav 接口未返回 data（可能触发风控 412）")
            val wbi = data["wbi_img"]?.jsonObject
                ?: throw RuntimeException("B站 nav 缺少 wbi_img")
            val img = wbi["img_url"]!!.jsonPrimitive.content.substringAfterLast("/").substringBefore(".")
            val sub = wbi["sub_url"]!!.jsonPrimitive.content.substringAfterLast("/").substringBefore(".")
            val k = WbiKeys(img, sub, System.currentTimeMillis())
            cached = k
            log.info("B站 WBI key 已刷新")
            k
        }
    }

    private fun md5Hex(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun wbiSign(params: LinkedHashMap<String, String>, keys: WbiKeys): String {
        val raw = keys.imgKey + keys.subKey
        val mixinKey = mixinKeyEncTab.map { raw[it] }.joinToString("")
        val query = params.toSortedMap().map { "${it.key}=${it.value}" }.joinToString("&")
        return md5Hex(query + mixinKey)
    }

    /** 返回 (视频标题, 音频直链 baseUrl)。任何一步失败都抛带原因的异常。 */
    suspend fun fetchAudio(bvid: String): Pair<String, String> {
        val (title, cid) = view(bvid)
        val baseUrl = playUrl(bvid, cid)
        return title to baseUrl
    }

    private suspend fun view(bvid: String): Pair<String, Long> {
        val resp = client.get("https://api.bilibili.com/x/web-interface/view") {
            header("User-Agent", UA)
            header("Referer", REFERER)
            parameter("bvid", bvid)
        }
        val el = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val code = (el["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1
        if (code != 0) throw RuntimeException("B站 view 接口错误 code=$code ${el["message"]}")
        val data = el["data"]?.jsonObject ?: throw RuntimeException("B站 view 缺少 data")
        val title = data["title"]?.jsonPrimitive?.content ?: ""
        val cid = (data["cid"] as? JsonPrimitive)?.content?.toLongOrNull()
            ?: throw RuntimeException("B站 view 缺少 cid（视频号可能无效）")
        return title to cid
    }

    private suspend fun playUrl(bvid: String, cid: Long): String {
        val keys = wbiKeys()
        val wts = (System.currentTimeMillis() / 1000).toString()
        val params = LinkedHashMap<String, String>().apply {
            put("bvid", bvid)
            put("cid", cid.toString())
            put("qn", "64")
            put("fnval", "16")   // 16 = 请求 DASH 格式（含 audio 数组）
            put("fourk", "1")
            put("wts", wts)
        }
        val wrid = wbiSign(params, keys)
        val resp = client.get("https://api.bilibili.com/x/player/wbi/play") {
            header("User-Agent", UA)
            header("Referer", REFERER)
            params.forEach { (k, v) -> parameter(k, v) }
            parameter("w_rid", wrid)
        }
        val el = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val code = (el["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1
        if (code != 0) throw RuntimeException("B站 playurl 接口错误 code=$code ${el["message"]}")
        val data = el["data"]?.jsonObject ?: throw RuntimeException("B站 playurl 缺少 data")
        // 优先取 DASH 音频
        val dash = data["dash"]?.jsonObject
        if (dash != null) {
            val audio = dash["audio"]?.let { it as? JsonArray }
            val first = audio?.firstOrNull()?.jsonObject
            val base = first?.get("baseUrl")?.jsonPrimitive?.content
            if (!base.isNullOrBlank()) return base
        }
        // 兜底：老接口走 durl（FLV 直链）
        val durl = data["durl"]?.let { it as? JsonArray }
        val durlUrl = durl?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
        if (!durlUrl.isNullOrBlank()) return durlUrl
        throw RuntimeException("B站 playurl 未解析到音频地址（dash/durl 均空）")
    }

    /** 下载音频字节（带 UA + Referer + Range + 跟随重定向，否则 403/412）。 */
    suspend fun downloadAudio(url: String): ByteArray {
        val req = java.net.http.HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("User-Agent", UA)
            .header("Referer", REFERER)
            .header("Range", "bytes=0-")
            .GET()
            .build()
        val cl = java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)   // 音频直链会 302 跳 CDN
            .build()
        val resp = cl.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray())
        val v = resp.statusCode()
        if (v !in 200..299 && v != 206) {
            throw RuntimeException("B站音频下载失败 HTTP $v")
        }
        return resp.body()
    }

    fun close() = runCatching { client.close() }
}
