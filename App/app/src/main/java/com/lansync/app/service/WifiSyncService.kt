package com.lansync.app.service

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.lansync.app.R
import com.lansync.app.data.local.TokenManager
import com.lansync.app.data.repository.SyncRepository
import com.lansync.app.domain.model.SyncState
import com.lansync.app.domain.model.WifiState
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
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiManager: WifiManager? = null
    private var targetWifiSsid: String = ""

    private val _wifiState = MutableStateFlow<WifiState>(WifiState.Disconnected)
    val wifiState: StateFlow<WifiState> = _wifiState.asStateFlow()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "wifi_sync_channel"

        const val ACTION_START_SYNC = "com.lansync.app.START_SYNC"
        const val ACTION_STOP_SYNC = "com.lansync.app.STOP_SYNC"
        const val EXTRA_TARGET_WIFI_SSID = "target_wifi_ssid"

        const val ACTION_SYNC_STATE = "com.lansync.app.SYNC_STATE"
        const val EXTRA_SYNC_STATE = "sync_state"
        const val EXTRA_SYNC_PROGRESS_CURRENT = "progress_current"
        const val EXTRA_SYNC_PROGRESS_TOTAL = "progress_total"
        const val EXTRA_SYNC_MESSAGE = "sync_message"

        // Local broadcast to update UI
        private val _syncStateBroadcast = MutableSharedFlow<SyncState>(replay = 1)
        val syncStateBroadcast: SharedFlow<SyncState> = _syncStateBroadcast.asSharedFlow()

        fun startService(context: Context, targetWifiSsid: String) {
            val intent = Intent(context, WifiSyncService::class.java).apply {
                action = ACTION_START_SYNC
                putExtra(EXTRA_TARGET_WIFI_SSID, targetWifiSsid)
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
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SYNC -> {
                targetWifiSsid = intent.getStringExtra(EXTRA_TARGET_WIFI_SSID) ?: ""
                startForeground(NOTIFICATION_ID, createNotification("同步服务运行中"))
                // 直接开始同步，不等待WiFi
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
        networkCallback?.let {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(it)
        }
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

    @SuppressLint("MissingPermission")
    private fun startNetworkCallback() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                checkWifiAndSync()
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                _wifiState.value = WifiState.Disconnected
                // WiFi 断开，停止服务
                updateNotification("WiFi 断开，停止同步")
                serviceScope.launch {
                    delay(2000)
                    stopSelf()
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                checkWifiAndSync()
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        connectivityManager.registerNetworkCallback(request, networkCallback!!)
    }

    @SuppressLint("MissingPermission")
    private fun checkWifiAndSync() {
        val wifiInfo: WifiInfo? = wifiManager?.connectionInfo

        if (wifiInfo == null || wifiInfo.networkId == -1) {
            _wifiState.value = WifiState.Disconnected
            return
        }

        val currentSsid = wifiInfo.ssid?.removeSurrounding("\"") ?: ""

        // 检查是否是目标 WiFi
        if (currentSsid == targetWifiSsid || targetWifiSsid.isEmpty()) {
            _wifiState.value = WifiState.Connected
            updateNotification("已连接 $currentSsid，开始同步...")
            serviceScope.launch {
                performSync()
            }
        } else {
            _wifiState.value = WifiState.WrongNetwork(currentSsid)
            // 非目标 WiFi，不同步
        }
    }

    private suspend fun performSync() {
        android.util.Log.d("WifiSyncService", "performSync: starting")
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

        // 4. 执行同步
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

        syncPhotosUseCase.uploadPhotos(photos, deviceId).collect { state ->
            _syncState.value = state
            serviceScope.launch { _syncStateBroadcast.emit(state) }

            when (state) {
                is SyncState.Progress -> {
                    updateNotification("同步中: ${state.current}/${state.total}")
                }
                is SyncState.Completed -> {
                    updateNotification("同步完成: ${photos.size} 个文件")
                    // 同步完成后停止服务
                    stopSelf()
                }
                is SyncState.Error -> {
                    updateNotification(state.message)
                    // 出错后也停止服务
                    stopSelf()
                }
                else -> {}
            }
        }
    }
}
