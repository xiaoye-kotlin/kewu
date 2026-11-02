package com.kewu.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * B 站视频应用内播放（2026-11-02，用户明确要求：不跳 B 站 App，在课悟内直接观看）。
 *
 * 后端 `/api/bilibili/videos` 返回的 videoUrl/audioUrl 是 B 站 DASH 分离流直链
 * （video/audio 均为完整 fMP4，可独立渐进下载，可能为空且带过期参数）。
 *
 * 平台实现：
 * - Android：media3 ExoPlayer，MergingMediaSource 合并视频+音频，
 *   DefaultHttpDataSource 带 Referer/User-Agent（B 站 CDN 校验，缺了会 403）。
 * - iOS / Desktop：占位 actual，提示暂不支持（本项目主交付 Android）。
 *
 * GitHub 上开源 Kotlin 播放器（NextPlayer、Just (Video) Player 等）底层全部是
 * media3 ExoPlayer，本项目直接采用同一方案，不额外引入封装层。
 */

/** 一次应用内播放请求：标题 + 封面（仅展示用）+ DASH 分离流直链（可空）。 */
data class DashVideoSources(
    val title: String,
    val cover: String,
    val videoUrl: String?,
    val audioUrl: String?,
) {
    /** 至少一路流可播才算可播放。 */
    val playable: Boolean get() = !videoUrl.isNullOrBlank()
}

/**
 * 应用内视频播放器。调用方负责提供 [sources]（video/audio 至少一路非空），
 * 播放器内部处理加载/合并/失败。
 *
 * @param retryToken 重试令牌：+1 触发重建播放器（B 站直链带过期参数，失效后重试）。
 * @param onError 播放失败回调（HTTP 403/直链过期等），由 UI 层展示重试面板。
 */
@Composable
expect fun VideoPlayerView(
    sources: DashVideoSources,
    modifier: Modifier = Modifier,
    retryToken: Int = 0,
    onError: (String) -> Unit = {},
)
