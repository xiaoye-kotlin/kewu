package com.kewu.app.ui

import androidx.compose.ui.graphics.vector.ImageVector
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Clear
import io.github.suqi8.coui.kmp.icon.extended.Lock

/**
 * 输入框末尾小图标（2026-10-23 全局 coui 化）：原 ImageVector.Builder 自绘的「清除 X」/「锁」
 * 已改为直接转发 coui-icons 原生字形。保留 `AppIcons.Clear` / `AppIcons.Lock` 符号名，
 * 旧调用点无需改动；新代码请直接用 `COUIIcons.*`（coui TextField 自带清除 / 密码切换，通常无需手放）。
 * 颜色仍由使用处 Icon(tint=...) 决定。
 */
internal object AppIcons {
    /** 清除图标（coui Clear）。 */
    val Clear: ImageVector
        get() = COUIIcons.Clear

    /** 锁图标（coui Lock）。 */
    val Lock: ImageVector
        get() = COUIIcons.Lock
}
