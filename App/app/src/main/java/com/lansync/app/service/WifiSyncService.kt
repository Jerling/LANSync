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

    // Service → ViewModel 事件通道（进程内 SharedFlow）
    // 注册到 companion object 的_currentInstance，HomeViewModel 通过静态属性访问
    private val _syncStateChannel = MutableSharedFlow<SyncState>(replay = 1)
    val syncStateChannel: SharedFlow<SyncState> = _syncStateChannel.asSharedFlow()

    // 日志通道：转发 UseCase 的调试日志
    private val _debugLogChannel = MutableSharedFlow<String>(extraBufferCapacity = 100)
    val debugLogChannel: SharedFlow<String> = _debugLogChannel.asSharedFlow()

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "wifi_sync_channel"

        // 当前运行中的 Service 实例（单例，通过它访问实例级 syncStateChannel）
        var currentInstance: WifiSyncService? = null
            private set

        const val ACTION_START_SYNC = "com.lansync.app.START_SYNC"
        const val ACTION_STOP_SYNC = "com.lansync.app.STOP_SYNC"

        // 静态 StateFlow：Service 和 HomeViewModel 都访问同一个实例，避免时序问题
        // init 时 currentInstance=null 也能正常初始化，不会像 SharedFlow 那样拿到空实例
        private val _syncStateFlow = MutableStateFlow<SyncState>(SyncState.Idle)
        val syncStateFlow: StateFlow<SyncState> = _syncStateFlow.asStateFlow()

        // 静态日志 channel，同理
        private val _debugLogFlow = MutableSharedFlow<String>(extraBufferCapacity = 100)
        val debugLogFlow: SharedFlow<String> = _debugLogFlow.asSharedFlow()

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
                // 写入静态 StateFlow，HomeViewModel 一定能收到
                _syncStateFlow.value = SyncState.Scanning
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
        currentInstance = null
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
                _syncStateFlow.value = SyncState.Error("未登录，请先登录")
                return
            }

            // 2. 检查服务器连接
            val healthResult = repository.healthCheck()
            if (healthResult.isFailure) {
                android.util.Log.e("WifiSyncService", "performSync: health check failed")
                _syncStateFlow.value = SyncState.Error("无法连接到服务器")
                return
            }
            android.util.Log.d("WifiSyncService", "performSync: health check OK")

            // 3. 收集 UseCase 的调试日志并转发到 UI
            serviceScope.launch {
                syncPhotosUseCase.debugLogs.collect { logs: List<String> ->
                    val lastLog = logs.lastOrNull()
                    if (lastLog != null) {
                        _debugLogFlow.emit(lastLog)
                    }
                }
            }

            // 4. 扫描本地照片
            _syncStateFlow.value = SyncState.Scanning
            val photos = syncPhotosUseCase.scanLocalPhotos()
            android.util.Log.d("WifiSyncService", "performSync: scanned ${photos.size} photos")

            if (photos.isEmpty()) {
                _syncStateFlow.value = SyncState.AllSynced
                updateNotification("没有新照片需要同步")
                stopSelf()
                return
            }

            // 5. 获取设备ID（部分设备可能抛出 SecurityException）
            val deviceId: String = try {
                Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
            } catch (e: Exception) {
                android.util.Log.e("WifiSyncService", "Failed to get device ID", e)
                "unknown_device"
            }

            try {
                syncPhotosUseCase.uploadPhotos(photos, deviceId).collect { state ->
                    try {
                        _syncStateFlow.value = state

                        when (state) {
                            is SyncState.Progress -> {
                                updateNotification("同步中: ${state.current}/${state.total}")
                            }
                            is SyncState.Completed -> {
                                updateNotification("同步完成")
                            }
                            is SyncState.AllSynced -> {
                                updateNotification("全部照片已同步，无需上传")
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
                _syncStateFlow.value = SyncState.Error("同步异常: ${e.message}")
                updateNotification("同步异常")
            } finally {
                stopSelf()
            }
        } catch (e: Exception) {
            android.util.Log.e("WifiSyncService", "performSync crashed", e)
            _syncStateFlow.value = SyncState.Error("同步异常: ${e.message}")
            updateNotification("同步异常")
            stopSelf()
        }
    }
}
