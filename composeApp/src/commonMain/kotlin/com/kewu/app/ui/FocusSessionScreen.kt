package com.kewu.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.FocusFinishInfo
import com.kewu.app.FocusStatusInfo
import com.kewu.app.FocusSuggestionInfo
import com.kewu.app.FocusVisualConfigInfo
import com.kewu.app.HapticKind
import com.kewu.app.KewuApi
import com.kewu.app.KewuGreen
import com.kewu.app.haptic
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.ChipDefaults
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.ProgressIndicatorDefaults
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import kotlinx.coroutines.launch

/**
 * 专注进行中的沉浸式界面（2026-11-16）。
 *
 * 这是「开始专注后」产生的新界面：整屏铺 [FocusBackground]（AI visual_config 三态背景），
 * 倒计时 / 操作 / 自评面板全部浮在动态背景之上。用半透明面板而非玻璃卡——
 * 因为玻璃卡采样的是 TaskPage 的灰底 backdrop，盖在深色动态背景上会突兀；半透明黑面板更贴合沉浸氛围。
 *
 * 背景层由父级 FocusTimerPage 的圆形扩散动画包裹，故本组件自身不需要再做 reveal。
 */
@Composable
fun FocusSessionScreen(
    status: FocusStatusInfo?,
    suggestion: FocusSuggestionInfo?,
    plannedMinutes: Int,
    tick: Int,
    baseTick: Int,
    loading: Boolean,
    notice: String?,
    onNotice: (String?) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSuggestionReload: () -> Unit,
    onExited: () -> Unit,
) {
    val visual = suggestion?.visualConfig ?: FocusVisualConfigInfo("DARK_BLUE_SOLID", 15, 20)
    val scope = rememberCoroutineScope()

    var finishing by remember { mutableStateOf(false) }
    var difficulty by remember { mutableStateOf(3) }
    var fatigue by remember { mutableStateOf(3) }

    val focusing = status?.focusing == true
    val resting = status?.resting == true
    val passed = (tick - baseTick).coerceAtLeast(0).toLong()
    val elapsed = (status?.elapsedSeconds ?: 0L) + if (focusing) passed else 0L
    val restLeft = ((status?.restRemainingSeconds ?: 0L) - if (resting) passed else 0L).coerceAtLeast(0L)
    val totalSec = (plannedMinutes * 60).coerceAtLeast(1)
    val progress = (elapsed.toFloat() / totalSec).coerceIn(0f, 1f)

    // 深色动态背景上的固定浅色文字（不跟 COUI 主题色，避免浅色主题下黑字看不清）
    val onDark = Color.White
    val onDarkDim = Color.White.copy(alpha = 0.72f)

    // 背景：进入专注页时随机选一张低干扰柔和图（静态/低饱和/低对比/无文字），不抢注意力
    FocusBackground(config = visual, enableNoise = false, calmRandom = true) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 顶部：返回 + 状态
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // 亮色半透明按钮：深色背景上清晰可见（白色描边 + 白字）
                TextButton(
                    text = "‹ 退出专注",
                    onClick = { haptic(HapticKind.Tick); onBack() },
                    modifier = Modifier.height(38.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary(
                        color = Color.White.copy(alpha = 0.18f),
                        textColor = Color.White,
                    ),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        resting -> "休息中"
                        focusing -> "专注中"
                        else -> "已结束"
                    },
                    color = onDark, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                )
            }

            Spacer(Modifier.height(32.dp))

            if (loading) {
                Text("正在同步专注状态…", color = onDarkDim, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                CircularProgressIndicator(
                    colors = ProgressIndicatorDefaults.circularProgressIndicatorColors(foregroundColor = KewuGreen),
                    strokeWidth = 3.dp, size = 34.dp,
                )
            } else {
                val timeText = when {
                    focusing -> formatClock((totalSec - elapsed).coerceAtLeast(0L))
                    resting -> formatClock(restLeft)
                    else -> "00:00"
                }
                val subText = when {
                    focusing -> "已专注 ${formatClock(elapsed)} · 目标 $plannedMinutes 分钟"
                    resting -> "闭眼放松，休息 ${formatClock(restLeft)}"
                    else -> "本次已结束"
                }
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = if (focusing) progress else if (resting) 1f else 0f,
                        colors = ProgressIndicatorDefaults.circularProgressIndicatorColors(foregroundColor = KewuGreen),
                        strokeWidth = 11.dp, size = 240.dp,
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(timeText, color = onDark, fontSize = 50.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        Text(subText, color = onDarkDim, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                }
                if (focusing) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "已记录分心 ${status?.distractionCount ?: 0} 次",
                        color = KewuGreen, fontSize = 13.sp,
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            // 半透明操作面板（加深底色，让面板边界和按钮在背景上更清晰）
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.46f))
                    .padding(18.dp),
            ) {
                if (focusing) {
                    KewuPrimaryButton("完成专注", onClick = { haptic(HapticKind.Light); finishing = true }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Row {
                        // 「我分心了 +1」白色半透明底 + 白字，深色背景上清晰
                        TextButton(
                            text = "我分心了 +1",
                            onClick = {
                                haptic(HapticKind.Tick)
                                scope.launch {
                                    val n = KewuApi.focusDistraction()
                                    if (n != null) { onNotice("已记一次分心（共 $n 次）"); onRefresh() }
                                    else onNotice("上报失败，请检查网络")
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(
                                color = Color.White.copy(alpha = 0.20f),
                                textColor = Color.White,
                            ),
                        )
                        Spacer(Modifier.size(8.dp))
                        // 「放弃本次」暖橙警示色，与「完成」区分开
                        TextButton(
                            text = "放弃本次",
                            onClick = {
                                haptic(HapticKind.Tick)
                                scope.launch {
                                    val ok = KewuApi.focusCancel()
                                    onNotice(if (ok) "已取消，本次不计入统计" else "取消失败，请检查网络")
                                    onRefresh()
                                    if (ok) onExited()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(
                                color = Color(0xFFFF8A65).copy(alpha = 0.22f),
                                textColor = Color(0xFFFFAB91),
                            ),
                        )
                    }
                } else if (resting) {
                    Text("休息一下，让大脑整理刚才学到的东西。", color = onDarkDim, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    KewuPrimaryButton(
                        "跳过休息",
                        onClick = {
                            scope.launch {
                                val ok = KewuApi.focusSkipRest()
                                onNotice(if (ok) "已跳过休息" else "操作失败，请检查网络")
                                onRefresh()
                                if (ok) onExited()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text("本次专注已结束，可返回设置新的时长。", color = onDarkDim, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    KewuPrimaryButton("返回设置", onExited, modifier = Modifier.fillMaxWidth())
                }

                // 完成时的难度 / 疲劳自评
                if (finishing) {
                    Spacer(Modifier.height(18.dp))
                    Text("本次感受（用于 AI 推荐）", color = onDark, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Text("任务难度", color = onDarkDim, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    // 亮色 Chip：未选中=白半透明底+白字，选中=绿底+白字（深色面板上清晰）
                    val scoreChipColors = ChipDefaults.chipColors(
                        containerColor = Color.White.copy(alpha = 0.18f),
                        selectedContainerColor = KewuGreen,
                        contentColor = Color.White,
                        selectedContentColor = Color.White,
                    )
                    Row {
                        (1..5).forEach { n ->
                            Chip(
                                selected = difficulty == n,
                                onClick = { haptic(HapticKind.Tick); difficulty = n },
                                label = "$n",
                                colors = scoreChipColors,
                            )
                            Spacer(Modifier.size(6.dp))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("专注后疲劳感", color = onDarkDim, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Row {
                        (1..5).forEach { n ->
                            Chip(
                                selected = fatigue == n,
                                onClick = { haptic(HapticKind.Tick); fatigue = n },
                                label = "$n",
                                colors = scoreChipColors,
                            )
                            Spacer(Modifier.size(6.dp))
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    KewuPrimaryButton(
                        "提交并完成",
                        onClick = {
                            scope.launch {
                                val r: FocusFinishInfo? = KewuApi.focusFinish(difficulty, fatigue)
                                finishing = false
                                onNotice(
                                    if (r != null) {
                                        if (r.restSeconds > 0) "完成！本次 ${r.actualMinutes} 分钟，休息 ${r.restSeconds / 60} 分钟"
                                        else "完成！本次 ${r.actualMinutes} 分钟"
                                    } else "提交失败，请检查网络"
                                )
                                onRefresh()
                                onSuggestionReload()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        text = "先不提交",
                        onClick = { haptic(HapticKind.Tick); finishing = false },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary(
                            color = Color.White.copy(alpha = 0.18f),
                            textColor = Color.White,
                        ),
                    )
                }

                if (notice != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(notice!!, color = onDarkDim, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 秒数转 mm:ss（纯展示）。 */
private fun formatClock(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
