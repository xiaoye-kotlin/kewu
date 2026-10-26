package com.kewu.app

import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun decodeImageBitmap(bytes: ByteArray, maxDim: Int): ImageBitmap? {
    // 先只读尺寸，再按 2 的幂降采样到长边 ≤ maxDim（BitmapFactory 的 inSampleSize 只认 2 的幂）
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}

/**
 * MediaPlayer 实现：在主线程创建，prepareAsync 的回调回到主线程 Looper。
 * http 链接由 MediaPlayer 边下边播（Manifest 已开 usesCleartextTraffic，OBS / 8080 是明文 http）。
 */
actual class PlatformAudioPlayer actual constructor() {
    private var mp: MediaPlayer? = MediaPlayer()
    private var prepared = false

    actual fun prepare(
        source: String,
        onReady: (durationMs: Long) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val p = mp ?: return
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            p.setOnPreparedListener {
                prepared = true
                onReady(it.duration.toLong().coerceAtLeast(0))
            }
            p.setOnCompletionListener { onComplete() }
            p.setOnErrorListener { _, what, _ ->
                onError(if (what == MediaPlayer.MEDIA_ERROR_SERVER_DIED) "播放服务异常，请重试" else "录音加载失败，请检查网络后重试")
                true
            }
            p.setDataSource(ImageLoader.resolve(source))
            p.prepareAsync()
        } catch (e: Exception) {
            onError("无法播放这段录音")
        }
    }

    actual fun play() {
        if (prepared) runCatching { mp?.start() }
    }

    actual fun pause() {
        if (prepared) runCatching { if (mp?.isPlaying == true) mp?.pause() }
    }

    actual fun seekTo(ms: Long) {
        if (prepared) runCatching { mp?.seekTo(ms.toInt()) }
    }

    actual fun positionMs(): Long =
        if (prepared) runCatching { mp?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L) else 0L

    actual fun release() {
        val p = mp ?: return
        mp = null
        prepared = false
        runCatching { p.reset() }
        runCatching { p.release() }
    }
}
