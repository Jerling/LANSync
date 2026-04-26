package com.lansync.app.service

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.lansync.app.R
import com.lansync.app.data.local.TokenManager
import com.lansync.app.data.repository.SyncRepository
import com.lansync.app.domain.model.SyncState
import com.lansync.app.domain.usecase.SyncPhotosUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class WifiSyncService : Service() {

    @Inject
    lateinit var tokenManager: TokenManager

    @Inject
    lateinit var syncPhotosUseCase: SyncPhotosUseCase

    @Inject
    lateinit var repository: SyncRepository

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "wifi_sync_channel"

        const val ACTION_START_SYNC = "com.lansync.app.START_SYNC"
        const val ACTION_STOP_SYNC = "com.lansync.app.STOP_SYNC"

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

        // Service → ViewModel 事件通道（进程内 SharedFlow，非 Android LocalBroadcast）
        private val _syncStateChannel = MutableSharedFlow<SyncState>(replay = 1)
        val syncStateChannel: SharedFlow<SyncState> = _syncStateChannel.asSharedFlow()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SYNC -> {
                startForeground(NOTIFICATION_ID, createNotification("同步服务运行中"))
                serviceScope.launch {
                    performSync()
                }
            }
            ACTION_STOP_SYNC -> {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
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

    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private suspend fun performSync() {
        android.util.Log.d("WifiSyncService", "performSync: starting")
        try {
            // 1. 检查是否已登录
            if (!repository.isLoggedIn()) {
                android.util.Log.e("WifiSyncService", "performSync: not logged in")
                _syncState.value = SyncState.Error("未登录，请先登录")
                return
            }

            // 2. 检查服务器连接
            val healthResult = repository.healthCheck()
            if (healthResult.isFailure) {
                android.util.Log.e("WifiSyncService", "performSync: health check failed")
                _syncState.value = SyncState.Error("无法连接到服务器")
                return
            }
            android.util.Log.d("WifiSyncService", "performSync: health check OK")

            // 3. 扫描本地照片
            _syncState.value = SyncState.Scanning
            val photos = syncPhotosUseCase.scanLocalPhotos()
            android.util.Log.d("WifiSyncService", "performSync: scanned ${photos.size} photos")

            if (photos.isEmpty()) {
                _syncState.value = SyncState.Completed
                updateNotification("没有新照片需要同步")
                return
            }

            // 4. 获取设备ID（部分设备可能抛出 SecurityException）
            val deviceId: String = try {
                Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
            } catch (e: Exception) {
                android.util.Log.e("WifiSyncService", "Failed to get device ID", e)
                "unknown_device"
            }

            try {
                syncPhotosUseCase.uploadPhotos(photos, deviceId).collect { state ->
                    try {
                        _syncState.value = state
                        serviceScope.launch { _syncStateChannel.emit(state) }

                        when (state) {
                            is SyncState.Progress -> {
                                updateNotification("同步中: ${state.current}/${state.total}")
                            }
                            is SyncState.Completed -> {
                                updateNotification("同步完成: ${photos.size} 个文件")
                            }
                            is SyncState.Error -> {
                                updateNotification(state.message)
                            }
                            else -> {}
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("WifiSyncService", "Error inside collect lambda", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("WifiSyncService", "Flow collection failed", e)
                _syncState.value = SyncState.Error("同步异常: ${e.message}")
                updateNotification("同步异常")
                stopSelf()
            }
        } catch (e: Exception) {
            android.util.Log.e("WifiSyncService", "performSync crashed", e)
            _syncState.value = SyncState.Error("同步异常: ${e.message}")
            updateNotification("同步异常")
            stopSelf()
        }
    }
}
