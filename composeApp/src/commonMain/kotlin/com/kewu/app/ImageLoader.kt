package com.kewu.app

import androidx.compose.ui.graphics.ImageBitmap
import io.ktor.client.request.get
import io.ktor.client.statement.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 把编码后的图片字节解码成 [ImageBitmap]，长边不超过 [maxDim]（Android 用 inSampleSize 降采样，
 * 避免 4000 万像素的手机原图直接解成 160MB 位图）。解不出来返回 null。
 */
expect fun decodeImageBitmap(bytes: ByteArray, maxDim: Int): ImageBitmap?

/**
 * 内置图片查看器 / 附件缩略图的加载器（2026-10-26）：http(s) 链接走网络，其余按本地路径读文件。
 *
 * - 原始字节按 url 缓存（总量上限 [BYTES_LIMIT]），同一张图的缩略图和大图只下载一次；
 * - 解码结果按「url@尺寸」缓存最近 [BITMAP_LIMIT] 张；
 * - OBS 附件链接公开可读，不带 token；后端若返回相对路径（`/xxx`）按 [KewuApi.BASE_URL] 补全。
 */
object ImageLoader {
    private const val BYTES_LIMIT = 24L * 1024 * 1024
    private const val BITMAP_LIMIT = 24

    private val client by lazy { platformHttpClient() }
    private val mutex = Mutex()
    private val bytesCache = LinkedHashMap<String, ByteArray>()
    private val bitmapCache = LinkedHashMap<String, ImageBitmap>()

    /** 后端相对路径（`/api/…`、`/uploads/…`）补全成完整链接；完整链接和本机文件路径原样返回。 */
    fun resolve(source: String): String =
        if (BACKEND_PREFIXES.any { source.startsWith(it) }) KewuApi.BASE_URL + source else source

    private val BACKEND_PREFIXES = listOf("/api/", "/uploads/", "/files/")

    private fun isRemote(source: String) = source.startsWith("http://") || source.startsWith("https://")

    /** 已在内存里的解码结果（首帧直接显示，不闪加载态）。 */
    fun cached(source: String, maxDim: Int): ImageBitmap? = bitmapCache["$source@$maxDim"]

    /** 加载并解码；失败返回 null（网络 / 文件不存在 / 格式不支持）。 */
    suspend fun load(source: String, maxDim: Int): ImageBitmap? {
        val key = "$source@$maxDim"
        mutex.withLock { bitmapCache.remove(key)?.let { bitmapCache[key] = it; return it } }
        val bytes = loadBytes(source) ?: return null
        val bmp = withContext(Dispatchers.Default) {
            try { decodeImageBitmap(bytes, maxDim) } catch (_: Throwable) { null }
        } ?: return null
        mutex.withLock {
            bitmapCache[key] = bmp
            while (bitmapCache.size > BITMAP_LIMIT) bitmapCache.remove(bitmapCache.keys.first())
        }
        return bmp
    }

    private suspend fun loadBytes(source: String): ByteArray? {
        mutex.withLock { bytesCache.remove(source)?.let { bytesCache[source] = it; return it } }
        val url = resolve(source)
        val bytes = try {
            if (isRemote(url)) {
                val resp = client.get(url)
                if (resp.status.value !in 200..299) null else resp.readBytes()
            } else {
                withContext(Dispatchers.Default) { platformReadFile(url) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        if (bytes.isEmpty()) return null
        mutex.withLock {
            bytesCache[source] = bytes
            var total = bytesCache.values.sumOf { it.size.toLong() }
            while (total > BYTES_LIMIT && bytesCache.size > 1) {
                val first = bytesCache.keys.first()
                total -= bytesCache.remove(first)?.size ?: 0
            }
        }
        return bytes
    }
}
