package com.kewu.recserver.routes

import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.utils.io.toByteArray
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * 助教聊天里的图片 / 语音（2026-11-03）：收 multipart、图片规整（转正 + 缩放 + 转 JPEG）、
 * OBS 图片转 data URL 交给视觉模型。
 */
internal object ChatMedia {

    private const val MAX_SIDE = 1600
    private const val MAX_FETCH_BYTES = 8 * 1024 * 1024

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun day(): String = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)

    /** 读 multipart 里名为 [field] 的文件：(原文件名, 字节)；没有或超过 [limit] 返回 null。 */
    suspend fun receiveFile(call: ApplicationCall, field: String, limit: Long): Pair<String, ByteArray>? {
        var result: Pair<String, ByteArray>? = null
        runCatching {
            call.receiveMultipart(formFieldLimit = limit).forEachPart { part ->
                if (part is PartData.FileItem && part.name == field && result == null) {
                    result = (part.originalFileName ?: "") to part.provider().toByteArray()
                }
                part.dispose()
            }
        }.onFailure { println("CHAT-MEDIA-RECEIVE: $it") }
        return result?.takeIf { it.second.isNotEmpty() && it.second.size <= limit }
    }

    /**
     * 图片规整：按 EXIF 方向转正（手机竖拍的 JPEG 像素是横的，只靠 EXIF 标记方向，ImageIO 不认），
     * 长边缩到 [MAX_SIDE]，透明底铺白，输出 JPEG。ImageIO 读不了的 WebP 原样返回；其它格式（如 HEIC）返回 null。
     */
    fun normalizeImage(bytes: ByteArray): Pair<ByteArray, String>? {
        val src = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
        if (src == null) {
            val webp = bytes.size > 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
            return if (webp && bytes.size <= MAX_FETCH_BYTES) bytes to "webp" else null
        }
        val oriented = orient(src, exifOrientation(bytes))
        val scale = minOf(1.0, MAX_SIDE.toDouble() / maxOf(oriented.width, oriented.height))
        val w = maxOf(1, (oriented.width * scale).toInt())
        val h = maxOf(1, (oriented.height * scale).toInt())
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.color = java.awt.Color.WHITE
        g.fillRect(0, 0, w, h)
        g.drawImage(oriented, 0, 0, w, h, null)
        g.dispose()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val buf = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(buf).use { ios ->
            writer.output = ios
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = 0.85f
            }
            writer.write(null, IIOImage(out, null, null), param)
        }
        writer.dispose()
        return buf.toByteArray() to "jpg"
    }

    /** 1=正常 3=180° 6=顺时针 90° 8=逆时针 90°（2/4/5/7 为镜像，按对应旋转近似处理）。 */
    private fun orient(img: BufferedImage, o: Int): BufferedImage {
        val quarter = when (o) { 6, 5 -> 1; 3, 4 -> 2; 8, 7 -> 3; else -> 0 }
        if (quarter == 0) return img
        val swap = quarter % 2 == 1
        val w = if (swap) img.height else img.width
        val h = if (swap) img.width else img.height
        val out = BufferedImage(w, h, if (img.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        val t = AffineTransform()
        when (quarter) {
            1 -> { t.translate(w.toDouble(), 0.0); t.rotate(Math.PI / 2) }
            2 -> { t.translate(w.toDouble(), h.toDouble()); t.rotate(Math.PI) }
            3 -> { t.translate(0.0, h.toDouble()); t.rotate(-Math.PI / 2) }
        }
        g.drawImage(img, t, null)
        g.dispose()
        return out
    }

    /** 从 JPEG 的 APP1/Exif 里读 Orientation（0x0112）；不是 JPEG / 没有则返回 1。 */
    private fun exifOrientation(b: ByteArray): Int = runCatching {
        if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return 1
        var i = 2
        while (i + 4 < b.size) {
            if (b[i] != 0xFF.toByte()) return 1
            val marker = b[i + 1].toInt() and 0xFF
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (marker == 0xDA || len < 2) return 1
            if (marker == 0xE1 && i + 10 < b.size && String(b, i + 4, 4, Charsets.US_ASCII) == "Exif") {
                val tiff = i + 10
                val le = b[tiff] == 'I'.code.toByte()
                fun u16(o: Int): Int {
                    val x = b[o].toInt() and 0xFF
                    val y = b[o + 1].toInt() and 0xFF
                    return if (le) x or (y shl 8) else (x shl 8) or y
                }
                fun u32(o: Int): Int = if (le) u16(o) or (u16(o + 2) shl 16) else (u16(o) shl 16) or u16(o + 2)
                val ifd = tiff + u32(tiff + 4)
                val n = u16(ifd)
                for (k in 0 until n) {
                    val e = ifd + 2 + k * 12
                    if (u16(e) == 0x0112) return u16(e + 8).takeIf { it in 1..8 } ?: 1
                }
                return 1
            }
            i += 2 + len
        }
        1
    }.getOrDefault(1)

    /**
     * 图片来源 → data URL：data:image 原样；自家 OBS（域名以 .myhuaweicloud.com 结尾的 https 链接）下载后转 base64。
     * 其它链接一律拒绝（null），避免接口被当成任意抓取代理。
     */
    fun toDataUrl(src: String): String? {
        if (src.startsWith("data:image/")) return src.takeIf { it.length <= MAX_FETCH_BYTES * 4 / 3 + 64 }
        val uri = runCatching { URI(src) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (uri.scheme != "https" || !host.endsWith(".myhuaweicloud.com")) return null
        return runCatching {
            val req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
            val body = resp.body()
            if (resp.statusCode() !in 200..299 || body.isEmpty() || body.size > MAX_FETCH_BYTES) return null
            val mime = when {
                body.size > 3 && body[0] == 0x89.toByte() && body[1] == 'P'.code.toByte() -> "image/png"
                body.size > 12 && String(body, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
                else -> "image/jpeg"
            }
            "data:$mime;base64," + Base64.getEncoder().encodeToString(body)
        }.onFailure { println("CHAT-IMAGE-FETCH: $src $it") }.getOrNull()
    }
}
