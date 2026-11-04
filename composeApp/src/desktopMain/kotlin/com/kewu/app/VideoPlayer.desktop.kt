package com.kewu.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.layout.BorderPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.util.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

/**
 * ★ G2（2026-11-05）：桌面端应用内播放 = JavaFX Media。
 *
 * 为什么不用后端给的 DASH 分离流：JavaFX MediaPlayer 不能把 video-only + audio-only 两路流合并，
 * 所以这里客户端直连 B 站 `playurl?platform=html5&high_quality=1` 取 **progressive mp4**
 * （音视频合一，浏览器 <video> 同款，已实测返回 durl[0].url；该接口无需 wbi 签名，带 UA/Referer 即可）。
 * [DashVideoSources.cid] 为 0（单视频无分集元数据）时先查 view 接口拿默认 cid。
 *
 * 控制条仍用公共层 [VideoControlsLayer]（coui Slider + 玻璃钮）：
 * MediaPlayer 的状态/进度写进 [VideoControls] 单例，播放/暂停/拖动由它回调进来。
 *
 * 兜底：取流失败给「在浏览器打开」按钮（系统浏览器看原视频），不再是一句「不支持」。
 */
@Composable
actual fun VideoPlayerView(
    sources: DashVideoSources,
    modifier: Modifier,
    retryToken: Int,
    onError: (String) -> Unit,
) {
    val cs = COUITheme.colorScheme
    var mp4Url by remember(sources.bvid, retryToken) { mutableStateOf<String?>(null) }
    var err by remember(sources.bvid, retryToken) { mutableStateOf<String?>(null) }

    LaunchedEffect(sources.bvid, retryToken) {
        mp4Url = null
        err = null
        if (sources.bvid.isBlank()) {
            err = "缺少视频号，无法取播放地址"
            return@LaunchedEffect
        }
        try {
            val url = withContext(Dispatchers.IO) { fetchProgressiveMp4(sources.bvid, sources.cid) }
            if (url.isNullOrBlank()) err = "没能取到播放地址（可能需要登录或视频受限）" else mp4Url = url
        } catch (e: Exception) {
            err = "取播放地址失败：${e.message ?: "网络异常"}"
        }
    }

    when {
        err != null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(err ?: "", color = cs.error, fontSize = 13.sp)
                Text(
                    "在浏览器打开原视频",
                    color = cs.primary, fontSize = 14.sp,
                    modifier = Modifier.padding(6.dp).clickable { openInBrowser(sources.bvid) },
                )
            }
        }
        mp4Url == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("正在取播放地址…", color = cs.onSurfaceSecondary, fontSize = 13.sp)
        }
        else -> DesktopJavaFxPlayer(mp4Url ?: return, modifier, onError)
    }
}

private fun openInBrowser(bvid: String) {
    runCatching {
        java.awt.Desktop.getDesktop().browse(URI("https://www.bilibili.com/video/$bvid"))
    }
}

/** 客户端直连 B 站取 progressive mp4（html5 端，音视流合一）。 */
private suspend fun fetchProgressiveMp4(bvid: String, cid: Long): String? {
    val client = HttpClient(OkHttp)
    try {
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        // 1) cid 未知 → view 接口拿默认 cid
        var useCid = cid
        if (useCid <= 0) {
            val viewBody = client.get("https://api.bilibili.com/x/web-interface/view") {
                header("User-Agent", ua)
                header("Referer", BILI_REFERER)
                parameter("bvid", bvid)
            }.bodyAsText()
            val view = Json.parseToJsonElement(viewBody).jsonObject
            useCid = view["data"]?.jsonObject?.get("cid")?.jsonPrimitive?.content?.toLongOrNull() ?: return null
        }
        // 2) html5 progressive mp4
        val playBody = client.get("https://api.bilibili.com/x/player/playurl") {
            header("User-Agent", ua)
            header("Referer", BILI_REFERER)
            parameter("bvid", bvid)
            parameter("cid", useCid)
            parameter("platform", "html5")
            parameter("high_quality", "1")
        }.bodyAsText()
        val play = Json.parseToJsonElement(playBody).jsonObject
        return play["data"]?.jsonObject?.get("durl")?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
    } finally {
        client.close()
    }
}

private const val BILI_REFERER = "https://www.bilibili.com/"

/** JavaFX MediaPlayer 播放 progressive mp4，并把状态/进度同步进 [VideoControls]。 */
@Composable
private fun DesktopJavaFxPlayer(url: String, modifier: Modifier, onError: (String) -> Unit) {
    val playerRef = remember { arrayOfNulls<MediaPlayer>(1) }

    DisposableEffect(url) {
        VideoControls.available = true
        VideoControls.buffering = true
        onDispose {
            playerRef[0]?.let { p ->
                Platform.runLater { runCatching { p.stop(); p.dispose() } }
            }
            playerRef[0] = null
            VideoControls.available = false
            VideoControls.playing = false
            VideoControls.buffering = false
            VideoControls.onToggle = null
            VideoControls.onSeek = null
        }
    }

    // 播放/暂停 / 拖动 由公共控制条回调进来（切到 FX 线程操作 MediaPlayer）
    LaunchedEffect(url) {
        VideoControls.onToggle = {
            playerRef[0]?.let { p ->
                Platform.runLater { if (p.status == MediaPlayer.Status.PLAYING) p.pause() else p.play() }
            }
        }
        VideoControls.onSeek = { ms ->
            playerRef[0]?.let { p -> Platform.runLater { p.seek(Duration(ms.toDouble())) } }
        }
    }

    SwingPanel(
        modifier = modifier,
        factory = {
            // toolkit 必须先就绪（全局只启动一次）；JFXPanel 构造会隐式初始化 toolkit，
            // 之后再 Platform.startup 会抛「已初始化」，所以放到构造之前。
            ensureJavaFxToolkit()
            JFXPanel().apply {
                val panel = this
                Platform.runLater {
                    runCatching {
                        val media = Media(url)
                        val player = MediaPlayer(media)
                        playerRef[0] = player
                        player.setOnReady {
                            VideoControls.durationMs = media.duration.toMillis().toLong()
                            VideoControls.buffering = false
                            player.play()
                        }
                        player.setOnPlaying { VideoControls.playing = true }
                        player.setOnPaused { VideoControls.playing = false }
                        player.setOnEndOfMedia {
                            VideoControls.playing = false
                            VideoControls.positionMs = VideoControls.durationMs
                        }
                        player.setOnError {
                            VideoControls.buffering = false
                            onError("视频播放失败：${player.error?.message ?: "未知错误"}")
                        }
                        player.currentTimeProperty().addListener { _ ->
                            VideoControls.positionMs = player.currentTime.toMillis().toLong()
                        }
                        val view = MediaView(player)
                        view.isPreserveRatio = true
                        val root = BorderPane(view)
                        panel.scene = Scene(root)
                    }.onFailure {
                        VideoControls.buffering = false
                        onError("播放器初始化失败：${it.message ?: "未知错误"}")
                    }
                }
            }
        },
    )
}

/** JavaFX toolkit 全局只启动一次（Platform.startup 不能重复调用）。 */
private fun ensureJavaFxToolkit() {
    if (!javaFxStarted) {
        synchronized(javaFxLock) {
            if (!javaFxStarted) {
                runCatching { Platform.startup { } }
                javaFxStarted = true
            }
        }
    }
}

private val javaFxLock = Any()
@Volatile
private var javaFxStarted = false
