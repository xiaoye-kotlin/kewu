package com.kewu.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.AudioPlayback
import com.kewu.app.AudioRecorder
import com.kewu.app.ChatHistory
import com.kewu.app.HapticKind
import com.kewu.app.ImageLoader
import com.kewu.app.KewuGreen
import com.kewu.app.LiveAudio
import com.kewu.app.RealtimeAsr
import com.kewu.app.RecordingManager
import com.kewu.app.haptic
import com.kewu.app.platformActivity
import com.kewu.app.platformAppContext
import com.kewu.app.platformCacheDir
import com.kewu.app.platformDeleteFile
import com.kewu.app.requestAudioPermission
import com.kewu.app.ui.liquidglass.KewuGlassCard
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.basic.Surface
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.Image as ImageIcon
import io.github.suqi8.coui.kmp.icon.extended.Ok
import io.github.suqi8.coui.kmp.icon.extended.Pause
import io.github.suqi8.coui.kmp.icon.extended.Phone
import io.github.suqi8.coui.kmp.icon.extended.Photos
import io.github.suqi8.coui.kmp.icon.extended.Play
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.suqi8.coui.kmp.icon.extended.Scan
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/*
 * 助教发图 / 发语音（2026-11-03）。
 *
 * 链路：
 *  - 图片：相册 / 拍照 → 输入栏上方预览（可配文字）→ 发送 → `POST /api/ai/chat-image`（服务端转正、缩放、转 JPEG，存 OBS）
 *    → 带 OBS 链接走 `/api/ai/stream`，服务端取回图片交给 DeepSeek 视觉模型。
 *  - 语音：点麦克风开录（最长 60 秒）→ 发送 → `POST /api/ai/voice`（存 OBS → 华为 SIS 识别）→ 识别文字作为提问交给 AI。
 *
 * 上传 / 识别在进程级协程里跑（[ChatMediaHub]），切走页面也会完成并写进聊天记录；进度通过 [ChatMediaHub.patches] 回到页面。
 */

/** 用户图片 / 语音消息的发送状态。 */
internal enum class ChatMediaState { DONE, SENDING, FAILED }

/** 语音最长时长（秒），到点自动发送。 */
internal const val CHAT_VOICE_MAX_SECS = 60

/** 失败消息的「点击重试」回调（页面提供）。 */
internal val LocalChatMediaRetry = staticCompositionLocalOf<(AiChatMsg) -> Unit> { {} }

/**
 * 媒体消息的进程级任务中心：上传 / 识别不随页面销毁取消；结果以补丁形式广播，页面在就地更新。
 */
internal object ChatMediaHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 对某条消息的更新（null 字段表示不改）。 */
    data class Patch(
        val id: Long,
        val state: ChatMediaState,
        val image: String? = null,
        val voice: String? = null,
        val text: String? = null,
        val error: String? = null,
    )

    private val _patches = MutableStateFlow<Map<Long, Patch>>(emptyMap())
    val patches: StateFlow<Map<Long, Patch>> = _patches.asStateFlow()

    private val _busy = MutableStateFlow<Set<String>>(emptySet())

    /** 正在上传 / 识别的会话 key（页面据此禁止再发，避免两路 AI 回答交错）。 */
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    fun patch(p: Patch) {
        _patches.value = _patches.value + (p.id to p)
    }

    fun run(key: String, block: suspend () -> Unit) {
        _busy.value = _busy.value + key
        scope.launch {
            try {
                block()
            } finally {
                _busy.value = _busy.value - key
            }
        }
    }

    /** 把补丁合到消息上。 */
    fun apply(m: AiChatMsg, p: Patch): AiChatMsg = m.copy(
        mediaState = p.state,
        image = p.image ?: m.image,
        voice = p.voice ?: m.voice,
        text = p.text ?: m.text,
        mediaError = p.error,
    )

    /** 写进聊天记录（按 id 合并；页面不在也能落盘）。 */
    fun persist(key: String, m: AiChatMsg) {
        ChatHistory.upsert(
            key,
            listOf(
                ChatHistory.Entry(
                    id = m.id, role = "user", text = m.text, ts = m.ts, quote = m.quote,
                    image = m.image, voice = if (m.isVoice) m.voice else null, voiceSecs = m.voiceSecs,
                ),
            ),
        )
    }
}

/** 本地图片的 MIME（按扩展名猜，服务端会统一转 JPEG）。 */
internal fun guessImageMime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "heic", "heif" -> "image/heic"
    else -> "image/jpeg"
}

// ───────────────────────── 录音控制 ─────────────────────────

/**
 * 助教语音录制：16kHz 单声道 WAV（与 SIS 一句话识别格式一致）。
 * 课堂录音进行中不抢麦克风；页面销毁时若还在录，停掉并删除文件。
 *
 * 2026-11-03 实时转录：平台注册了 [LiveAudio]（Android）时边录边把 PCM 送给 [RealtimeAsr]，
 * 说话过程中 [liveText] 实时出字，停下后 [lastText] 即最终文字，不再上传文件等转写；
 * 实时识别连不上时 [lastText] 为 null，调用方退回 `KewuApi.sendChatVoice` 整段上传。
 */
@Stable
internal class ChatVoiceRecorder {
    private val recorder by lazy { runCatching { AudioRecorder(platformAppContext()) }.getOrNull() }
    private val lock = Mutex()

    var recording by mutableStateOf(false)
        private set

    /** 开始录音的时刻（epoch 毫秒），界面据此计时。 */
    var startedAtMs by mutableLongStateOf(0L)
        private set

    private var asr: RealtimeAsr? = null
    private var live = false

    /** 实时识别出的文字（录音中边说边更新）。 */
    val liveText: String get() = asr?.text.orEmpty()

    /** 上一次 [stop] 得到的实时识别结果；null = 没走实时识别或识别失败（需上传转写）。 */
    var lastText: String? = null
        private set

    /** 开始录音；成功返回 null，失败返回原因。 */
    suspend fun start(): String? = lock.withLock {
        if (recording) return@withLock null
        if (RecordingManager.isRecording) return@withLock "正在录课堂录音，暂时不能发语音"
        val rec = recorder ?: if (LiveAudio.start == null) return@withLock "当前设备不支持录音" else null
        if (!awaitPermission()) return@withLock "需要麦克风权限才能发语音"
        val now = nowMs()
        lastText = null
        val liveStart = LiveAudio.start
        if (liveStart != null) {
            val a = RealtimeAsr()
            val ok = runCatching { liveStart("${platformCacheDir()}/chatvoice/v_$now.wav") { a.push(it) } }.getOrDefault(false)
            if (ok) {
                a.start()
                asr = a
                live = true
                startedAtMs = now
                recording = true
                return@withLock null
            }
        }
        val out = "${platformCacheDir()}/chatvoice/v_$now.pcm"
        val fileRec = rec ?: return@withLock "当前设备不支持录音"
        val ok = runCatching { fileRec.startRecording(out) }.getOrDefault(false)
        if (!ok) return@withLock "无法开始录音（麦克风可能被占用）"
        live = false
        startedAtMs = now
        recording = true
        null
    }

    /** 停止并返回 (WAV 路径, 秒数)；没在录 / 保存失败返回 null。 */
    suspend fun stop(): Pair<String, Int>? = lock.withLock {
        if (!recording) return@withLock null
        recording = false
        val secs = ((nowMs() - startedAtMs + 400) / 1000).toInt().coerceIn(0, CHAT_VOICE_MAX_SECS)
        if (live) {
            val path = runCatching { LiveAudio.stop?.invoke() }.getOrNull()
            val a = asr
            asr = null
            lastText = if (path == null || secs < 1) { a?.cancel(); null } else a?.finish()
            return@withLock path?.let { it to secs }
        }
        val path = runCatching { recorder?.stopRecording() }.getOrNull() ?: return@withLock null
        path to secs
    }

    /** 取消：停止并删除文件。 */
    suspend fun cancel() {
        stop()?.let { runCatching { platformDeleteFile(it.first) } }
    }

    private suspend fun awaitPermission(): Boolean {
        if (recorder?.hasPermission() == true) return true
        val act = platformActivity() ?: return true
        return suspendCancellableCoroutine { c ->
            requestAudioPermission(act) { granted -> if (c.isActive) c.resume(granted) }
        }
    }
}

/** 记住一个录音控制器；离开页面时还在录就停掉并丢弃。 */
@Composable
internal fun rememberChatVoiceRecorder(): ChatVoiceRecorder {
    val r = remember { ChatVoiceRecorder() }
    DisposableEffect(r) {
        onDispose {
            if (r.recording) {
                @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) { r.cancel() }
            }
        }
    }
    return r
}

// ───────────────────────── 面板（液态玻璃） ─────────────────────────

/**
 * 附件面板（U4 按 InputView 的 `funCationColumnNumber(4)` + `setFuncationData` 重做）：
 * 微信灰底 + 白色圆角方块 4 列网格，替代旧版的两颗液态玻璃圆钮。
 */
@Composable
internal fun ChatAttachPanel(
    backdrop: LayerBackdrop,
    onAlbum: () -> Unit,
    onCamera: (() -> Unit)?,
    config: InputViewConfig = InputViewConfig(),
    /** ★ W10：语音通话入口（W1 删掉人格区电话钮后补回「+」面板；不传 = 不显示该项） */
    onVoiceCall: (() -> Unit)? = null,
) {
    // ★ V4：只保留 拍照 / 相册；★ W10 追加「语音通话」。
    val items = mutableListOf<InputViewFunctionItem>()
    val actions = mutableListOf<() -> Unit>()
    if (onCamera != null) {
        items += InputViewFunctionItem(COUIIcons.Scan, "拍照")
        actions += onCamera
    }
    items += InputViewFunctionItem(COUIIcons.Photos, "相册")
    actions += onAlbum
    if (onVoiceCall != null) {
        items += InputViewFunctionItem(COUIIcons.Phone, "语音通话")
        actions += onVoiceCall
    }
    ChatFunctionPanel(
        config = config,
        items = items,
        onClick = { i -> actions.getOrNull(i)?.invoke() },
    )
}

@Composable
private fun GlassPanel(backdrop: LayerBackdrop, content: @Composable () -> Unit) {
    val cs = COUITheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 6.dp)
            .border(1.dp, cs.dividerLine, RoundedCornerShape(22.dp)),
    ) {
        KewuGlassCard(
            backdrop = backdrop,
            shape = RoundedCornerShape(22.dp),
            blurRadius = 20.dp,
            tintAlpha = 0.36f,
            modifier = Modifier.fillMaxWidth(),
        ) { content() }
    }
}

/** 附件入口：错峰弹入（位移 + 缩放，作用在玻璃之内的 coui 件上）。 */
@Composable
private fun AttachEntry(label: String, icon: ImageVector, index: Int, onClick: () -> Unit) {
    val cs = COUITheme.colorScheme
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(40L + index * 60L)
        p.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 380f))
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .graphicsLayer {
                val v = p.value
                scaleX = 0.6f + 0.4f * v
                scaleY = 0.6f + 0.4f * v
                translationY = (1f - v) * 18.dp.toPx()
                alpha = v.coerceIn(0f, 1f)
            }
            .pressBounce { haptic(HapticKind.Tick); onClick() },
    ) {
        Surface(
            modifier = Modifier.size(56.dp),
            shape = CircleShape,
            color = cs.secondaryContainer,
            border = BorderStroke(1.dp, cs.dividerLine),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = cs.primary, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = cs.onSurfaceSecondary, fontSize = 11.5.sp)
    }
}

/**
 * 录音面板：取消 / 实时声纹 / 红点计时 / 发送。到 [CHAT_VOICE_MAX_SECS] 秒自动发送；最后 10 秒倒计时变色。
 * 声纹是单个无限时钟驱动的 Canvas 绘制（只重绘不重组），低端机也流畅。
 */
@Composable
internal fun ChatVoicePanel(
    backdrop: LayerBackdrop,
    recorder: ChatVoiceRecorder,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    val cs = COUITheme.colorScheme
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recorder.recording, recorder.startedAtMs) {
        while (recorder.recording) {
            elapsedMs = (nowMs() - recorder.startedAtMs).coerceAtLeast(0L)
            if (elapsedMs >= CHAT_VOICE_MAX_SECS * 1000L) {
                haptic(HapticKind.Medium)
                onSend()
                break
            }
            delay(100)
        }
    }
    val secs = (elapsedMs / 1000).toInt()
    val left = CHAT_VOICE_MAX_SECS - secs
    val warn = left <= 10
    // U4 InputView：面板改微信灰底（panelBackgroundColor），不再用液态玻璃采样者
    Box(Modifier.fillMaxWidth().background(InputViewConfig().panelBackgroundColor)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { haptic(HapticKind.Tick); onCancel() },
                    backgroundColor = cs.secondaryContainer,
                    minWidth = 40.dp,
                    minHeight = 40.dp,
                    cornerRadius = 20.dp,
                ) {
                    Icon(COUIIcons.Close, contentDescription = "取消", tint = cs.onSurfaceSecondary, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(12.dp))
                LiveVoiceWave(
                    color = if (warn) Color(0xFFF5A623) else KewuGreen,
                    modifier = Modifier.weight(1f).height(34.dp),
                )
                Spacer(Modifier.width(12.dp))
                IconButton(
                    onClick = { haptic(HapticKind.Light); onSend() },
                    backgroundColor = KewuGreen,
                    minWidth = 40.dp,
                    minHeight = 40.dp,
                    cornerRadius = 20.dp,
                ) {
                    Icon(COUIIcons.Ok, contentDescription = "发送语音", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                RecordingDot()
                Spacer(Modifier.width(6.dp))
                Text(
                    "${secs / 60}:${(secs % 60).toString().padStart(2, '0')}",
                    color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (warn) "还剩 $left 秒" else "点 ✓ 发送",
                    color = if (warn) Color(0xFFF5A623) else cs.onSurfaceSecondary, fontSize = 12.sp,
                )
            }
            // 实时转录：边说边出字（没走实时识别时不显示）
            val live = recorder.liveText
            AnimatedVisibility(live.isNotBlank()) {
                Text(
                    live,
                    color = cs.onSurface, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 4,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}

/** 录音中的红点：呼吸闪烁。 */
@Composable
private fun RecordingDot() {
    val t = rememberInfiniteTransition(label = "recDot")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "recDotA")
    Canvas(Modifier.size(8.dp)) { drawCircle(Color(0xFFE5484D).copy(alpha = a)) }
}

/** 实时声纹：一组圆角竖条按两路正弦叠加起伏，入场时振幅从 0 渐入（P7 起语音通话页复用，故 internal）。 */
@Composable
internal fun LiveVoiceWave(color: Color, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "liveWave")
    val phase by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "liveWavePhase")
    val amp = remember { Animatable(0f) }
    LaunchedEffect(Unit) { amp.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
    Canvas(modifier) {
        val bars = 26
        val gap = size.width / bars
        val barW = (gap * 0.46f).coerceAtMost(4.dp.toPx())
        for (i in 0 until bars) {
            val a = abs(sin(phase + i * 0.52f)) * (0.55f + 0.45f * sin(phase * 2f + i * 1.27f))
            val h = size.height * (0.16f + 0.84f * (a.coerceIn(0f, 1f) * amp.value))
            val x = i * gap + (gap - barW) / 2f
            drawRoundRect(
                color = color.copy(alpha = 0.55f + 0.45f * a.coerceIn(0f, 1f)),
                topLeft = Offset(x, (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}

/** 输入栏上方的待发图片：缩略图 + 提示 + ×；弹入时轻微放大。 */
@Composable
internal fun ChatStagedImageBar(path: String, onRemove: () -> Unit) {
    val cs = COUITheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = cs.surfaceContainer,
        border = BorderStroke(1.dp, cs.dividerLine),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RemoteImage(
                source = path,
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp))
                    .clickable { ImageViewer.open(listOf(ViewerImage(path))) },
                maxDim = 240,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("图片", color = cs.onSurface, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                Text("可以再写一句想问的", color = cs.onSurfaceSecondary, fontSize = 11.5.sp)
            }
            IconButton(
                onClick = { haptic(HapticKind.Tick); onRemove() },
                backgroundColor = cs.secondaryContainer,
                minWidth = 32.dp,
                minHeight = 32.dp,
                cornerRadius = 16.dp,
            ) {
                Icon(COUIIcons.Close, contentDescription = "移除图片", tint = cs.onSurfaceSecondary, modifier = Modifier.size(14.dp))
            }
        }
    }
}

// ───────────────────────── 用户气泡里的图片 / 语音 ─────────────────────────

/**
 * 图片气泡：按原图比例显示（长边 200dp），圆角 + 细描边；发送中蒙层转圈，失败蒙层 + 点击重试；点开大图。
 * 入场：从 0.86 弹到 1（非玻璃件，可用透明度）。
 */
@Composable
internal fun ChatImageBubble(msg: AiChatMsg, onLongPress: () -> Unit) {
    val cs = COUITheme.colorScheme
    val src = msg.image ?: return
    val retry = LocalChatMediaRetry.current
    // 上传完成后 src 由本地路径换成 OBS 链接：新图到手前继续显示旧图，避免闪回占位
    var bmp by remember { mutableStateOf(ImageLoader.cached(src, 720)) }
    LaunchedEffect(src) {
        (ImageLoader.cached(src, 720) ?: ImageLoader.load(src, 720))?.let { bmp = it }
    }
    val (w, h) = bmp?.let { b ->
        val r = (b.width.toFloat() / b.height.coerceAtLeast(1)).coerceIn(0.56f, 1.8f)
        if (r >= 1f) 200f to 200f / r else 200f * r to 200f
    } ?: (160f to 160f)
    val enter = remember { Animatable(if (msg.mediaState == ChatMediaState.DONE) 1f else 0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 360f)) }
    val dim by animateFloatAsState(if (msg.mediaState == ChatMediaState.DONE) 0f else 0.38f, tween(240), label = "imgDim")
    // ★ 2026-11-04 U3 weui：图片气泡与文本气泡同一套语言（6dp 圆角 + 极淡描边 + 浅灰占位底），
    //   旧版 18dp 大圆角 + 主题容器深色底，和重做后的 weui 文本气泡并排时明显不是一家人。
    // ★ X3（2026-11-05）：图片气泡圆角 6→12dp，与文本气泡统一
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .graphicsLayer {
                val v = enter.value
                scaleX = 0.86f + 0.14f * v
                scaleY = 0.86f + 0.14f * v
                alpha = v.coerceIn(0f, 1f)
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f)
            }
            .size(w.dp, h.dp)
            .clip(shape)
            .border(1.dp, WeuiBubbleBorder, shape)
            .background(WeuiChatBg)
            .bubblePress(onLongPress)
            .clickable {
                when (msg.mediaState) {
                    ChatMediaState.FAILED -> { haptic(HapticKind.Light); retry(msg) }
                    else -> ImageViewer.open(listOf(ViewerImage(src)))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            androidx.compose.foundation.Image(b, contentDescription = "图片", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(COUIIcons.ImageIcon, contentDescription = null, tint = cs.onSurfaceVariantSummary, modifier = Modifier.size(24.dp))
        }
        if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        when (msg.mediaState) {
            ChatMediaState.SENDING -> InfiniteProgressIndicator(size = 22.dp)
            ChatMediaState.FAILED -> FailedBadge(msg.mediaError)
            ChatMediaState.DONE -> Unit
        }
    }
}

@Composable
private fun FailedBadge(error: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(0xFFE5484D)), contentAlignment = Alignment.Center) {
            Icon(COUIIcons.Refresh, contentDescription = "重试", tint = Color.White, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(error?.take(16) ?: "发送失败", color = Color.White, fontSize = 11.sp)
        Text("点击重试", color = Color.White.copy(alpha = 0.8f), fontSize = 10.5.sp)
    }
}

/**
 * 语音气泡：品牌色胶囊，宽度随时长增长；播放键 + 声纹（播放时按进度点亮并跳动）+ 时长；
 * 下方展开识别出的文字（识别中显示跳动省略号，失败可点重试）。
 */
@Composable
internal fun ChatVoiceBubble(msg: AiChatMsg, onLongPress: () -> Unit) {
    val retry = LocalChatMediaRetry.current
    val src = msg.voice
    val key = "chatvoice:${msg.id}"
    val play by AudioPlayback.state.collectAsState()
    val mine = play.key == key
    val playing = mine && play.phase == AudioPlayback.Phase.PLAYING
    val loading = mine && play.phase == AudioPlayback.Phase.LOADING
    val progress = if (mine && play.durationMs > 0) (play.positionMs.toFloat() / play.durationMs).coerceIn(0f, 1f) else 0f
    val secs = msg.voiceSecs.coerceAtLeast(1)
    val widthDp = (110 + secs * 2.4f).coerceAtMost(230f)
    // ★ 2026-11-04 U3 weui：语音气泡也统一到「纯 #95EC69 + #191919 黑字 + 6dp 圆角」。
    //   旧版是品牌主色胶囊 + 白字 22dp 大圆角，与重做后的文本气泡完全不是一套语言。
    // ★ X3（2026-11-05）：语音气泡圆角 6→12dp（靠头像侧尖角处留 4dp），与文本气泡统一
    val shape = RoundedCornerShape(topStart = 12.dp, topEnd = 4.dp, bottomEnd = 12.dp, bottomStart = 12.dp)
    val p = rememberEnterProgress(0, baseDelayMs = 40L)
    Surface(
        modifier = Modifier.widthIn(min = widthDp.dp, max = 260.dp).riseEnter(p, 26f),
        shape = shape,
        color = WeuiUserGreen,
        contentColor = WeuiText,
    ) {
        Column(
            Modifier
                .bubblePress(onLongPress)
                .animateContentSize(spring(dampingRatio = 0.85f, stiffness = 380f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(
                Modifier.width((widthDp - 24).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(WeuiText.copy(alpha = 0.10f))
                        .clickable(enabled = src != null) {
                            haptic(HapticKind.Tick)
                            if (src != null) AudioPlayback.toggle(key, src)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        loading -> InfiniteProgressIndicator(size = 14.dp)
                        playing -> Icon(COUIIcons.Pause, contentDescription = "暂停", tint = WeuiText, modifier = Modifier.size(14.dp))
                        else -> Icon(COUIIcons.Play, contentDescription = "播放", tint = WeuiText, modifier = Modifier.size(14.dp))
                    }
                }
                Spacer(Modifier.width(9.dp))
                PlaybackWave(
                    seed = msg.id,
                    playing = playing,
                    progress = progress,
                    color = WeuiText,
                    modifier = Modifier.weight(1f).height(22.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("$secs″", color = WeuiText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            val transcript = msg.text.trim()
            AnimatedVisibility(
                visible = msg.mediaState != ChatMediaState.DONE || transcript.isNotEmpty(),
                enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = 380f)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
            ) {
                Column(Modifier.width((widthDp - 24).dp).padding(top = 8.dp)) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(WeuiText.copy(alpha = 0.12f)))
                    Spacer(Modifier.height(7.dp))
                    when (msg.mediaState) {
                        ChatMediaState.SENDING -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("正在识别", color = WeuiText.copy(alpha = 0.65f), fontSize = 12.5.sp)
                            TypingDots(WeuiText.copy(alpha = 0.65f))
                        }
                        ChatMediaState.FAILED -> Text(
                            (msg.mediaError ?: "没听清") + "，点击重试",
                            color = WeuiText,
                            fontSize = 12.5.sp,
                            modifier = Modifier.clickable { haptic(HapticKind.Light); retry(msg) },
                        )
                        ChatMediaState.DONE -> Text(transcript, color = WeuiText.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 19.sp)
                    }
                }
            }
        }
    }
}

/** 三个依次跳动的点（「正在识别…」）。 */
@Composable
private fun TypingDots(color: Color) {
    val t = rememberInfiniteTransition(label = "dots")
    val ph by t.animateFloat(0f, 3f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "dotsPh")
    Canvas(Modifier.padding(start = 3.dp).size(width = 18.dp, height = 10.dp)) {
        for (i in 0 until 3) {
            val d = ((ph - i + 3f) % 3f)
            val lift = if (d < 1f) sin(d * PI.toFloat()) else 0f
            drawCircle(
                color.copy(alpha = 0.5f + 0.5f * lift),
                radius = 1.6.dp.toPx(),
                center = Offset(size.width * (0.17f + i * 0.33f), size.height * (0.7f - 0.35f * lift)),
            )
        }
    }
}

/** 回放声纹：固定的伪随机条高（同一条消息每次一样）；播放时已播部分点亮、条高轻微跳动。 */
@Composable
private fun PlaybackWave(seed: Long, playing: Boolean, progress: Float, color: Color, modifier: Modifier = Modifier) {
    val heights = remember(seed) {
        var x = (seed xor 0x5DEECE66DL) and 0xFFFFFF
        List(24) {
            x = (x * 1103515245 + 12345) and 0x7FFFFFFF
            0.3f + (x % 1000) / 1000f * 0.7f
        }
    }
    val t = rememberInfiniteTransition(label = "pbWave")
    val ph by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(900, easing = LinearEasing)), label = "pbWavePh")
    val bounce by animateFloatAsState(if (playing) 1f else 0f, tween(220), label = "pbBounce")
    Canvas(modifier) {
        val n = heights.size
        val gap = size.width / n
        val barW = (gap * 0.5f).coerceAtMost(3.dp.toPx())
        for (i in 0 until n) {
            val wobble = 1f + 0.22f * bounce * sin(ph + i * 0.8f)
            val h = (size.height * heights[i] * wobble).coerceIn(size.height * 0.2f, size.height)
            val lit = (i + 0.5f) / n <= progress
            drawRoundRect(
                color = color.copy(alpha = if (lit || (!playing && progress == 0f)) 0.95f else 0.42f),
                topLeft = Offset(i * gap + (gap - barW) / 2f, (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}

private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
