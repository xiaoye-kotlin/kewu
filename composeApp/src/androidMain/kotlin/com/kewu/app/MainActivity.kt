package com.kewu.app

import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {

    // 全屏沉浸：隐藏底部系统导航小白条（手势条）。
    // BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE：需要时可从底部临时唤出，系统手势（回桌面/返回）不受影响。
    private fun hideSystemBars() {
        window.insetsController?.let { c ->
            c.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsets.Type.navigationBars())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContext = applicationContext // 供 haptic() 取 Vibrator
        enableEdgeToEdge()
        hideSystemBars() // 进入软件即全屏，底部不留白色系统条
        setContent {
            App()
        }
    }

    override fun onResume() {
        super.onResume()
        // 从后台返回时再藏一次（部分 OEM 会在切后台时把导航条放回来）
        hideSystemBars()
    }
}
