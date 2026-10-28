package com.kewu.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * 麦克风前台服务（2026-10-26）：录音期间挂一条常驻通知，让系统允许 App 在锁屏 / 后台继续读麦克风。
 * Android 9 起后台 App 读麦克风只拿到静音；Android 14 起还必须声明 foregroundServiceType=microphone。
 * 只负责「保活 + 告知用户正在录音」，不参与录音本身；任何启动失败都静默降级（前台仍可正常录）。
 */
class RecordingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "课堂录音", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "录音进行中时显示，锁屏后也能继续录音"
                },
            )
            val open = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val n: Notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("课悟正在录音")
                .setContentText("锁屏后录音会继续，回到课悟可停止并上传")
                .setOngoing(true)
                .setContentIntent(open)
                .build()
            startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "kewu_recording"
        private const val NOTIFY_ID = 3101

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, RecordingService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RecordingService::class.java)) }
        }
    }
}
