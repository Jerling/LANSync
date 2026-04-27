package com.lansync.app.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lansync.app.data.api.ApiClient
import com.lansync.app.data.local.TokenManager
import com.lansync.app.domain.model.GalleryGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GalleryUiState(
    val isLoading: Boolean = false,
    val groups: List<GalleryGroup> = emptyList(),
    val totalCount: Int = 0,
    val error: String? = null,
    val selectedPhoto: SelectedPhoto? = null,
    val isLoggedIn: Boolean = true
)

data class SelectedPhoto(
    val id: String,
    val name: String,
    val path: String,
    val type: String,
    val size: Long,
    val index: Int,
    val allPhotos: List<Pair<String, String>> // id to path map for swipe navigation
)

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val tokenManager: TokenManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    private val baseUrl: String
        get() = ApiClient.getBaseUrl()

    private var authToken: String? = null

    init {
        checkLoginAndLoad()
        viewModelScope.launch {
            tokenManager.tokenFlow.collect { token ->
                authToken = token
            }
        }
    }

    private fun checkLoginAndLoad() {
        viewModelScope.launch {
            tokenManager.tokenFlow.collect { token ->
                if (token.isNullOrEmpty()) {
                    _uiState.value = _uiState.value.copy(isLoggedIn = false, error = "请先登录")
                } else {
                    _uiState.value = _uiState.value.copy(isLoggedIn = true)
                    loadGallery()
                }
            }
        }
    }

    fun loadGallery() {
        viewModelScope.launch {
            if (!_uiState.value.isLoggedIn) {
                _uiState.value = _uiState.value.copy(error = "请先登录")
                return@launch
            }
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val response = ApiClient.getApi().getGalleryList()
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        groups = body.groups,
                        totalCount = body.totalCount
                    )
                } else {
                    val errMsg = when (response.code()) {
                        401 -> "登录已过期，请重新登录"
                        else -> "加载失败: ${response.code()}"
                    }
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = errMsg
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "网络错误: ${e.message}"
                )
            }
        }
    }

    fun getThumbUrl(photoPath: String): String {
        // photoPath 格式: 2026/04/26/IMG20260426131925.jpg
        // Coil 请求不走 Retrofit，需要手动把 / 编码为 %2F
        val encoded = photoPath.replace("/", "%2F")
        val token = authToken ?: ""
        return "${baseUrl}api/gallery/thumb/$encoded?token=$token"
    }

    fun getPhotoUrl(photoPath: String): String {
        val token = authToken ?: ""
        return "${baseUrl}api/gallery/photo/$photoPath?token=$token"
    }

    fun selectPhoto(photo: com.lansync.app.domain.model.GalleryPhoto, allPhotos: List<Pair<String, String>>) {
        _uiState.value = _uiState.value.copy(
            selectedPhoto = SelectedPhoto(
                id = photo.id,
                name = photo.name,
                path = photo.path,
                type = photo.type,
                size = photo.size,
                index = allPhotos.indexOfFirst { it.first == photo.id },
                allPhotos = allPhotos
            )
        )
    }

    fun clearSelectedPhoto() {
        _uiState.value = _uiState.value.copy(selectedPhoto = null)
    }

    fun navigatePhoto(direction: Int) {
        val current = _uiState.value.selectedPhoto ?: return
        val newIndex = (current.index + direction).coerceIn(0, current.allPhotos.size - 1)
        val (newId, newPath) = current.allPhotos[newIndex]
        _uiState.value = _uiState.value.copy(
            selectedPhoto = current.copy(id = newId, path = newPath, index = newIndex)
        )
    }
}
