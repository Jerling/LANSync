package com.lansync.app.service

import com.lansync.app.domain.model.SyncState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 同步状态和日志的静态Flow持有者。
 *
 * 为什么单独拎出来：
 * - [WifiSyncService] 需要这些Flow来向 UI 透传状态
 * - [SyncManager] 需要向这些Flow写入状态
 * - 如果放在 WifiSyncService.companion object，SyncManager 就变成了 WifiSyncService 的隐式依赖
 *
 * 解决方案：放在独立的 object 中，两方都引用它，打破循环依赖。
 */
object SyncStateHolder {
    val syncStateFlow: MutableStateFlow<SyncState> = MutableStateFlow(SyncState.Idle)
    val asStateFlow: StateFlow<SyncState> = syncStateFlow.asStateFlow()

    val debugLogFlow: MutableSharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 100)
    val asDebugLogFlow: SharedFlow<String> = debugLogFlow.asSharedFlow()

    fun emitDebugLog(msg: String) {
        debugLogFlow.tryEmit(msg)
    }
}