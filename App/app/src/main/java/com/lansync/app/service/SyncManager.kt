package com.lansync.app.service

import android.content.Context
import android.provider.Settings
import com.lansync.app.data.repository.SyncRepository
import com.lansync.app.domain.model.SyncState
import com.lansync.app.domain.usecase.SyncPhotosUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SyncManager — 同步核心逻辑，独立于 Android 组件生命周期。
 *
 * 背景：OPPO/ColorOS 后台 Freeze 会暂停 Foreground Service，导致
 * 同步进行到一半时卡住，USB 连接处理逻辑来不及执行就被冻结。
 *
 * 解决方案：
 * - 所有核心逻辑（扫描、两阶段过滤、上传）放在 SyncManager（Singleton）
 * - MainActivity 通过 USB_CONNECTED 广播直接触发 SyncManager.startSync()
 * - WifiSyncService 只负责保活（foreground notification），不承载业务逻辑
 * - 状态统一写入 [SyncStateHolder]，HomeViewModel 通过 WifiSyncService.syncStateFlow 观察（保持向后兼容）
 */
@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncRepository: SyncRepository,
    private val syncPhotosUseCase: SyncPhotosUseCase
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 对外暴露的状态流（内部用 SyncStateHolder 写入）
    val syncState: StateFlow<SyncState> = SyncStateHolder.asStateFlow

    // 调试日志流
    val debugLog: SharedFlow<String> = SyncStateHolder.asDebugLogFlow

    /**
     * 开始同步。
     * 可从任意上下文调用（MainActivity USB 广播、WifiSyncService、或直接 UI 触发）。
     */
    fun startSync() {
        val current = SyncStateHolder.syncStateFlow.value
        if (current is SyncState.Scanning || current is SyncState.Uploading) {
            return
        }
        scope.launch { performSync() }
    }

    /**
     * 停止同步
     */
    fun stopSync() {
        scope.cancel()
        SyncStateHolder.syncStateFlow.value = SyncState.Idle
    }

    private suspend fun performSync() {
        try {
            SyncStateHolder.syncStateFlow.value = SyncState.Scanning

            if (!syncRepository.isLoggedIn()) {
                val err = "未登录，请先登录"
                SyncStateHolder.syncStateFlow.value = SyncState.Error(err)
                return
            }

            val healthResult = syncRepository.healthCheck()
            if (healthResult.isFailure) {
                val err = "无法连接到服务器"
                SyncStateHolder.syncStateFlow.value = SyncState.Error(err)
                return
            }

            // 转发 UseCase 的调试日志
            scope.launch {
                syncPhotosUseCase.debugLogs.collect { logs ->
                    logs.lastOrNull()?.let { SyncStateHolder.emitDebugLog(it) }
                }
            }

            val photos = syncPhotosUseCase.scanLocalPhotos()
            if (photos.isEmpty()) {
                SyncStateHolder.syncStateFlow.value = SyncState.AllSynced
                return
            }

            val deviceId = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                    ?: "unknown_device"
            } catch (e: Exception) {
                "unknown_device"
            }

            syncPhotosUseCase.uploadPhotos(photos, deviceId).collect { state ->
                SyncStateHolder.syncStateFlow.value = state
            }
        } catch (e: Exception) {
            val err = "同步异常: ${e.message}"
            SyncStateHolder.syncStateFlow.value = SyncState.Error(err)
        }
    }
}