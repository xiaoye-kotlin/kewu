package com.kewu.app.ui

import com.kewu.app.AiAgent
import com.kewu.app.AiClient
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuGreen
import com.kewu.app.platformLockPortrait
import com.kewu.app.platformSetSpeakerphone
import com.kewu.app.haptic
import com.kewu.app.ui.liquidglass.KewuGlassCard
import com.kewu.app.ui.liquidglass.rememberGlassBackdrop
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.BackHandler
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.Mic
import io.github.suqi8.coui.kmp.icon.extended.Phone
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ───────────────────────── AI 语音通话全屏层（2026-11-04，P7） ─────────────────────────

/**
 * AI 语音通话（参考 zuiwuyuan/FastBlur_VoiceChat 的视觉语言，Compose 重写）：
 * - 全屏深色渐变 + 绿色氛围光斑作采样层，玻璃件照常 `KewuGlassCard` 采样（遵守 coui 约束：
 *   采样者不裹 alpha/scale、只做位移动画）；
 * - 小悟绿光环头像居中，聆听时外扩脉冲环 + 实时声纹；
 * - 链路（复用既有件，零新后端）：点大圆钮开始说 → [ChatVoiceRecorder] 边录边送
 *   `LiveAudio` → SIS 实时转录（`recorder.liveText` 实时上屏）→ 再点结束 → 文本进
 *   `AiClient.chatStream` 流式回答（通话口吻 system prompt，短句口语化）→ 回复大字居中显示；
 * - ★ TTS 未开通：AI 回复纯文本展示。预留 [TtsPlayer] 钩子，后端 `/api/ai/tts` 接通后
 *   注册实现即可语音播报；
 * - ★ 通话不落聊天记录：直接调 AiClient（不走 AssistantHub），挂断即散，不污染文字对话、
 *   也不点亮助教红点；通话内的多轮上下文只留在内存里。
 *
 * 交互（★ V11 起全程免手操作）：自动开录 → 识别到一句话说完自动交给 AI → 回答完自动继续听；
 * 静音 / 挂断 / 返回键仍可随时操作；到 [CHAT_VOICE_MAX_SECS] 自动结束本轮作兜底。
 */

/** TTS 预留钩子（P7）：后端 / 华为 TTS 开通后注册 `speak` 实现（接 `/api/ai/tts` 播放）。 */
internal object TtsPlayer {
    var speak: ((text: String) -> Unit)? = null
    fun speakIfAvailable(text: String) {
        if (text.isNotBlank()) speak?.invoke(text)
    }
}

/** 通话阶段：空闲 / 聆听中 / 小悟思考中。 */
private enum class VoiceCallPhase { IDLE, LISTENING, THINKING }

/** 通话内一轮对话（不落盘）。 */
private data class VoiceTurn(val user: String, val ai: String)

private fun vcNowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
private fun vcTwo(n: Int) = if (n < 10) "0$n" else "$n"
private fun vcFmtClock(totalSec: Int) = "${vcTwo(totalSec / 60)}:${vcTwo(totalSec % 60)}"

// ── ★ V11 自动断句参数（2026-11-04 晚 2）──
/** 实时转录文本连续稳定多久判定「一句话说完了」 */
private const val AUTO_SILENCE_MS = 1200L
/** 句末标点出现时可更快收尾（仍需稳定一小段，避免识别中间态误判） */
private const val AUTO_PUNCT_SILENCE_MS = 600L
/** 至少识别到几个字才允许自动提交 */
private const val AUTO_MIN_CHARS = 2
private const val SENTENCE_END_CHARS = "。？！?!,.、；;～~"

/** 文本是否以句末标点收尾（中文口语里省略标点时靠 [AUTO_SILENCE_MS] 兜底）。 */
private fun vcEndsWithPunct(t: String): Boolean = t.lastOrNull()?.let { it in SENTENCE_END_CHARS } == true

@Composable
internal fun AiVoiceCallScreen(onDismiss: () -> Unit) {
    // ★ V10（2026-11-04 晚 2）：通话页只允许竖屏；挂断 / 返回 / 页面销毁时恢复系统默认方向
    DisposableEffect(Unit) {
        platformLockPortrait(true)
        onDispose { platformLockPortrait(false) }
    }
    val backdrop = rememberGlassBackdrop()
    val scope = rememberCoroutineScope()
    val recorder = rememberChatVoiceRecorder()
    val snackbar = LocalKewuSnackbar.current

    var phase by remember { mutableStateOf(VoiceCallPhase.IDLE) }
    /** ★ V11/V12：静音开关。关麦时停掉本轮录音且不送 AI；取消静音自动重新进入聆听。 */
    var muted by remember { mutableStateOf(false) }
    /** ★ V12：免提 / 听筒状态（右侧扬声器按钮，Android 真切换音频路由）。 */
    var speakerOn by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    // 通话内多轮（仅内存）：上屏用「最近一轮大、更早轮小字回顾」
    var turns by remember { mutableStateOf(listOf<VoiceTurn>()) }
    // 本轮：用户最终识别文本 + AI 流式回复
    var liveUser by remember { mutableStateOf("") }
    var aiReply by remember { mutableStateOf("") }
    // 通话计时
    var callStartMs by remember { mutableStateOf(vcNowMs()) }
    var elapsedSec by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            elapsedSec = ((vcNowMs() - callStartMs) / 1000).toInt()
            delay(500)
        }
    }

    // 提示 2.2s 自动消
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(2200)
            hint = null
        }
    }

    // ── 说 / 停 ──
    fun startTalk() {
        if (phase != VoiceCallPhase.IDLE) return
        scope.launch {
            com.kewu.app.AudioPlayback.stop() // 别把自己上一轮的回放录进去
            val err = recorder.start()
            if (err != null) {
                hint = err
                return@launch
            }
            haptic(HapticKind.Light)
            phase = VoiceCallPhase.LISTENING
        }
    }

    /** 结束本轮：取实时转录（失败退回整段上传转写）→ 流式问 AI → 大字展示。 */
    fun stopTalk() {
        scope.launch {
            if (!recorder.recording) {
                phase = VoiceCallPhase.IDLE
                return@launch
            }
            val r = recorder.stop()
            // 实时识别没出字：退回既有 sendChatVoice 整段上传转写（同助教语音消息链路）
            var text = recorder.lastText?.trim()
            if (text.isNullOrEmpty() && r != null && r.second >= 1) {
                text = runCatching { KewuApi.sendChatVoice(r.first).text.trim().takeIf { it.isNotEmpty() } }.getOrNull()
            }
            r?.let { runCatching { com.kewu.app.platformDeleteFile(it.first) } }
            if (r == null || r.second < 1 || text.isNullOrEmpty()) {
                if (text.isNullOrEmpty() && r != null && r.second >= 1) hint = "没听清，请再试一次"
                phase = VoiceCallPhase.IDLE
                return@launch
            }
            liveUser = text
            phase = VoiceCallPhase.THINKING
            aiReply = ""
            val system = buildString {
                append("你是「小悟」，课悟 App 里学生的 AI 学习助手，正在和学生进行语音通话。")
                append("像在电话里聊天一样回答：口语化、直接给结论、一次只说一件事；")
                append("不要用 markdown 符号、不要列表、不要客套开场白；单次回答 120 字以内。\n")
                append("以下是该学生在课悟 App 里此刻能看到的全部信息，回答时以此为准：\n")
                append(com.kewu.app.AiContext.student())
            }
            val history = turns.takeLast(6).flatMap {
                listOf(AiClient.Msg("user", it.user), AiClient.Msg("assistant", it.ai))
            }
            val reply = try {
                AiClient.chatStream(
                    listOf(AiClient.Msg("system", system)) + history + AiClient.Msg("user", text),
                    maxTokens = 2048,
                    onDelta = { aiReply += it },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (reply == null) {
                aiReply = ""
                hint = "小悟暂时连不上 AI 服务，请稍后再试～"
                phase = VoiceCallPhase.IDLE
                return@launch
            }
            val parsed = AiAgent.parse(reply).text.trim()
            aiReply = parsed
            turns = turns + VoiceTurn(text, parsed)
            TtsPlayer.speakIfAvailable(parsed) // TTS 未开通：空实现，纯文本展示
            phase = VoiceCallPhase.IDLE
        }
    }

    // 到最长时长自动结束本轮
    LaunchedEffect(phase) {
        if (phase != VoiceCallPhase.LISTENING) return@LaunchedEffect
        while (recorder.recording) {
            if (vcNowMs() - recorder.startedAtMs >= CHAT_VOICE_MAX_SECS * 1000L) {
                haptic(HapticKind.Medium)
                stopTalk()
                break
            }
            delay(120)
        }
    }

    // ★ V11 自动断句主循环（2026-11-04 晚 2，用户「不要每次等用户点击那个麦克风才能发送给」）：
    //   ① 进入通话自动开录，不用点任何按钮；
    //   ② 实时 ASR 文本连续稳定 AUTO_SILENCE_MS（或出现句末标点且稳定 600ms）→ 判定一句话说完，
    //      自动 stopTalk() 把整句交给 AI，不再等用户点；
    //   ③ AI 回答完 phase 回 IDLE → 本循环自动再次开录，形成连续免手操作的多轮通话。
    //   说明：没有额外起一次 LLM 来判断「说完了没」（那会平白增加 1~2s 延迟），
    //   用「实时转录稳定 + 句末标点」的 VAD 式语义断句，交互上等价。
    LaunchedEffect(phase, muted) {
        if (muted) return@LaunchedEffect
        when (phase) {
            VoiceCallPhase.IDLE -> {
                // 给页面入场 / 上一轮回答留一点间隔，避免紧接着抢麦克风
                delay(350)
                startTalk()
            }
            VoiceCallPhase.LISTENING -> {
                var last = recorder.liveText.trim()
                var stableAt = vcNowMs()
                while (recorder.recording && !muted) {
                    val t = recorder.liveText.trim()
                    if (t != last) {
                        last = t
                        stableAt = vcNowMs()
                    } else {
                        val stable = vcNowMs() - stableAt
                        val ready = stable >= AUTO_SILENCE_MS ||
                            (vcEndsWithPunct(t) && stable >= AUTO_PUNCT_SILENCE_MS)
                        if (t.length >= AUTO_MIN_CHARS && ready) {
                            haptic(HapticKind.Light)
                            stopTalk()
                            break
                        }
                    }
                    delay(120)
                }
            }
            VoiceCallPhase.THINKING -> Unit
        }
    }

    /** ★ V11/V12：静音切换。静音时丢弃本轮录音且不送 AI；取消静音后自动回到聆听。 */
    fun toggleMute() {
        muted = !muted
        if (muted && recorder.recording) {
            scope.launch { recorder.cancel() }
            phase = VoiceCallPhase.IDLE
        }
    }

    // 挂断：还在录就丢弃；退出全屏层
    fun hangUp() {
        scope.launch {
            if (recorder.recording) recorder.cancel()
            onDismiss()
        }
    }
    BackHandler { hangUp() }

    Box(Modifier.fillMaxSize()) {
        // ── 采样层：深色渐变 + 两团绿色氛围光斑（玻璃件采样它） ──
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(backdrop)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0D1F17), Color(0xFF081410), Color(0xFF050C09)),
                    ),
                ),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(KewuGreen.copy(alpha = 0.20f), Color.Transparent),
                        center = Offset(size.width * 0.22f, size.height * 0.18f),
                        radius = size.minDimension * 0.85f,
                    ),
                    radius = size.minDimension * 0.85f,
                    center = Offset(size.width * 0.22f, size.height * 0.18f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(KewuGreen.copy(alpha = 0.14f), Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.72f),
                        radius = size.minDimension * 0.95f,
                    ),
                    radius = size.minDimension * 0.95f,
                    center = Offset(size.width * 0.85f, size.height * 0.72f),
                )
            }
        }

        Column(Modifier.fillMaxSize()) {
            // ── 顶部：微信式小分条（★ V12）──
            // 用户「把呼叫界面顶部的那个头像那一大块儿给我去掉，顶部像微信那样做一个小分条，
            // 显示小悟」—— 原来中部那颗 120dp 圆头像 + 脉冲光环 + 22sp 昵称整块删除（见下），
            // 这里只留一条细分条：收起钮 + 「小悟」+ 状态/计时。
            // 另用户提过「头像是正方形的」，折中保留一枚 28dp 正方形「悟」字牌（非照片头像、
            // 非一大块）；若严格不要任何头像，删掉这个 Box 即可。
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.10f))
                        .clickable { haptic(HapticKind.Tick); hangUp() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(COUIIcons.Close, contentDescription = "收起", tint = Color.White, modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(28.dp).clip(RoundedCornerShape(4.dp)).background(KewuGreen),
                        contentAlignment = Alignment.Center,
                    ) { Text("悟", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(9.dp))
                    Column {
                        Text("小悟", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(
                            when (phase) {
                                VoiceCallPhase.LISTENING -> "正在聆听 · ${vcFmtClock(elapsedSec)}"
                                VoiceCallPhase.THINKING -> "小悟思考中 · ${vcFmtClock(elapsedSec)}"
                                VoiceCallPhase.IDLE -> if (muted) "已静音 · ${vcFmtClock(elapsedSec)}"
                                else "语音通话 · ${vcFmtClock(elapsedSec)}"
                            },
                            color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(38.dp)) // 与左侧按钮对称占位
            }

            // ── 中部：状态 + 声纹/转录/回答（V12 起不再有头像区） ──
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(18.dp))
                // ★ V12：120dp 圆头像 + 脉冲光环 + 22sp 昵称整块删除；「小悟」已在顶部小分条
                Text(
                    when (phase) {
                        VoiceCallPhase.LISTENING -> "正在聆听…"
                        VoiceCallPhase.THINKING -> "小悟思考中…"
                        VoiceCallPhase.IDLE -> when {
                            muted -> "已静音 · 点左侧麦克风恢复"
                            turns.isEmpty() -> "正在准备聆听…"
                            else -> "小悟在听，继续说就行"
                        }
                    },
                    color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp,
                )
                Spacer(Modifier.height(14.dp))

                // 聆听中：实时声纹 + 实时转录文字
                if (phase == VoiceCallPhase.LISTENING) {
                    LiveVoiceWave(KewuGreen, Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 48.dp))
                    Spacer(Modifier.height(8.dp))
                    val live = recorder.liveText
                    AnimatedVisibility(live.isNotBlank(), enter = fadeIn(), exit = fadeOut()) {
                        Text(
                            live, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp,
                            lineHeight = 21.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 28.dp),
                        )
                    }
                }

                // 本轮用户的话（识别完成、AI 回答中时小字展示）
                if (phase != VoiceCallPhase.LISTENING && liveUser.isNotBlank()) {
                    Text(
                        "「$liveUser」",
                        color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp,
                        lineHeight = 18.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 28.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                }

                // AI 回复：大字居中（玻璃卡衬底）
                if (aiReply.isNotBlank()) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                        KewuGlassCard(
                            backdrop = backdrop,
                            shape = RoundedCornerShape(22.dp),
                            blurRadius = 24.dp,
                            tintAlpha = 0.30f,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                aiReply,
                                color = Color.White, fontSize = 18.sp, lineHeight = 26.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // 更早轮回顾（小字，最多 3 轮）
                turns.dropLast(1).takeLast(3).forEach { t ->
                    Text(
                        "你：${t.user}",
                        color = Color.White.copy(alpha = 0.38f), fontSize = 12.sp,
                        lineHeight = 16.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp).padding(top = 6.dp),
                    )
                    Text(
                        "小悟：${t.ai}",
                        color = Color.White.copy(alpha = 0.38f), fontSize = 12.sp,
                        lineHeight = 16.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp).padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // 轻提示（玻璃胶囊）
            AnimatedVisibility(
                visible = hint != null,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                enter = fadeIn(), exit = fadeOut(),
            ) {
                Box(
                    Modifier
                        .padding(bottom = 10.dp)
                        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(15.dp)),
                ) {
                    KewuGlassCard(
                        backdrop = backdrop,
                        shape = RoundedCornerShape(15.dp),
                        blurRadius = 16.dp,
                        tintAlpha = 0.30f,
                    ) {
                        Text(
                            hint.orEmpty(),
                            color = Color.White, fontSize = 12.5.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                }
            }

            // ── 底部：微信电话三钮（★ V12）──
            // 用户「底部居中的是挂断按钮，左侧是麦克风，右侧是一个音响扬声器的 icon」、
            // 「右边调整免提或耳机，左侧静音/麦克风，中间红色挂断」。
            // V11 起全程自动断句，原来的「说/停」大绿钮不再需要，换成微信这一排。
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 26.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top,
            ) {
                VoiceCallAction(
                    label = if (muted) "取消静音" else "静音",
                    active = muted,
                    onClick = { toggleMute() },
                ) {
                    Icon(
                        COUIIcons.Mic,
                        contentDescription = if (muted) "取消静音" else "静音",
                        tint = if (muted) Color(0xFF181818) else Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }
                VoiceCallAction(
                    label = "挂断",
                    background = Color(0xFFE5484D),
                    onClick = { hangUp() },
                ) {
                    Icon(COUIIcons.Phone, contentDescription = "挂断", tint = Color.White, modifier = Modifier.size(27.dp))
                }
                VoiceCallAction(
                    label = if (speakerOn) "免提" else "听筒",
                    active = speakerOn,
                    onClick = {
                        speakerOn = !speakerOn
                        platformSetSpeakerphone(speakerOn)
                    },
                ) {
                    SpeakerGlyph(
                        color = if (speakerOn) Color(0xFF181818) else Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
    }
}

/** ★ V12：微信电话底部圆形操作钮 + 下方文字标签（静音 / 挂断 / 免提）。 */
@Composable
private fun VoiceCallAction(
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
    background: Color = Color.White.copy(alpha = 0.14f),
    icon: @Composable () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(if (active) Color.White else background)
                .clickable { haptic(HapticKind.Tick); onClick() },
            contentAlignment = Alignment.Center,
        ) { icon() }
        Spacer(Modifier.height(9.dp))
        Text(label, color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp)
    }
}

/** ★ V12：自绘扬声器（喇叭 + 声波），coui 图标集里没有 Volume / Speaker 图标。 */
@Composable
private fun SpeakerGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w * 0.10f, h * 0.40f)
            lineTo(w * 0.30f, h * 0.40f)
            lineTo(w * 0.52f, h * 0.22f)
            lineTo(w * 0.52f, h * 0.78f)
            lineTo(w * 0.30f, h * 0.60f)
            lineTo(w * 0.10f, h * 0.60f)
            close()
        }
        drawPath(p, color)
        val sw = 2.dp.toPx()
        drawLine(color, Offset(w * 0.64f, h * 0.36f), Offset(w * 0.78f, h * 0.22f), strokeWidth = sw)
        drawLine(color, Offset(w * 0.64f, h * 0.64f), Offset(w * 0.78f, h * 0.78f), strokeWidth = sw)
        drawLine(color, Offset(w * 0.84f, h * 0.30f), Offset(w * 0.92f, h * 0.22f), strokeWidth = sw)
    }
}
