package com.lansync.app.service

import android.app.*
import android.content.*
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * WifiSyncService — 前台服务，仅负责保活（foreground notification）。
 *
 * 核心同步逻辑已移至 [SyncManager]。
 * 这样做是为了规避 OPPO/ColorOS 后台 Freeze 对 Service 生命周期的干扰：
 * - 即使 Freezer 暂停了 Service，只要 App 进程还在，SyncManager 就能继续运行
 * - MainActivity 通过 USB_CONNECTED 广播可以直接触发 SyncManager.startSync()
 * - WifiSyncService 只维持一个前台通知，不承载任何同步业务逻辑
 */
@AndroidEntryPoint
class WifiSyncService : Service() {

    @Inject
    lateinit var syncManager: SyncManager

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "wifi_sync_channel"

        var currentInstance: WifiSyncService? = null
            private set

        const val ACTION_START_SYNC = "com.lansync.app.START_SYNC"
        const val ACTION_STOP_SYNC = "com.lansync.app.STOP_SYNC"

        // 静态 StateFlow：HomeViewModel 通过这个观察同步状态
        // 内部实现在 SyncStateHolder，由 SyncManager 写入
        val syncStateFlow = SyncStateHolder.asStateFlow

        // 静态日志 channel：HomeViewModel 通过这个观察调试日志
        val debugLogFlow = SyncStateHolder.asDebugLogFlow

        /** 供外部（如 GalleryViewModel）写入调试日志 */
        fun emitDebugLog(msg: String) {
            SyncStateHolder.emitDebugLog(msg)
        }

        fun startService(context: Context) {
            val intent = Intent(context, WifiSyncService::class.java).apply {
                action = ACTION_START_SYNC
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, WifiSyncService::class.java).apply {
                action = ACTION_STOP_SYNC
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        currentInstance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SYNC -> {
                startForeground(NOTIFICATION_ID, createNotification("同步服务运行中"))
                // 触发 SyncManager 执行同步（不依赖 Service 生命周期）
                syncManager.startSync()
            }
            ACTION_STOP_SYNC -> {
                syncManager.stopSync()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        currentInstance = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WiFi 同步服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "当连接到指定 WiFi 时自动同步照片"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LANSync")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}