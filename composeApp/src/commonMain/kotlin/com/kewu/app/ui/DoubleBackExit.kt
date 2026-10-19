package com.kewu.app.ui

import com.kewu.app.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kewu.app.HapticKind
import com.kewu.app.haptic
import com.kewu.app.platformExitApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * 返回键两级行为（2026-10-19 用户需求）。
 *
 * 需求原文：一级界面（各 tab 根 / 登录页）按系统返回键 → 提示「再次按下返回键退出应用」，
 * 短时间内再按一次才真正退出；二级界面（如课堂页点课表下钻）既可点左上角返回按钮，
 * 也可直接按系统返回键返回上一级，**不应**触发退出提示。
 *
 * 实现原理：Compose 的返回事件分发给「注册最晚且 enabled 的 BackHandler」。
 * 子级页面（下钻页）在自己的分支里注册 BackHandler，组合顺序上晚于本 composable → 优先消费；
 * 下钻页退出组合后其 handler 注销，本 handler 才重新成为消费者。因此：
 * - 二级页：返回事件被下钻页自身的 BackHandler 吃掉 → 回上一级，本提示不出现；
 * - 一级页：没有更晚的 handler → 第一次按浮出提示气泡，[EXIT_WINDOW_MS] 内再按 → 退出应用。
 *
 * 提示气泡用 M3 inverseSurface / inverseOnSurface（与 Snackbar 同款语义色，不自造色），
 * 仅做 fade + 上浮动画；不参与 coui 玻璃采样，无首帧 SIGSEGV 约束问题。
 *
 * @param modifier 由调用方给定位于（一般 `Modifier.align(Alignment.BottomCenter).padding(bottom = …)`）。
 */
@Composable
fun DoubleBackExitHint(modifier: Modifier = Modifier) {
    var lastBackAt by remember { mutableStateOf(0L) }
    var hint by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler {
        val now = Clock.System.now().toEpochMilliseconds()
        if (now - lastBackAt in 1 until EXIT_WINDOW_MS) {
            // 双击窗口内第二次按下：取消提示并退出应用
            hint = false
            platformExitApp()
        } else {
            // 第一次按下（或超过窗口的重新计次）：记时 + 极轻震动 + 浮出提示
            lastBackAt = now
            haptic(HapticKind.Tick)
            hint = true
            scope.launch {
                delay(EXIT_WINDOW_MS)
                hint = false
            }
        }
    }
    AnimatedVisibility(
        visible = hint,
        modifier = modifier,
        enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { h -> h / 3 },
        exit = fadeOut(tween(200)) + slideOutVertically(tween(220)) { h -> h / 3 },
    ) {
        ExitHintBubble()
    }
}

/** 双击退出的判定窗口（毫秒）。2s 是主流 App 通用值（微信/支付宝同款）。 */
private const val EXIT_WINDOW_MS = 2000L

@Composable
private fun ExitHintBubble() {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .padding(horizontal = 32.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(cs.inverseSurface.copy(alpha = 0.92f))
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            "再次按下返回键退出应用",
            color = cs.inverseOnSurface,
            fontSize = 13.5.sp,
        )
    }
}
