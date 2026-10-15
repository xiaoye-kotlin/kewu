package com.kewu.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.kewu.app.ui.MainScreen

// 课悟品牌色，后续界面可复用
val KewuBlue = Color(0xFF1450C8)
val KewuDeepBlue = Color(0xFF0B1E4B)
val KewuSky = Color(0xFF2F7BF0)

@Composable
fun App() {
    // 主界面：顶部品牌栏 + 内容区 + 底部苹果液态玻璃导航栏
    MainScreen()
}
