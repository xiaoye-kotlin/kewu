package com.kewu.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    companion object {
        private var audioPermissionCallback: ((Boolean) -> Unit)? = null
        private var cameraPermissionCallback: ((Boolean) -> Unit)? = null
        private var notificationPermissionCallback: ((Boolean) -> Unit)? = null
        private var pickCallback: ((List<Uri>) -> Unit)? = null
    }

    // 作业附件：系统照片选择器（最多 9 张）/ 任意文件多选（2026-10-26）
    private val pickImagesLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { uris ->
        pickCallback?.invoke(uris)
        pickCallback = null
    }

    private val pickDocumentsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        pickCallback?.invoke(uris)
        pickCallback = null
    }

    // 助教发图「拍照」（2026-11-03）：系统相机拍到 App 缓存目录（FileProvider 授权），回调本地路径；取消 / 失败回调 null
    private var cameraFile: java.io.File? = null
    private var cameraCallback: ((String?) -> Unit)? = null
    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val f = cameraFile
        val cb = cameraCallback
        cameraFile = null
        cameraCallback = null
        val path = f?.takeIf { ok && it.exists() && it.length() > 0 }?.absolutePath
        if (path == null) f?.delete()
        cb?.invoke(path)
    }

    fun takePhoto(callback: (String?) -> Unit) {
        requestCameraPermission { granted ->
            if (!granted) { callback(null); return@requestCameraPermission }
            try {
                val dir = java.io.File(cacheDir, "camera").apply { mkdirs() }
                val f = java.io.File(dir, "cam_${System.currentTimeMillis()}.jpg")
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                cameraFile = f
                cameraCallback = callback
                takePictureLauncher.launch(uri)
            } catch (_: Exception) {
                cameraFile = null
                cameraCallback = null
                callback(null)
            }
        }
    }

    fun pickImages(callback: (List<Uri>) -> Unit) {
        pickCallback = callback
        pickImagesLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun pickDocuments(callback: (List<Uri>) -> Unit) {
        pickCallback = callback
        pickDocumentsLauncher.launch(arrayOf("*/*"))
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        audioPermissionCallback?.invoke(isGranted)
        audioPermissionCallback = null
    }

    private val requestCameraLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        cameraPermissionCallback?.invoke(isGranted)
        cameraPermissionCallback = null
    }

    private val requestNotificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        notificationPermissionCallback?.invoke(isGranted)
        notificationPermissionCallback = null
    }

    fun requestAudioPermission(callback: (Boolean) -> Unit) {
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            callback(true)
        } else {
            audioPermissionCallback = callback
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // 扫码签到的相机权限（ScanPage 经 requestCameraPermission expect 调到这）
    fun requestCameraPermission(callback: (Boolean) -> Unit) {
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            callback(true)
        } else {
            cameraPermissionCallback = callback
            requestCameraLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // 通知权限（通知设置页经 requestNotificationPermission expect 调到这；Android 13+ 运行时申请）
    fun requestNotificationPermission(callback: (Boolean) -> Unit) {
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            callback(true)
        } else {
            notificationPermissionCallback = callback
            requestNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContext = applicationContext // 供 haptic() 取 Vibrator
        CrashLog.install(applicationContext)
        appActivity = this // 供录音权限请求等需要 Activity 的运行时调用
        CameraCapture.launcher = { cb -> (appActivity as? MainActivity)?.takePhoto(cb) ?: cb(null) }
        // 全屏视频手势：亮度只改本窗口（不动系统设置、无需权限），音量为媒体音量
        val resolver = applicationContext.contentResolver
        DeviceLevels.brightness = {
            val act = appActivity as? MainActivity
            val w = act?.window?.attributes?.screenBrightness ?: -1f
            if (w >= 0f) w else runCatching {
                android.provider.Settings.System.getInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
            }.getOrDefault(0.5f).coerceIn(0f, 1f)
        }
        DeviceLevels.setBrightness = { v ->
            (appActivity as? MainActivity)?.window?.let { w ->
                w.attributes = w.attributes.apply { screenBrightness = v.coerceIn(0.01f, 1f) }
            }
        }
        DeviceLevels.resetBrightness = {
            (appActivity as? MainActivity)?.window?.let { w ->
                w.attributes = w.attributes.apply {
                    screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
        val audio = applicationContext.getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        DeviceLevels.volume = {
            val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / max
        }
        DeviceLevels.setVolume = { v ->
            val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val target = kotlin.math.round(v.coerceIn(0f, 1f) * max).toInt()
            if (target != audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)) {
                runCatching { audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0) }
            }
        }
        // 只开 edge-to-edge：系统导航小白条（手势条）默认显示，底部区域透明，
        // 内容直接延伸到屏幕底边（托盘底已留 24dp 空隙，小白条悬在背景上不挡标签）。
        // 早期版本在这里 hide(navigationBars()) 全屏沉浸，用户要求改回「不隐藏、只透明」。
        enableEdgeToEdge()
        // ★ enableEdgeToEdge 会把 isNavigationBarContrastEnforced / isStatusBarContrastEnforced
        //   置 true —— 系统会在透明系统栏后面补一层半透明 scrim「保证对比度」，
        //   于是小白条那一带看起来灰蒙蒙的不够透（微信/系统默认观感是纯净透明）。
        //   显式关掉：系统栏区域只剩 App 自己的 M3 底色（MainScreen 底部 24dp 幕布），全透。
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
        setContent {
            App()
        }
    }

    // 2026-10-26：释放静态引用（旧版 appActivity / 各回调从不置空，Activity 销毁后仍被持有）
    override fun onDestroy() {
        if (appActivity === this) appActivity = null
        audioPermissionCallback = null
        cameraPermissionCallback = null
        notificationPermissionCallback = null
        pickCallback = null
        super.onDestroy()
    }
}
