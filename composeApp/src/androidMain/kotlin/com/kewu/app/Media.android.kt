package com.kewu.app

import android.graphics.BitmapFactory
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
 * 录音播放（2026-11-02 改用 media3 ExoPlayer，原 MediaPlayer 实现下线）。
 * 用户报「不管在哪个界面播放录音都一直转圈」：MediaPlayer 的 prepareAsync 遇到网络异常（如 https 连接挂死）
 * 既不回调 onPrepared 也不回调 onError，UI 停在 LOADING；且 iPhone 录的 WAV 带 JUNK / FLLR 块，部分机型解析失败。
 * ExoPlayer：WavExtractor 跳过未知块、连接 / 读取有超时并回调 onPlayerError；本地路径与 http(s) 统一处理。
 * 必须在主线程创建与调用（AudioPlayback 的回调都在主线程）。
 */
actual class PlatformAudioPlayer actual constructor() {
    private var player: androidx.media3.exoplayer.ExoPlayer? = null
    private var prepared = false

    actual fun prepare(
        source: String,
        onReady: (durationMs: Long) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
    ) {
        try {
            val ctx = platformAppContext() as android.content.Context
            val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(15_000)
                .setAllowCrossProtocolRedirects(true)
            val dataSource = androidx.media3.datasource.DefaultDataSource.Factory(ctx, http)
            val p = androidx.media3.exoplayer.ExoPlayer.Builder(ctx)
                .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSource))
                .build()
            player = p
            p.setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                false,
            )
            p.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        androidx.media3.common.Player.STATE_READY -> if (!prepared) {
                            prepared = true
                            val d = p.duration
                            onReady(if (d == androidx.media3.common.C.TIME_UNSET) 0L else d.coerceAtLeast(0))
                        }
                        // 播完先停（否则 playWhenReady 仍为 true，上层 seekTo(0) 后会自动重播）
                        androidx.media3.common.Player.STATE_ENDED -> { p.pause(); onComplete() }
                        else -> Unit
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    onError(
                        when (error.errorCode) {
                            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                            -> "网络连接失败，请检查网络后重试"
                            androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                            androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                            -> "录音文件不存在或已被清理"
                            else -> "录音加载失败，请稍后重试"
                        },
                    )
                }
            })
            val uri = ImageLoader.resolve(source).let { s ->
                if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("file:") || s.startsWith("content:")) s
                else android.net.Uri.fromFile(java.io.File(s)).toString()
            }
            p.setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
            p.playWhenReady = false
            p.prepare()
        } catch (e: Exception) {
            onError("无法播放这段录音")
        }
    }

    actual fun play() {
        if (prepared) runCatching { player?.play() }
    }

    actual fun pause() {
        if (prepared) runCatching { player?.pause() }
    }

    actual fun seekTo(ms: Long) {
        if (prepared) runCatching { player?.seekTo(ms) }
    }

    actual fun positionMs(): Long =
        if (prepared) runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L) else 0L

    actual fun release() {
        val p = player ?: return
        player = null
        prepared = false
        runCatching { p.release() }
    }
}
