package com.kewu.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    // 运行时授权通道必须在 onCreate（早于 RESUMED）就登记好，
    // 否则在 Compose 组合阶段登记会因为 Activity 已 RESUMED 直接抛异常
    private var pendingPermission: ((Boolean) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> pendingPermission?.invoke(granted).also { pendingPermission = null } }

    // 供权限页调用：真正弹系统授权框
    fun requestRuntimePermission(name: String, onResult: (Boolean) -> Unit) {
        pendingPermission = onResult
        permissionLauncher.launch(name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            App()
        }
    }
}
