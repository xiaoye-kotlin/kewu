package com.kewu.app.ui

import com.kewu.app.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.kewu.app.HapticKind
import com.kewu.app.haptic
import com.kewu.app.platformExitApp
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
 * - 一级页：没有更晚的 handler → 第一次按弹出提示，[EXIT_WINDOW_MS] 内再按 → 退出应用。
 *
 * 提示（2026-10-23 全局 coui 化）：原自建 inverseSurface 气泡改为**全局 coui Snackbar**
 * （[LocalKewuSnackbar]，根 Scaffold 宿主，位置/动画/配色全由 coui 提供），本函数不再绘制任何 UI。
 *
 * @param modifier 保留旧签名（MainScreen / LoginScreen / TeacherMainScreen 仍按定位传入）；
 *   Snackbar 由根宿主统一定位，此参数已不参与布局。
 */
@Composable
fun DoubleBackExitHint(@Suppress("UNUSED_PARAMETER") modifier: Modifier = Modifier) {
    var lastBackAt by remember { mutableStateOf(0L) }
    val snackbar = LocalKewuSnackbar.current
    val scope = rememberCoroutineScope()
    BackHandler {
        val now = Clock.System.now().toEpochMilliseconds()
        if (now - lastBackAt in 1 until EXIT_WINDOW_MS) {
            // 双击窗口内第二次按下：直接退出应用（进程结束，提示随之消失）
            platformExitApp()
        } else {
            // 第一次按下（或超过窗口的重新计次）：记时 + 极轻震动 + 弹出 coui 提示
            lastBackAt = now
            haptic(HapticKind.Tick)
            // 注意 showSnackbar 挂起到提示消失为止，必须另起协程，不能阻塞返回事件
            scope.launch { snackbar.toast("再次按下返回键退出应用") }
        }
    }
}

/** 双击退出的判定窗口（毫秒）。2s 是主流 App 通用值（微信/支付宝同款）。 */
private const val EXIT_WINDOW_MS = 2000L
