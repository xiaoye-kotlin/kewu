package com.kewu.app

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.currentTime
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.seekToTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSURL
import org.jetbrains.skia.Image as SkiaImage

actual fun decodeImageBitmap(bytes: ByteArray, maxDim: Int): ImageBitmap? =
    try { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() } catch (_: Throwable) { null }

/**
 * iOS：AVPlayer 直接播链接 / 本地文件（未实机验证）。总时长需要 KVO 才能拿到，这里先按 0（未知）上报，
 * 进度条退化为只显示已播时长。
 */
@OptIn(ExperimentalForeignApi::class)
actual class PlatformAudioPlayer actual constructor() {
    private var player: AVPlayer? = null

    actual fun prepare(
        source: String,
        onReady: (durationMs: Long) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val url = ImageLoader.resolve(source)
        val nsUrl = if (url.startsWith("http://") || url.startsWith("https://")) NSURL.URLWithString(url)
            else NSURL.fileURLWithPath(url)
        if (nsUrl == null) {
            onError("无法播放这段录音")
            return
        }
        player = AVPlayer(uRL = nsUrl)
        onReady(0)
    }

    actual fun play() { player?.play() }

    actual fun pause() { player?.pause() }

    actual fun seekTo(ms: Long) {
        player?.seekToTime(CMTimeMakeWithSeconds(ms / 1000.0, 600))
    }

    actual fun positionMs(): Long {
        val p = player ?: return 0
        val sec = CMTimeGetSeconds(p.currentTime())
        return if (sec.isNaN()) 0 else (sec * 1000).toLong()
    }

    actual fun release() {
        player?.pause()
        player = null
    }
}
