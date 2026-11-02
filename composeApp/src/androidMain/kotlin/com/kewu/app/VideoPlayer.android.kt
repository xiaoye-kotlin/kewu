package com.kewu.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.media3.datasource.DefaultHttpDataSource

/**
 * Android actual：[VideoPlayerView] 的 ExoPlayer 实现。
 *
 * B 站 DASH 分离流 = video .m4s + audio .m4s 两个独立 fMP4，
 * 用 MergingMediaSource(Progressive, Progressive) 按同一时间轴合并。
 * CDN 校验 Referer/User-Agent（缺 Referer 直接 403），在此统一注入。
 *
 * 生命周期：remember(sources, retryToken) 建 player，DisposableEffect onDispose 释放。
 * 直链带过期参数，点开后应尽快起播；失效时上层把 retryToken+1 重建播放器重试。
 */
@Composable
actual fun VideoPlayerView(
    sources: DashVideoSources,
    modifier: Modifier,
    retryToken: Int,
    onError: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = androidx.compose.runtime.remember(sources, retryToken) {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "https://www.bilibili.com",
                    "User-Agent" to
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
                ),
            )
            .setAllowCrossProtocolRedirects(true)

        fun sourceOf(url: String): MediaSource =
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(url))

        val videoMs = sources.videoUrl?.takeIf { it.isNotBlank() }?.let(::sourceOf)
        val audioMs = sources.audioUrl?.takeIf { it.isNotBlank() }?.let(::sourceOf)
        val merged: MediaSource = when {
            videoMs != null && audioMs != null -> MergingMediaSource(videoMs, audioMs)
            videoMs != null -> videoMs
            audioMs != null -> audioMs
            else -> error("DashVideoSources 无一路可播流")
        }

        ExoPlayer.Builder(context.applicationContext).build().apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    onError(error.errorCodeName)
                }
            })
            setMediaSource(merged)
            prepare()
            playWhenReady = true
        }
    }
    androidx.compose.runtime.DisposableEffect(player) {
        onDispose { player.release() }
    }
    // 全屏（2026-11-02 用户：视频没法放大）：控制条全屏键 → 横屏 + 隐藏系统栏；退出 / 离开播放层时恢复
    val fullscreen = VideoFullscreen.on
    val activity = context as? android.app.Activity ?: platformActivity() as? android.app.Activity
    androidx.compose.runtime.LaunchedEffect(fullscreen) { activity?.let { applyFullscreen(it, fullscreen) } }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            VideoFullscreen.on = false
            activity?.let { applyFullscreen(it, false) }
        }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = true
                setFullscreenButtonClickListener { VideoFullscreen.on = it }
            }
        },
        // 重试会重建 player：update 里重新绑定（旧版只在 factory 里绑一次，重试后画面不更新）
        update = { view ->
            if (view.player !== player) view.player = player
            view.setFullscreenButtonState(fullscreen)
        },
        modifier = modifier,
    )
}

private fun applyFullscreen(activity: android.app.Activity, on: Boolean) {
    activity.requestedOrientation = if (on) {
        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    } else {
        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    val ctl = androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    if (on) {
        ctl.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    } else {
        ctl.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }
}
