package com.lansync.app.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lansync.app.data.local.TokenManager
import com.lansync.app.data.repository.SyncRepository
import com.lansync.app.domain.model.*
import com.lansync.app.domain.usecase.SyncPhotosUseCase
import com.lansync.app.service.WifiSyncService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

data class HomeUiState(
    val isLoggedIn: Boolean = false,
    val username: String = "",
    val serverUrl: String = "",
    val syncState: SyncState = SyncState.Idle,
    val isServiceRunning: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    application: Application,
    private val tokenManager: TokenManager,
    private val repository: SyncRepository,
    private val syncPhotosUseCase: SyncPhotosUseCase
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // 调试日志列表（最多 50 条）
    private val _debugLogs = MutableStateFlow<List<String>>(emptyList())
    val debugLogs: StateFlow<List<String>> = _debugLogs.asStateFlow()

    init {
        loadUserInfo()
        observeServiceSyncState()
        observeDebugLogs()
    }

    private fun observeDebugLogs() {
        viewModelScope.launch {
            WifiSyncService.debugLogFlow.collect { log ->
                _debugLogs.value = _debugLogs.value + log
                if (_debugLogs.value.size > 50) {
                    _debugLogs.value = _debugLogs.value.takeLast(50)
                }
            }
        }
    }

    private fun observeServiceSyncState() {
        viewModelScope.launch {
            WifiSyncService.syncStateFlow.collect { state ->
                _uiState.value = _uiState.value.copy(syncState = state)
                // 当同步完成或出错时，重置服务运行状态
                if (state is SyncState.Completed || state is SyncState.Error || state is SyncState.AllSynced) {
                    _uiState.value = _uiState.value.copy(isServiceRunning = false)
                }
            }
        }
    }

    private fun loadUserInfo() {
        viewModelScope.launch {
            tokenManager.usernameFlow.collect { username ->
                _uiState.value = _uiState.value.copy(username = username ?: "")
            }
        }
        viewModelScope.launch {
            tokenManager.serverUrlFlow.collect { url ->
                _uiState.value = _uiState.value.copy(serverUrl = url ?: "")
            }
        }
    }

    fun startSyncService() {
        // 防止重复启动：已经在扫描或上传中则忽略
        if (_uiState.value.syncState is SyncState.Scanning || _uiState.value.syncState is SyncState.Uploading) {
            return
        }
        val context = getApplication<Application>()
        _uiState.value = _uiState.value.copy(isServiceRunning = true, syncState = SyncState.Scanning)
        WifiSyncService.startService(context)
    }

    fun stopSyncService() {
        val context = getApplication<Application>()
        _uiState.value = _uiState.value.copy(isServiceRunning = false, syncState = SyncState.Idle)
        WifiSyncService.stopService(context)
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            _uiState.value = _uiState.value.copy(isLoggedIn = false)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearDebugLogs() {
        _debugLogs.value = emptyList()
    }
}
