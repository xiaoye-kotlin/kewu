package com.kewu.app.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage

/**
 * Android 真机扫码：CameraX 预览 + ML Kit 条码识别（内置模型，离线可用）。
 * 只触发一次 [onScanned]；[enabled] 置 false 时暂停识别（出了结果 / 重试前），
 * 恢复 true 时重新 armed。页面销毁自动解绑相机并释放 ML Kit 客户端。
 */
@Composable
actual fun QrScannerView(onScanned: (String) -> Unit, modifier: Modifier, enabled: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanner = remember { BarcodeScanning.getClient() }
    var handled by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    // ★ 2026-10-26：分析器只在首次 update 时创建，直接读参数会永远停在首次组合的值（传 enabled=false 暂停不了）
    val enabledNow by rememberUpdatedState(enabled)
    val onScannedNow by rememberUpdatedState(onScanned)
    // 识别放后台单线程（旧版跑在主线程执行器上，和 UI 抢帧）
    val analyzerExecutor = remember { java.util.concurrent.Executors.newSingleThreadExecutor() }

    // 重新进入 Scanning 态时解除一次性锁
    LaunchedEffect(enabled) { if (enabled) handled = false }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            provider?.unbindAll()
            scanner.close()
            analyzerExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { PreviewView(it).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } },
        update = { previewView ->
            if (provider != null) return@AndroidView
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                    val media = imageProxy.image
                    if (media == null || !enabledNow || handled) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    val input = InputImage.fromMediaImage(media, imageProxy.imageInfo.rotationDegrees)
                    scanner.process(input)
                        .addOnSuccessListener { barcodes ->
                            val value = barcodes.firstOrNull()?.rawValue
                            if (!handled && value != null) {
                                handled = true
                                onScannedNow(value)
                            }
                        }
                        .addOnCompleteListener { imageProxy.close() }
                }
                try {
                    p.unbindAll()
                    p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (_: Exception) {
                    // 相机被占用 / 无相机等：静默，页面其它部分有兜底提示
                }
            }, ContextCompat.getMainExecutor(context))
        },
    )
}
