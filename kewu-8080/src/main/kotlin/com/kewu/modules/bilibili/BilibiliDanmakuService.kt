package com.kewu.modules.bilibili

import com.google.protobuf.CodedInputStream
import com.google.protobuf.WireFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

// B站弹幕分段：GET /api/bilibili/danmaku?bvid=…&segmentIndex=…（可选 cid，分P/合集用）。
//
// 流程：bvid → view 拿 cid（复用 BilibiliFetcher，UA/Referer/风控 cookie 都在那边）→
//       GET https://api.bilibili.com/x/v2/dm/web/seg.so?type=1&oid={cid}&segment_index={index}
//       → protobuf 二进制解成 [{progress,mode,fontsize,color,content}]。
//
// 为什么不引 .proto + protoc：项目里没有 protobuf 生成插件，而响应结构很稳定（见下方字段号），
// 直接用 protobuf-java 的 CodedInputStream 按 wire 格式解更轻，也不会因为生成类版本不一致挂掉。
// 字段号已用真实 seg.so 响应核对过：顶层 1=dmList(repeated DanmakuElem)，元素里
//   1=id, 2=progress(ms), 3=mode, 4=fontsize, 5=color(0xRRGGBB), 6=midHash, 7=content。
//
// 长视频的关键：一个分段 = 6 分钟（360000ms），App 每 6 分钟才来一次；cid 缓存 30 分钟、
// 分段结果缓存 10 分钟，拖动进度条反复请求同一段不会再打 B站接口。
// 任何一步失败（风控 412 / 超时 / 解析异常）都只记日志并返回空数组，绝不把 500 抛给 App。
class BilibiliDanmakuService(private val fetcher: BilibiliFetcher) {

    private val log = LoggerFactory.getLogger(BilibiliDanmakuService::class.java)

    // 标准浏览器头，和 BilibiliFetcher 保持一致；缺 Referer 会被 B站 412 风控
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val referer = "https://www.bilibili.com/"

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** bvid(+#cid) → (cid, 缓存时间)。多 P 视频同一 bvid 不同 cid，传了 cid 就分开缓存。 */
    private val cidCache = ConcurrentHashMap<String, Pair<Long, Long>>()
    private val cidMutex = Mutex()

    /** "cid:segmentIndex" → (结果, 缓存时间)。弹幕会随时间新增，10 分钟后允许刷新。 */
    private val segCache = ConcurrentHashMap<String, Pair<JsonArray, Long>>()

    /** 一条弹幕：progress 单位毫秒，color 是 0xRRGGBB。 */
    private data class Elem(
        val progress: Int, val mode: Int, val fontsize: Int, val color: Int, val content: String,
    )

    /**
     * 取某一段弹幕。失败一律空数组（不是 null、不是异常），上层直接 respond 出去即可。
     * 整体 15 秒封顶：B站偶发卡住时不能让 App 的预加载协程一直挂着。
     */
    suspend fun segment(bvid: String, cid: Long, segmentIndex: Int): JsonArray =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(15_000) {
                runCatching { load(bvid, cid, segmentIndex) }
                    .onFailure { e -> log.warn("B站弹幕段加载失败 bvid=$bvid cid=$cid seg=$segmentIndex：${e.javaClass.simpleName} ${e.message.orEmpty().take(160)}") }
                    .getOrDefault(JsonArray(emptyList()))
            } ?: JsonArray(emptyList())
        }

    private suspend fun load(bvid: String, cid: Long, segmentIndex: Int): JsonArray {
        val oid = if (cid > 0) cid else resolveCid(bvid)
        if (oid <= 0) return JsonArray(emptyList())

        val key = "$oid:$segmentIndex"
        val now = System.currentTimeMillis()
        segCache[key]?.let { (arr, ts) ->
            if (now - ts < SEG_TTL_MS) return arr
        }
        evictExpired(now)

        val url = "https://api.bilibili.com/x/v2/dm/web/seg.so?type=1&oid=$oid&segment_index=$segmentIndex"
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(12))
            .header("User-Agent", ua)
            .header("Referer", referer)
            .header("Accept", "*/*")
            .GET()
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
        if (resp.statusCode() !in 200..299) {
            log.warn("B站 seg.so HTTP ${resp.statusCode()} bvid=$bvid oid=$oid seg=$segmentIndex")
            return JsonArray(emptyList())
        }

        val elems = parseDanmaku(resp.body())
        val arr = buildJsonArray {
            elems.forEach { e ->
                add(buildJsonObject {
                    put("progress", e.progress)
                    put("mode", e.mode)
                    put("fontsize", e.fontsize)
                    put("color", e.color)
                    put("content", e.content)
                })
            }
        }
        segCache[key] = arr to now
        return arr
    }

    /** view 接口拿 cid，缓存 30 分钟并用 Mutex 挡住并发重复请求。 */
    private suspend fun resolveCid(bvid: String): Long {
        val now = System.currentTimeMillis()
        cidCache[bvid]?.let { (c, ts) -> if (now - ts < CID_TTL_MS) return c }
        return cidMutex.withLock {
            cidCache[bvid]?.let { (c, ts) -> if (now - ts < CID_TTL_MS) return@withLock c }
            val c = fetcher.viewInfo(bvid).cid
            if (c > 0) cidCache[bvid] = c to System.currentTimeMillis()
            c
        }
    }

    /** 解 DmWebViewReply：只用 field 1 的 dmList，其余字段（弹幕表情/配置等）一律跳过。 */
    private fun parseDanmaku(raw: ByteArray): List<Elem> {
        if (raw.isEmpty()) return emptyList()
        val data = maybeDecompress(raw)
        val input = CodedInputStream.newInstance(data)
        val out = ArrayList<Elem>(512)
        while (!input.isAtEnd) {
            val tag = input.readTag()
            if (WireFormat.getTagFieldNumber(tag) == 1 &&
                WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED
            ) {
                // readByteArray 自己读长度前缀，正是 repeated DanmakuElem 的编码
                parseElem(CodedInputStream.newInstance(input.readByteArray()))?.let { out += it }
            } else {
                input.skipField(tag)
            }
        }
        return out
    }

    /** 解单条 DanmakuElem，缺字段走 B站默认（25号字 / 白色 / 滚动）。 */
    private fun parseElem(input: CodedInputStream): Elem? {
        var progress = 0
        var mode = 1
        var fontsize = 25
        var color = 0xFFFFFF
        var content = ""
        while (!input.isAtEnd) {
            val tag = input.readTag()
            when (WireFormat.getTagFieldNumber(tag)) {
                2 -> progress = input.readInt32()
                3 -> mode = input.readInt32()
                4 -> fontsize = input.readInt32()
                5 -> color = input.readUInt32()
                7 -> content = input.readString()
                else -> input.skipField(tag)
            }
        }
        if (content.isBlank()) return null
        return Elem(progress, mode, fontsize, color, content)
    }

    /** B站偶尔给 gzip / deflate，按文件头判一下；解不开就用原始字节交给 protobuf 解析。 */
    private fun maybeDecompress(raw: ByteArray): ByteArray = runCatching {
        when {
            raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte() ->
                GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
            raw.size > 2 && raw[0] == 0x78.toByte() ->
                InflaterInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
            else -> raw
        }
    }.getOrDefault(raw)

    /** 缓存别无限涨：过期项顺手清掉，还太多就整个清空（长视频最多几十段，够用）。 */
    private fun evictExpired(now: Long) {
        segCache.entries.removeIf { (_, v) -> now - v.second >= SEG_TTL_MS }
        if (segCache.size > MAX_CACHED_SEGMENTS) segCache.clear()
        cidCache.entries.removeIf { (_, v) -> now - v.second >= CID_TTL_MS }
    }

    private companion object {
        const val CID_TTL_MS = 30 * 60 * 1000L
        const val SEG_TTL_MS = 10 * 60 * 1000L
        const val MAX_CACHED_SEGMENTS = 256
    }
}
