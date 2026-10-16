package com.kewu.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
        // 主界面：顶部品牌栏 + 内容区 + 底部苹果液态玻璃导航栏
        MainScreen()
    }
}
