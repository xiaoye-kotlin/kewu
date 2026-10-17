package com.kewu.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.kewu.app.ui.LoginScreen
import com.kewu.app.ui.MainScreen

// 课悟品牌色，后续界面可复用
val KewuBlue = Color(0xFF1450C8)
val KewuDeepBlue = Color(0xFF0B1E4B)
val KewuSky = Color(0xFF2F7BF0)
// 课堂主页「绿色勾勾」主题色（参考 KernelSU 主页：已验证/已完成态用绿）
val KewuGreen = Color(0xFF2BD17A)

@Composable
fun App() {
    // 显式套一层 MaterialTheme：页面一律用它的默认 colorScheme / typography / shapes，
    // 不再自定义背景与文字色（绿色只作为品牌强调色用在「勾勾」徽章上）。
    MaterialTheme {
        // 启动首屏 = 登录/注册（液态玻璃 + coui）。mock 登录态：登录成功后进入主界面，
        // 在「我的」页点「退出登录」会回到这里（App 级持有 loggedIn，保证跨 tab 不丢）。
        var loggedIn by remember { mutableStateOf(false) }
        if (loggedIn) {
            MainScreen(onLogout = { loggedIn = false })
        } else {
            LoginScreen(onLogin = { loggedIn = true })
        }
    }
}
