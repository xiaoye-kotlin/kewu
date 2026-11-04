package com.kewu.app.ui

import androidx.compose.runtime.mutableStateListOf
import com.kewu.app.BiliEpisodeInfo
import com.kewu.app.BilibiliVideoInfo
import com.kewu.app.kvGet
import com.kewu.app.kvSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * B 站「收藏的合集」（★ W5，2026-11-04 晚 3）。
 *
 * 用户：「加一个收藏的功能，也就是可以收藏这个合集」「收藏可以看自己收藏的合集」
 * 「AI 也能知道我收藏的内容」。
 *
 * - 持久化到 kv `kewu_bili_favorites`（JSON 数组，不存 videoUrl/audioUrl —— DASH 直链会过期）；
 * - 用 `mutableStateListOf` 让收藏按钮 / 浏览面板 / AI 摘要都随收藏变化自动刷新；
 * - 收藏键：合集用 `seasonId`，非合集用 `bvid`（同一合集的多张卡只算一条）；
 * - [contextLine] 供 W7 注入 `AiContext.student()`，让 AI 知道学生收藏了哪些合集。
 */
internal object BiliFavorites {
    private const val KEY = "kewu_bili_favorites"
    private val items = mutableStateListOf<BilibiliVideoInfo>()
    private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            val raw = kvGet(KEY) ?: return
            Json.parseToJsonElement(raw).jsonArray.forEach { el ->
                decodeVideo(el)?.let { items.add(it) }
            }
        }
    }

    /** 全部收藏（Compose 组合期读取会订阅，收藏变化自动重组）。 */
    fun all(): List<BilibiliVideoInfo> {
        ensureLoaded()
        return items
    }

    /** 同一合集只算一条；非合集按 bvid。 */
    fun keyOf(v: BilibiliVideoInfo): String =
        if (v.seasonId != 0L) "s${v.seasonId}" else "b${v.bvid}"

    fun isFavorite(v: BilibiliVideoInfo): Boolean {
        ensureLoaded()
        val k = keyOf(v)
        return items.any { keyOf(it) == k }
    }

    /** 再点一次即取消收藏。 */
    fun toggle(v: BilibiliVideoInfo) {
        ensureLoaded()
        val k = keyOf(v)
        val idx = items.indexOfFirst { keyOf(it) == k }
        if (idx >= 0) {
            items.removeAt(idx)
        } else {
            // 直链不入库：下次打开按 bvid/cid 现取
            items.add(v.copy(videoUrl = null, audioUrl = null))
        }
        save()
    }

    fun remove(v: BilibiliVideoInfo) {
        ensureLoaded()
        val k = keyOf(v)
        val idx = items.indexOfFirst { keyOf(it) == k }
        if (idx >= 0) {
            items.removeAt(idx)
            save()
        }
    }

    /** ★ W7：AI 上下文摘要（学生收藏了哪些合集、各多少集）。 */
    fun contextLine(): String {
        ensureLoaded()
        if (items.isEmpty()) return ""
        return buildString {
            append("【我收藏的 B 站学习合集】")
            append(items.joinToString("；") { v ->
                val name = v.seasonTitle.ifBlank { v.title }.take(40)
                if (v.episodes.isNotEmpty()) "$name（共 ${v.episodes.size} 集）" else name
            })
            append("。回答学习相关问题时可优先参考这些合集的内容。")
        }
    }

    private fun save() {
        runCatching {
            kvSet(KEY, buildJsonArray { items.forEach { add(encodeVideo(it)) } }.toString())
        }
    }

    private fun encodeVideo(v: BilibiliVideoInfo): JsonObject = buildJsonObject {
        put("bvid", v.bvid)
        put("title", v.title)
        put("cover", v.cover)
        put("duration", v.duration)
        put("seasonId", v.seasonId)
        put("seasonTitle", v.seasonTitle)
        put("seasonCover", v.seasonCover)
        put("seasonCount", v.seasonCount)
        put("pageCount", v.pageCount)
        put("episodes", buildJsonArray {
            v.episodes.forEach { ep ->
                add(buildJsonObject {
                    put("bvid", ep.bvid)
                    put("cid", ep.cid)
                    put("title", ep.title)
                    put("duration", ep.duration)
                })
            }
        })
    }

    private fun decodeVideo(el: JsonElement): BilibiliVideoInfo? {
        val o = el as? JsonObject ?: return null
        val eps: List<BiliEpisodeInfo> = (o["episodes"] as? JsonArray)?.mapNotNull { e ->
            val eo = e as? JsonObject ?: return@mapNotNull null
            BiliEpisodeInfo(
                bvid = txt(eo["bvid"]),
                cid = num(eo["cid"]),
                title = txt(eo["title"]),
                duration = txt(eo["duration"]),
            )
        }.orEmpty()
        val bvid = txt(o["bvid"])
        if (bvid.isBlank()) return null
        return BilibiliVideoInfo(
            bvid = bvid,
            title = txt(o["title"]),
            cover = txt(o["cover"]),
            videoUrl = null,
            audioUrl = null,
            duration = txt(o["duration"]),
            seasonId = num(o["seasonId"]),
            seasonTitle = txt(o["seasonTitle"]),
            seasonCover = txt(o["seasonCover"]),
            seasonCount = num(o["seasonCount"]).toInt(),
            pageCount = num(o["pageCount"]).toInt(),
            episodes = eps,
        )
    }

    private fun txt(e: JsonElement?): String =
        if (e == null || e is JsonNull || e !is JsonPrimitive) ""
        else runCatching { e.jsonPrimitive.content }.getOrNull().orEmpty()

    private fun num(e: JsonElement?): Long = txt(e).toLongOrNull() ?: 0L
}
