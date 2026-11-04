package com.kewu.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.HapticKind
import com.kewu.app.haptic
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Add
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.Edit
import io.github.suqi8.coui.kmp.icon.extended.Mic
import io.github.suqi8.coui.kmp.icon.extended.Ok
import io.github.suqi8.coui.kmp.icon.extended.Photos
import io.github.suqi8.coui.kmp.icon.extended.Scan
import kotlinx.coroutines.delay

/*
 * 底部输入框 —— mtjsoft/InputView 1:1 复刻（2026-11-04 U4）。
 *
 * 用户原话「我底部那个发送框用这个：https://github.com/mtjsoft/InputView」。
 * 该库是 Android XML 自定义 View（jitpack com.github.mtjsoft:InputView:1.1.0），本项目是
 * Compose Multiplatform，commonMain 无法直接依赖 Android View，因此把库 README V1.1.0 的
 * **全部 15 个自定义属性 + 4 个回调 + 三段面板交互**逐一映射到 commonMain（三端一致）。
 * 对照表见《项目交接文档》67.4。
 *
 *   inputViewBgColor / panelBackgroundColor        → [InputViewConfig]
 *   sendBtnBackgroundResId                         → sendBtnBackgroundColor
 *   emojiTypeListBgColor / emojiTypeSelectBgColor  → emojiTypeListBgColor / emojiTypeSelectBgColor
 *   audioIconResId / keyboardIconResId             → audioIcon / keyboardIcon（左 1 钮切换）
 *   emojiIconResId / emojiDeleteIconResId / emojiBackIconResId → emojiIcon 等
 *   funCationIconResId                             → funCationIcon（+ 钮）
 *   emojiColumnNumber(8) / funCationColumnNumber(4)→ [ChatEmojiPanel] / [ChatFunctionPanel]
 *   voiceMinTime(1) / voiceMaxTime(60)             → [PressToTalkButton] + config
 *   setFuncationData(FunctionEntity)               → [InputViewFunctionItem]
 *   setFuncationClickListener / onItemClick        → onFunctionClick(index)
 *   setSendClickListener / onSendClick             → onSend
 *   setVoiceOverListener / noPermission / onOver   → onVoiceNoPermission / onVoiceStop → 外部 onOver
 */

/** 对应 InputView V1.1.0 的 15 个自定义属性；默认值与 README 一致。 */
internal data class InputViewConfig(
    /** inputViewBgColor —— 整个输入框背景（微信灰） */
    val inputViewBgColor: Color = Color(0xFFF7F7F7),
    /** panelBackgroundColor —— 表情/功能面板背景 */
    val panelBackgroundColor: Color = Color(0xFFF5F5F5),
    /** sendBtnBackgroundResId —— 发送按钮背景色（微信绿） */
    val sendBtnBackgroundColor: Color = Color(0xFF07C160),
    /** emojiTypeListBgColor —— 表情类型列表背景色 */
    val emojiTypeListBgColor: Color = Color(0xFFEDEDED),
    /** emojiTypeSelectBgColor —— 表情类型选中颜色 */
    val emojiTypeSelectBgColor: Color = Color(0xFF07C160),
    /** emojiColumnNumber 默认 8 */
    val emojiColumnNumber: Int = 8,
    /** funCationColumnNumber 默认 4 */
    val funCationColumnNumber: Int = 4,
    /** voiceMinTime 默认 1 秒 */
    val voiceMinTime: Int = 1,
    /** voiceMaxTime 默认 60 秒 */
    val voiceMaxTime: Int = 60,
    /** audioIconResId —— 语音模式图标 */
    val audioIcon: ImageVector = COUIIcons.Mic,
    /** keyboardIconResId —— 键盘模式图标 */
    val keyboardIcon: ImageVector = COUIIcons.Edit,
    /** emojiIconResId */
    val emojiIcon: ImageVector = COUIIcons.Ok,
    /** emojiDeleteIconResId */
    val emojiDeleteIcon: ImageVector = COUIIcons.Close,
    /** emojiBackIconResId */
    val emojiBackIcon: ImageVector = COUIIcons.Close,
    /** funCationIconResId —— + 号图标 */
    val funCationIcon: ImageVector = COUIIcons.Add,
    val inputTextColor: Color = Color(0xFF191919),
    val inputHintColor: Color = Color(0xFF9A9A9A),
    val iconTint: Color = Color(0xFF181818),
)

/** 对应 `FunctionEntity(iconResId, name)`（setFuncationData）。 */
internal data class InputViewFunctionItem(val icon: ImageVector, val label: String)

/** ★ V4：+ 面板只留 拍照 / 相册（录音的麦克风已在输入框外，文件功能按用户要求移除）。 */
internal fun defaultInputViewFunctions(): List<InputViewFunctionItem> = listOf(
    InputViewFunctionItem(COUIIcons.Scan, "拍照"),
    InputViewFunctionItem(COUIIcons.Photos, "相册"),
)

/** 微信风格的小图标钮（替换旧版液态玻璃圆钮；不裹玻璃采样者，可直接按压）。 */
@Composable
internal fun ChatWeuiIconBtn(
    icon: ImageVector,
    onClick: () -> Unit,
    contentDescription: String? = null,
    size: Dp = 34.dp,
    tint: Color = Color(0xFF181818),
) {
    val cb = rememberUpdatedState(onClick)
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { haptic(HapticKind.Tick); cb.value() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/**
 * 完整 InputView：左语音/键盘切换、中间输入框或「按住说话」、右表情、右 + / 发送，
 * 下方按需展开 8 列表情面板 / 4 列功能面板。
 */
@Composable
internal fun ChatInputView(
    input: String,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    config: InputViewConfig = InputViewConfig(),
    modifier: Modifier = Modifier,
    placeholder: String = "问点什么…",
    sendEnabled: Boolean = true,
    hasContent: Boolean = input.isNotBlank(),
    voiceMode: Boolean = false,
    onVoiceModeChange: (Boolean) -> Unit = {},
    functionItems: List<InputViewFunctionItem> = defaultInputViewFunctions(),
    onFunctionClick: (Int) -> Unit = {},
    onVoiceStart: () -> Unit = {},
    onVoiceStop: (cancelled: Boolean) -> Unit = {},
    onVoiceNoPermission: (String) -> Unit = {},
    useBuiltinPanels: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    above: (@Composable () -> Unit)? = null,
    /** V3：输入框**下方**的面板槽位（+ / 录音面板）；放在 Row 之后、底部导航留白之前 */
    below: (@Composable () -> Unit)? = null,
    bottomPad: Dp = 0.dp,
) {
    var functionOpen by remember { mutableStateOf(false) }
    var voiceRecording by remember { mutableStateOf(false) }
    var voiceElapsed by remember { mutableLongStateOf(0L) }

    Column(
        modifier
            .fillMaxWidth()
            .background(config.inputViewBgColor)
            .padding(bottom = bottomPad.coerceAtLeast(0.dp)),
    ) {
        // 顶部分隔线（微信输入栏与消息区之间）
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x1A000000)))
        above?.invoke()
        // 录音浮层：语音模式下按住说话时，在输入栏上方展开（对应库 demo 的录音遮罩）
        if (voiceRecording) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 56.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCC2B2B2B))
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(config.audioIcon, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(6.dp))
                    Text("${voiceElapsed / 1000}″", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("松开发送 · 说话太短会取消", color = Color.White.copy(alpha = 0.78f), fontSize = 11.sp)
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左 1：audioIconResId ↔ keyboardIconResId
            if (leading != null) {
                leading()
            } else {
                ChatWeuiIconBtn(
                    icon = if (voiceMode) config.keyboardIcon else config.audioIcon,
                    onClick = {
                        functionOpen = false
                        onVoiceModeChange(!voiceMode)
                    },
                    contentDescription = if (voiceMode) "切回键盘" else "切到语音",
                    tint = config.iconTint,
                )
            }
            Spacer(Modifier.width(6.dp))
            // 中：文字输入框 / 按住说话
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.White)
                    .border(0.5.dp, Color(0x14000000), RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (voiceMode && useBuiltinPanels) {
                    PressToTalkButton(
                        config = config,
                        onStart = onVoiceStart,
                        onStop = onVoiceStop,
                        onNoPermission = onVoiceNoPermission,
                        onRecordingChange = { on, ms -> voiceRecording = on; voiceElapsed = ms },
                    )
                } else {
                    if (input.isEmpty()) {
                        Text(
                            placeholder,
                            color = config.inputHintColor,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 9.dp),
                        )
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = onInput,
                        textStyle = TextStyle(color = config.inputTextColor, fontSize = 14.sp),
                        cursorBrush = SolidColor(config.sendBtnBackgroundColor),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (sendEnabled && hasContent) onSend() }),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp),
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            trailing?.invoke()
            // ★ V1 + V2（2026-11-04 晚 2）：右侧那个「勾勾」（原 emojiIcon=Ok 表情钮）删掉，
            //   表情面板整体移除；这里改成「发送」按钮并**常驻显示**（不再依赖 hasContent）。
            //   有内容 = 微信绿，无内容 = 半透明绿，仍可点（各页面 send() 自己挡空输入）。
            Box(
                Modifier
                    .height(34.dp)
                    .width(56.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(
                        if (hasContent) config.sendBtnBackgroundColor
                        else config.sendBtnBackgroundColor.copy(alpha = 0.45f),
                    )
                    .clickable(enabled = sendEnabled) { haptic(HapticKind.Light); onSend() },
                contentAlignment = Alignment.Center,
            ) {
                Text("发送", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            }
        }
        // ★ V3：+ / 录音面板渲染在输入框**下方**（微信就是键盘位），bottomPad 在其下统一给导航让位
        below?.invoke()
        // 功能面板：setFuncationData / funCationColumnNumber
        if (useBuiltinPanels) {
            AnimatedVisibility(
                visible = functionOpen,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                ChatFunctionPanel(
                    config = config,
                    items = functionItems,
                    onClick = { i -> functionOpen = false; onFunctionClick(i) },
                )
            }
        }
    }
}

/**
 * 语音模式「按住说话」：按下开始、松手结束；短于 voiceMinTime 视为取消；
 * 到 voiceMaxTime 自动结束。录音中弹出居中浮层（松开发送 / 上滑取消提示）。
 * 实际录音启停由外部 `onVoiceStart / onVoiceStop` 接现有 [ChatVoiceRecorder] 链路。
 */
@Composable
private fun PressToTalkButton(
    config: InputViewConfig,
    onStart: () -> Unit,
    onStop: (cancelled: Boolean) -> Unit,
    onNoPermission: (String) -> Unit,
    onRecordingChange: (Boolean, Long) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var autoStopped by remember { mutableStateOf(false) }
    val startCb = rememberUpdatedState(onStart)
    val stopCb = rememberUpdatedState(onStop)
    val changeCb = rememberUpdatedState(onRecordingChange)
    // noPermission 由外部 onVoiceStart 里的录音链路转发（库里是申请权限时回调）

    LaunchedEffect(pressed) {
        if (!pressed) {
            changeCb.value(false, 0L)
            return@LaunchedEffect
        }
        val t0 = nowInputMs()
        changeCb.value(true, 0L)
        while (pressed) {
            val ms = nowInputMs() - t0
            elapsedMs = ms
            changeCb.value(true, ms)
            if (ms >= config.voiceMaxTime * 1000L) {
                autoStopped = true
                pressed = false
                stopCb.value(false)
                break
            }
            delay(100)
        }
        changeCb.value(false, elapsedMs)
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        val t0 = nowInputMs()
                        autoStopped = false
                        elapsedMs = 0L
                        pressed = true
                        haptic(HapticKind.Medium)
                        startCb.value()
                        tryAwaitRelease()
                        pressed = false
                        // 到 voiceMaxTime 已自动收尾，避免松手时重复 onOver
                        if (!autoStopped) {
                            val tooShort = (nowInputMs() - t0) < config.voiceMinTime * 1000L
                            stopCb.value(tooShort)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (pressed) "${elapsedMs / 1000}″  松开发送" else "按住 说话",
            color = config.inputTextColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}

// ★ V1（2026-11-04 晚 2）：用户明确「表情面板去掉这里不需要发送表情」——8 列表情面板
//   与右侧 emoji 钮（原来的「勾勾」）一并移除。InputViewConfig 里的 emoji 相关 attrs 仍保留，
//   只是不再挂载任何 UI，避免破坏了配置对照表；后续要恢复只需重新挂回 ChatEmojiPanel。

/** 4 列功能面板（funCationColumnNumber / setFuncationData / onItemClick）。 */
@Composable
internal fun ChatFunctionPanel(
    config: InputViewConfig,
    items: List<InputViewFunctionItem>,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cols = config.funCationColumnNumber.coerceAtLeast(1)
    Column(
        modifier
            .fillMaxWidth()
            // V4 只剩两项，高度随内容自适应，避免一整块空白
            .background(config.panelBackgroundColor)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items.withIndex().chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { (index, item) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { haptic(HapticKind.Tick); onClick(index) }
                            .padding(vertical = 6.dp),
                    ) {
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White)
                                .border(0.5.dp, Color(0x14000000), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(item.icon, contentDescription = item.label, tint = config.iconTint, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(item.label, color = Color(0xFF5B5B5B), fontSize = 11.5.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

private fun nowInputMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
