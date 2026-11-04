package com.kewu.app.ui

import com.kewu.app.AiAgent
import com.kewu.app.AiClient
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuGreen
import com.kewu.app.platformLockPortrait
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
 * 交互：大圆钮点按切换（说/停），挂断或返回键退出；到 [CHAT_VOICE_MAX_SECS] 自动结束本轮。
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
            // ── 顶栏：收起 + 通话计时 ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VoiceCallIconBtn(backdrop = backdrop, icon = COUIIcons.Close, desc = "收起", onClick = { hangUp() })
                Spacer(Modifier.weight(1f))
                Text(
                    "语音通话 · ${vcFmtClock(elapsedSec)}",
                    color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp,
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(42.dp)) // 与左侧按钮对称占位
            }

            // ── 中部：头像 + 状态 + 声纹/转录/回答 ──
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(8.dp))
                // 头像 + 聆听脉冲环（非玻璃件，draw 参数级动画随意用）
                Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
                    if (phase == VoiceCallPhase.LISTENING) {
                        val t = rememberInfiniteTransition(label = "vcPulse")
                        val p by t.animateFloat(
                            0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
                            label = "vcPulseP",
                        )
                        Canvas(Modifier.fillMaxSize()) {
                            val r = size.minDimension / 2f
                            drawCircle(KewuGreen.copy(alpha = (1f - p) * 0.4f), radius = r * (0.72f + 0.28f * p))
                        }
                    }
                    Box(
                        Modifier
                            .size(92.dp)
                            .clip(CircleShape)
                            .background(KewuGreen),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    0.6f to Color.Transparent,
                                    1f to Color.White.copy(alpha = 0.28f),
                                    center = center,
                                    radius = size.minDimension / 2f,
                                ),
                                radius = size.minDimension / 2f,
                            )
                        }
                        PersonGlyph(Modifier.size(40.dp), Color.White)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("小悟", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    when (phase) {
                        VoiceCallPhase.LISTENING -> "正在聆听…"
                        VoiceCallPhase.THINKING -> "小悟思考中…"
                        VoiceCallPhase.IDLE -> if (turns.isEmpty()) "点击下方按钮，开始说话" else "继续提问，或挂断结束"
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

            // ── 底部：说话大圆钮 + 挂断 ──
            Row(
                Modifier.fillMaxWidth().padding(bottom = 34.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 挂断（红色玻璃圆钮）
                VoiceCallIconBtn(backdrop = backdrop, icon = COUIIcons.Phone, desc = "挂断", tint = Color(0xFFFF6B5E), onClick = { hangUp() })
                Spacer(Modifier.width(34.dp))
                // 说/停大圆钮：绿芯 + 白色描边（非玻璃件，按压 Q 弹可整体缩放）
                val pressed = remember { mutableStateOf(false) }
                val s by animateFloatAsState(
                    if (pressed.value) 0.9f else 1f,
                    spring(dampingRatio = 0.35f, stiffness = 460f, visibilityThreshold = 0.0005f),
                    label = "vcTalkScale",
                )
                Box(
                    Modifier
                        .size(84.dp)
                        .scale(s)
                        .border(1.5.dp, Color.White.copy(alpha = 0.30f), CircleShape)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(KewuGreen, KewuGreen.copy(alpha = 0.82f)),
                                radius = 200f,
                            ),
                        )
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    pressed.value = true
                                    tryAwaitRelease()
                                    pressed.value = false
                                },
                            )
                        }
                        .clickable {
                            haptic(HapticKind.Light)
                            when (phase) {
                                VoiceCallPhase.IDLE -> startTalk()
                                else -> stopTalk()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        COUIIcons.Mic,
                        contentDescription = if (phase == VoiceCallPhase.LISTENING) "结束说话" else "开始说话",
                        tint = Color.White, modifier = Modifier.size(34.dp),
                    )
                    if (phase == VoiceCallPhase.LISTENING) {
                        val t = rememberInfiniteTransition(label = "vcRec")
                        val a by t.animateFloat(
                            0.3f, 1f, infiniteRepeatable(tween(650, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                            label = "vcRecA",
                        )
                        Canvas(Modifier.align(Alignment.TopEnd).padding(6.dp).size(12.dp)) {
                            drawCircle(Color(0xFFFF5A4E).copy(alpha = a), radius = size.minDimension / 2f)
                        }
                    }
                }
                Spacer(Modifier.width(34.dp))
                Spacer(Modifier.size(42.dp)) // 与挂断对称占位
            }
        }
    }
}

/** 通话页圆形玻璃图标钮（挂断/收起共用）。 */
@Composable
private fun VoiceCallIconBtn(backdrop: LayerBackdrop, icon: ImageVector, desc: String, tint: Color = Color.White, onClick: () -> Unit) {
    Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)) {
            KewuGlassCard(
                backdrop = backdrop,
                shape = CircleShape,
                blurRadius = 14.dp,
                tintAlpha = 0.26f,
            ) {}
        }
        Icon(
            icon, contentDescription = desc, tint = tint,
            modifier = Modifier
                .size(20.dp)
                .clickable { haptic(HapticKind.Tick); onClick() },
        )
    }
}
