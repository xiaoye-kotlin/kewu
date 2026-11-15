package com.kewu.modules.bilibili

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
        val info = viewInfo(bvid)
        // 2026-11-02：直接走 wbi/play 在服务器 IP 上会被风控返回 HTML（JSON 解析报 Unexpected token），
        // 改为与搜索列表同一路径：playurl 优先、WBI 兜底；都拿不到 DASH 时再试老的 durl 解析
        val audio = runCatching {
            try { fetchDashUrlsByPlayurl(bvid, info.cid).second } catch (e: Throwable) {
                log.warn("B站 playurl 取音频失败，尝试 WBI 兜底 bvid=$bvid：${e.message}")
                fetchDashUrlsByWbi(bvid, info.cid).second
            }
        }.getOrElse { playUrl(bvid, info.cid) }
        return info.title to audio
    }

    /** view 接口解析结果：基础信息 + 分P 列表 + 所属 UGC 合集（2026-11-04，合集/分P 分集展示用）。 */
    data class BiliViewData(
        val title: String,
        val cover: String,
        /** pages[0].cid（多 P 视频默认第一集），老视频兜底 data.cid */
        val cid: Long,
        /** 分P 列表（仅 >1 P 时非空）：同 bvid 不同 cid */
        val pages: List<BiliEpisode>,
        /** 所属合集 id；不属于任何合集为 0 */
        val seasonId: Long,
        val seasonTitle: String,
        val seasonCover: String,
        /** 合集分集（各集 bvid 不同）；无合集为空 */
        val seasonEpisodes: List<BiliEpisode>,
    )

    suspend fun viewInfo(bvid: String): BiliViewData {
        val resp = client.get("https://api.bilibili.com/x/web-interface/view") {
            header("User-Agent", UA)
            header("Referer", REFERER)
            header("X-Bili-Api-Source", "pc")
            header("Cookie", buvidCookie())
            parameter("bvid", bvid)
        }
        val el = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val code = (el["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1
        if (code != 0) throw RuntimeException("B站 view 接口错误 code=$code ${el["message"]}")
        val data = el["data"]?.jsonObject ?: throw RuntimeException("B站 view 缺少 data")
        val title = data["title"]?.jsonPrimitive?.content ?: ""
        val cover = normalizeCover(data["pic"]?.jsonPrimitive?.content.orEmpty())
        // 分P：pages[].duration 为秒（int）
        val pages = (data["pages"] as? JsonArray)?.mapNotNull { p ->
            (p as? JsonObject)?.let { o ->
                val pcid = o["cid"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@let null
                BiliEpisode(bvid, pcid, o["part"]?.jsonPrimitive?.content ?: "", fmtSeconds(o["duration"]))
            }
        }.orEmpty()
        val cid = pages.firstOrNull()?.cid
            ?: (data["cid"] as? JsonPrimitive)?.content?.toLongOrNull()
            ?: throw RuntimeException("B站 view 缺少 cid（视频号可能无效）")
        // UGC 合集：ugc_season.sections[].episodes[]，每集有独立 bvid/cid；duration 也是秒
        val us = data["ugc_season"] as? JsonObject
        val seasonEps = us?.let { s ->
            (s["sections"] as? JsonArray)?.flatMap { sec ->
                ((sec as? JsonObject)?.get("episodes") as? JsonArray).orEmpty()
            }?.mapNotNull { e ->
                (e as? JsonObject)?.let { o ->
                    val ebv = o["bvid"]?.jsonPrimitive?.content?.trim().orEmpty()
                    val ecid = o["cid"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@let null
                    if (ebv.isEmpty()) return@let null
                    BiliEpisode(ebv, ecid, o["title"]?.jsonPrimitive?.content ?: "", fmtSeconds(o["duration"]))
                }
            }.orEmpty().distinctBy { it.bvid }
        }.orEmpty()
        return BiliViewData(
            title = title, cover = cover, cid = cid,
            pages = if (pages.size > 1) pages else emptyList(),
            seasonId = us?.get("id")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            seasonTitle = us?.get("title")?.jsonPrimitive?.content ?: "",
            seasonCover = normalizeCover(us?.get("cover")?.jsonPrimitive?.content.orEmpty()),
            seasonEpisodes = seasonEps.take(100),
        )
    }

    /** 秒（int / 字符串）→ m:ss / h:mm:ss；拿不到返回空串。 */
    private fun fmtSeconds(el: kotlinx.serialization.json.JsonElement?): String {
        val total = (el as? JsonPrimitive)?.content?.toIntOrNull() ?: return ""
        if (total <= 0) return ""
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
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
            header("X-Bili-Api-Source", "pc")
            header("Cookie", buvidCookie())
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

    /** 按指定 cid（某分P / 合集某集）取 DASH 视频/音频直链：playurl 优先、WBI 兜底。 */
    suspend fun dashUrlsFor(bvid: String, cid: Long): Pair<String, String> = try {
        fetchDashUrlsByPlayurl(bvid, cid)
    } catch (e: Throwable) {
        // 有些网络/IP 下 playurl 会被拦，退回已验证过的 WBI 播放接口再试一次
        log.warn("B站 playurl 直链失败，尝试 WBI 兜底 bvid=$bvid：${e.message}")
        fetchDashUrlsByWbi(bvid, cid)
    }

    private suspend fun fetchDashUrlsByPlayurl(bvid: String, cid: Long): Pair<String, String> {
        val resp = client.get("https://api.bilibili.com/x/player/playurl") {
            header("User-Agent", UA)
            header("Referer", REFERER)
            header("X-Bili-Api-Source", "pc")
            header("Cookie", buvidCookie())
            parameter("bvid", bvid)
            parameter("cid", cid.toString())
            parameter("fnval", "16")   // DASH 格式
            parameter("fourk", "1")
        }
        val el = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val code = (el["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1
        if (code != 0) throw RuntimeException("B站 playurl 接口错误 code=$code ${el["message"]}")
        val data = el["data"]?.jsonObject ?: throw RuntimeException("B站 playurl 缺少 data")
        return extractDashUrls(data)
    }

    private suspend fun fetchDashUrlsByWbi(bvid: String, cid: Long): Pair<String, String> {
        val keys = wbiKeys()
        val wts = (System.currentTimeMillis() / 1000).toString()
        val params = LinkedHashMap<String, String>().apply {
            put("bvid", bvid)
            put("cid", cid.toString())
            put("qn", "64")
            put("fnval", "16")
            put("fourk", "1")
            put("wts", wts)
        }
        val resp = client.get("https://api.bilibili.com/x/player/wbi/play") {
            header("User-Agent", UA)
            header("Referer", REFERER)
            header("X-Bili-Api-Source", "pc")
            header("Cookie", buvidCookie())
            params.forEach { (k, v) -> parameter(k, v) }
            parameter("w_rid", wbiSign(params, keys))
        }
        val el = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val code = (el["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1
        if (code != 0) throw RuntimeException("B站 WBI play 接口错误 code=$code ${el["message"]}")
        val data = el["data"]?.jsonObject ?: throw RuntimeException("B站 WBI play 缺少 data")
        return extractDashUrls(data)
    }

    private fun extractDashUrls(data: JsonObject): Pair<String, String> {
        val dash = data["dash"]?.jsonObject
            ?: throw RuntimeException("B站播放接口缺少 dash（可能需要会员或触发风控）")
        val videoUrl = dash["video"]?.let { it as? JsonArray }?.firstOrNull()?.jsonObject
            ?.let { it["baseUrl"]?.jsonPrimitive?.content ?: it["base_url"]?.jsonPrimitive?.content }
            .orEmpty()
        val audioUrl = dash["audio"]?.let { it as? JsonArray }?.firstOrNull()?.jsonObject
            ?.let { it["baseUrl"]?.jsonPrimitive?.content ?: it["base_url"]?.jsonPrimitive?.content }
            .orEmpty()
        if (videoUrl.isBlank() || audioUrl.isBlank()) {
            throw RuntimeException("B站播放接口缺少 video/audio 地址")
        }
        return videoUrl to audioUrl
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

    /**
     * B站风控：search/type 等接口必须带 buvid3/buvid4 cookie，否则返回 412 HTML 报错页。
     * 从 finger/spi 拿，缓存 24h（有效期很长，避免每次搜索都多打一次 spi）。
     */
    private var buvidCookieCache: String? = null
    private var buvidCookieTs: Long = 0
    private suspend fun buvidCookie(): String {
        val now = System.currentTimeMillis()
        if (buvidCookieCache != null && now - buvidCookieTs < 24 * 60 * 60_000) return buvidCookieCache!!
        val resp = client.get("https://api.bilibili.com/x/frontend/finger/spi") {
            header("User-Agent", UA)
            header("Referer", REFERER)
        }
        val el = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val data = el["data"]?.jsonObject ?: throw RuntimeException("B站 finger/spi 未返回 data")
        val b3 = data["b_3"]?.jsonPrimitive?.content ?: throw RuntimeException("B站 finger/spi 缺少 b_3")
        val b4 = data["b_4"]?.jsonPrimitive?.content ?: ""
        val cookie = "buvid3=$b3" + if (b4.isNotBlank()) "; buvid4=$b4" else ""
        buvidCookieCache = cookie
        buvidCookieTs = now
        log.info("B站 buvid cookie 已刷新")
        return cookie
    }

    /**
     * 按关键词搜索 B站视频，返回前 10 条（bvid/title/cover/videoUrl/audioUrl）。
     * 带 UA + Referer + X-Bili-Api-Source(pc) + buvid cookie 防 412 风控；
     * 单个视频直链失败只把该条 URL 留空，不让整个接口崩掉；每条直链请求最多等 10 秒。
     */
    suspend fun searchVideos(keyword: String, withUrls: Boolean = true): List<BilibiliVideoItem> {
        val resp = client.get("https://api.bilibili.com/x/web-interface/search/type") {
            header("User-Agent", UA)
            header("Referer", REFERER)
            header("X-Bili-Api-Source", "pc")
            header("Cookie", buvidCookie())
            parameter("search_type", "video")
            parameter("keyword", keyword)
        }
        val el = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val code = (el["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1
        if (code != 0) throw RuntimeException("B站搜索接口错误 code=$code ${el["message"]}")
        val data = el["data"]?.jsonObject ?: throw RuntimeException("B站搜索缺少 data")
        val result = data["result"]?.let { it as? JsonArray } ?: return emptyList()
        val basicItems = result.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val bvid = obj["bvid"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (bvid.isEmpty()) return@mapNotNull null   // B站偶尔返回合集/直播类结果，没 bvid 前端没法跳转，直接过滤
            // 标题里常带 <em class="keyword"> 高亮标签，剥掉只留纯文本
            val title = (obj["title"]?.jsonPrimitive?.content ?: "").replace(Regex("<[^>]+>"), "")
            val cover = normalizeCover(obj["pic"]?.jsonPrimitive?.content.orEmpty())
            // 时长：搜索接口给 "4:5" / "1:02:30" 这类未补零字符串，统一成 m:ss / h:mm:ss
            BilibiliVideoItem(bvid, title, cover, duration = normalizeDuration(obj["duration"]?.jsonPrimitive?.content.orEmpty()))
        }.take(10)

        // 10 个视频并发补直链 + 合集/分P 元数据，单条最多 10 秒；某条失败只留空 URL，不影响其它视频。
        // 2026-11-04：view 接口顺带带出 ugc_season（合集）与 pages（分P），客户端按合集发卡、点进去看全部分集。
        return coroutineScope {
            basicItems.map { item ->
                async {
                    val info = runCatching { withTimeoutOrNull(10_000) { viewInfo(item.bvid) } }
                        .onFailure { e -> log.warn("B站 view 获取失败 bvid=${item.bvid}：${e.message}") }
                        .getOrNull()
                    // 推荐列表只要元数据时跳过 DASH 直链：每条要额外打一次 playurl 接口，
                    // 10 条并发也要等最慢的那条（线上实测 8.4s -> 0.6s）。view 接口仍然要调，
                    // 合集 / 分P 元数据就靠它，不能一起省掉；点播直链由 /api/bilibili/play 现取。
                    val urls = if (!withUrls) null else info?.let {
                        runCatching { withTimeoutOrNull(10_000) { dashUrlsFor(item.bvid, it.cid) } }
                            .onFailure { e -> log.warn("B站直链获取失败 bvid=${item.bvid}：${e.message}") }
                            .getOrNull()
                    }
                    item.copy(
                        videoUrl = urls?.first.orEmpty(),
                        audioUrl = urls?.second.orEmpty(),
                        pageCount = info?.pages?.size ?: 0,
                        seasonId = info?.seasonId ?: 0L,
                        seasonTitle = info?.seasonTitle.orEmpty(),
                        seasonCover = info?.seasonCover.orEmpty(),
                        seasonCount = info?.seasonEpisodes?.size ?: 0,
                        episodes = when {
                            info == null -> emptyList()
                            info.seasonId != 0L -> info.seasonEpisodes
                            info.pages.size > 1 -> info.pages
                            else -> emptyList()
                        },
                    )
                }
            }.awaitAll()
        }
    }

    private fun normalizeDuration(raw: String): String {
        val parts = raw.trim().split(':').map { it.trim().toIntOrNull() ?: return "" }
        if (parts.isEmpty() || parts.size > 3) return ""
        val total = parts.fold(0) { acc, v -> acc * 60 + v }
        if (total <= 0) return ""
        val h = total / 3600
        val m = total % 3600 / 60
        val sec = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    // B站 pic 常返回 //i0.hdslb.com/... 这种协议相对地址，前端直接用会缺协议，这里统一补成 https。
    private fun normalizeCover(pic: String): String = when {
        pic.startsWith("//") -> "https:$pic"
        pic.startsWith("http://") -> "https://" + pic.removePrefix("http://")
        else -> pic
    }

    fun close() = runCatching { client.close() }
}

// 搜索结果的一项：videoUrl/audioUrl 供 App 内播放，拿不到的条目留空字符串
// 2026-11-04：合集（ugc_season）与多分P 视频带 episodes 分集元数据（bvid/cid/标题/时长），客户端点合集卡看全部。
data class BilibiliVideoItem(
    val bvid: String,
    val title: String,
    val cover: String,
    val videoUrl: String = "",
    val audioUrl: String = "",
    /** 时长 m:ss / h:mm:ss；拿不到为空串 */
    val duration: String = "",
    /** 所属 UGC 合集 id；不属于任何合集为 0 */
    val seasonId: Long = 0L,
    val seasonTitle: String = "",
    val seasonCover: String = "",
    /** 合集内视频数（0 = 无合集） */
    val seasonCount: Int = 0,
    /** 多分P 视频的分P 数；单视频为 0 */
    val pageCount: Int = 0,
    /** 分集元数据：合集→各集 bvid 不同；分P→同 bvid 不同 cid */
    val episodes: List<BiliEpisode> = emptyList(),
)

/** 一集：合集分集（bvid 各不相同）或分P（同 bvid 不同 cid）。 */
data class BiliEpisode(
    val bvid: String,
    val cid: Long,
    val title: String,
    /** m:ss / h:mm:ss；拿不到为空串 */
    val duration: String = "",
)
