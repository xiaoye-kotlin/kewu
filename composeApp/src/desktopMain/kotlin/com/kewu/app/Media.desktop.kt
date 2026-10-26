package com.kewu.app

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.EventQueue
import java.io.File
import java.net.URI
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import org.jetbrains.skia.Image as SkiaImage

actual fun decodeImageBitmap(bytes: ByteArray, maxDim: Int): ImageBitmap? =
    try { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() } catch (_: Exception) { null }

/**
 * 桌面端（开发调试用）：javax.sound 只认 WAV / AIFF / AU。远程链接先下载到临时文件，
 * 播放用 SourceDataLine 流式写出，跳转 = 重新打开流并跳过对应字节。
 */
actual class PlatformAudioPlayer actual constructor() {
    @Volatile private var file: File? = null
    @Volatile private var format: AudioFormat? = null
    @Volatile private var totalFrames = 0L
    @Volatile private var startFrame = 0L
    @Volatile private var line: SourceDataLine? = null
    @Volatile private var playThread: Thread? = null
    @Volatile private var released = false
    private var onComplete: () -> Unit = {}

    actual fun prepare(
        source: String,
        onReady: (durationMs: Long) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
    ) {
        this.onComplete = onComplete
        Thread {
            try {
                val url = ImageLoader.resolve(source)
                val f = if (url.startsWith("http://") || url.startsWith("https://")) {
                    File.createTempFile("kewu_play_", ".wav").also { tmp ->
                        tmp.deleteOnExit()
                        URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    }
                } else File(url)
                AudioSystem.getAudioInputStream(f).use { s ->
                    format = s.format
                    totalFrames = s.frameLength
                }
                file = f
                val fmt = format!!
                val dur = if (totalFrames > 0) (totalFrames * 1000 / fmt.frameRate).toLong() else 0L
                if (!released) EventQueue.invokeLater { onReady(dur) }
            } catch (_: Exception) {
                if (!released) EventQueue.invokeLater { onError("无法播放这段录音（桌面端仅支持 WAV）") }
            }
        }.apply { isDaemon = true }.start()
    }

    actual fun play() {
        val f = file ?: return
        val fmt = format ?: return
        if (playThread?.isAlive == true) return
        val from = startFrame
        playThread = Thread {
            try {
                AudioSystem.getAudioInputStream(f).use { s: AudioInputStream ->
                    s.skip(from * fmt.frameSize)
                    val l = AudioSystem.getSourceDataLine(fmt)
                    l.open(fmt)
                    line = l
                    l.start()
                    val buf = ByteArray(fmt.frameSize * 2048)
                    var finished = true
                    while (true) {
                        if (Thread.currentThread().isInterrupted || released) { finished = false; break }
                        val n = s.read(buf)
                        if (n <= 0) break
                        l.write(buf, 0, n)
                    }
                    if (finished) {
                        l.drain()
                        l.close()
                        line = null
                    }
                    if (finished && !released) {
                        startFrame = 0
                        EventQueue.invokeLater { onComplete() }
                    }
                }
            } catch (_: Exception) {
                line = null
            }
        }.apply { isDaemon = true; start() }
    }

    actual fun pause() {
        val l = line
        val t = playThread
        line = null
        playThread = null
        if (l != null) {
            startFrame += l.longFramePosition
            // close 会让阻塞中的 write 立即返回，播放线程随后因中断标记退出
            runCatching { l.stop(); l.flush(); l.close() }
        }
        t?.interrupt()
    }

    actual fun seekTo(ms: Long) {
        val fmt = format ?: return
        val wasPlaying = playThread?.isAlive == true
        if (wasPlaying) pause()
        startFrame = (ms * fmt.frameRate / 1000).toLong().coerceIn(0, maxOf(0, totalFrames))
        if (wasPlaying) play()
    }

    actual fun positionMs(): Long {
        val fmt = format ?: return 0
        val frames = startFrame + (line?.longFramePosition ?: 0L)
        return (frames * 1000 / fmt.frameRate).toLong()
    }

    actual fun release() {
        released = true
        pause()
    }
}
