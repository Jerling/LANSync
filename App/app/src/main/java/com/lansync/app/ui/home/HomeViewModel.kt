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

    init {
        loadUserInfo()
        observeServiceSyncState()
    }

    private fun observeServiceSyncState() {
        viewModelScope.launch {
            WifiSyncService.syncStateChannel.collect { state ->
                _uiState.value = _uiState.value.copy(syncState = state)
                // 当同步完成或出错时，重置服务运行状态
                if (state is SyncState.Completed || state is SyncState.Error) {
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
        val context = getApplication<Application>()
        WifiSyncService.startService(context)
        _uiState.value = _uiState.value.copy(isServiceRunning = true)
    }

    fun stopSyncService() {
        val context = getApplication<Application>()
        WifiSyncService.stopService(context)
        _uiState.value = _uiState.value.copy(isServiceRunning = false)
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
}
