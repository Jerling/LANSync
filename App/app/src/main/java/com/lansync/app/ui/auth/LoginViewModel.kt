package com.lansync.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lansync.app.data.api.ApiClient
import com.lansync.app.data.local.TokenManager
import com.lansync.app.domain.model.LoginResponse
import com.lansync.app.domain.usecase.LoginUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val isLoggedIn: Boolean = false,
    val savedUsername: String = "",
    val savedPassword: String = "",
    val savedServerUrl: String = ""
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val loginUseCase: LoginUseCase,
    private val tokenManager: TokenManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        loadSavedCredentials()
    }

    private fun loadSavedCredentials() {
        viewModelScope.launch {
            val savedUsername = tokenManager.usernameFlow.first() ?: ""
            val savedPassword = tokenManager.getPassword() ?: ""
            val savedServerUrl = tokenManager.serverUrlFlow.first() ?: ""
            _uiState.value = _uiState.value.copy(
                savedUsername = savedUsername,
                savedPassword = savedPassword,
                savedServerUrl = savedServerUrl
            )
        }
    }

    fun login(username: String, password: String, serverUrl: String = "") {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            // 如果用户输入了服务器地址则应用
            if (serverUrl.isNotBlank()) {
                ApiClient.setBaseUrl(serverUrl)
                tokenManager.saveServerUrl(serverUrl)
            }

            val result = loginUseCase(username, password)

            result.fold(
                onSuccess = { response ->
                    tokenManager.saveUsername(username)
                    tokenManager.savePassword(password)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isLoggedIn = true,
                        error = null,
                        savedUsername = username,
                        savedPassword = password,
                        savedServerUrl = serverUrl
                    )
                },
                onFailure = { exception ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = exception.message ?: "登录失败"
                    )
                }
            )
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
