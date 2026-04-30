package com.lansync.app.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lansync.app.data.api.ApiClient
import com.lansync.app.data.local.SyncedFileDao
import com.lansync.app.data.local.TokenManager
import com.lansync.app.domain.model.*
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
    val isLoggedIn: Boolean = true,
    // 多选相关
    val isSelecting: Boolean = false,          // 是否处于多选模式
    val selectedIds: Set<String> = emptySet(), // 已选中的 photo id 集合
    // 操作中状态
    val isOperationInProgress: Boolean = false,
    val operationMessage: String? = null,      // 操作结果提示（成功或失败）
)

data class SelectedPhoto(
    val id: String,
    val name: String,
    val path: String,
    val type: String,
    val size: Long,
    val index: Int,
    val allPhotos: List<Triple<String, String, String>>, // id to path to name for swipe navigation
    val hasLocal: Boolean = false,           // 本地是否有同名文件
    val localUri: String? = null              // 本地文件 URI，有则优先打开
)

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val tokenManager: TokenManager,
    private val syncedFileDao: SyncedFileDao  // 用于查询本地文件
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
        val encoded = photoPath.replace("/", "%2F")
        val token = authToken ?: ""
        return "${baseUrl}api/gallery/thumb/$encoded?token=$token"
    }

    fun getPhotoUrl(photoPath: String): String {
        val token = authToken ?: ""
        return "${baseUrl}api/gallery/photo/$photoPath?token=$token"
    }

    fun selectPhoto(photo: GalleryPhoto, allPhotos: List<Triple<String, String, String>>) {
        _uiState.value = _uiState.value.copy(
            selectedPhoto = SelectedPhoto(
                id = photo.id,
                name = photo.name,
                path = photo.path,
                type = photo.type,
                size = photo.size,
                index = allPhotos.indexOfFirst { it.first == photo.id },
                allPhotos = allPhotos,
                hasLocal = false,
                localUri = null
            )
        )
    }

    /** 检查本地是否有同名文件，有则更新 selectedPhoto */
    suspend fun checkAndUpdateLocalPhoto() {
        val selected = _uiState.value.selectedPhoto ?: return
        // 从 allPhotos 中查找同 name+size 的本地记录
        // 由于 allPhotos 只有 id+path，我们用 name 和 size 在 Room 中查找
        val local = syncedFileDao.findByNameAndSize(selected.name, selected.size)
        if (local != null && !local.filePath.isNullOrEmpty()) {
            _uiState.value = _uiState.value.copy(
                selectedPhoto = selected.copy(hasLocal = true, localUri = local.filePath)
            )
        }
    }

    fun clearSelectedPhoto() {
        _uiState.value = _uiState.value.copy(selectedPhoto = null)
    }

    fun navigatePhoto(direction: Int) {
        val current = _uiState.value.selectedPhoto ?: return
        val newIndex = (current.index + direction).coerceIn(0, current.allPhotos.size - 1)
        val (newId, newPath, newName) = current.allPhotos[newIndex]
        _uiState.value = _uiState.value.copy(
            selectedPhoto = current.copy(id = newId, name = newName, path = newPath, index = newIndex)
        )
    }

    // ==================== 多选操作 ====================

    /** 长按缩略图时触发：进入多选模式并选中该项 */
    fun onPhotoLongPress(photo: GalleryPhoto) {
        _uiState.value = _uiState.value.copy(
            isSelecting = true,
            selectedIds = setOf(photo.id)
        )
    }

    /** 点击缩略图：在多选模式下切换选中，非多选模式下打开预览 */
    fun onPhotoClick(photo: GalleryPhoto, allPhotos: List<Triple<String, String, String>>) {
        val state = _uiState.value
        if (state.isSelecting) {
            // 切换选中状态
            val newSelected = if (photo.id in state.selectedIds) {
                state.selectedIds - photo.id
            } else {
                state.selectedIds + photo.id
            }
            // 如果取消选中后为空，退出多选模式
            _uiState.value = state.copy(
                isSelecting = newSelected.isNotEmpty(),
                selectedIds = newSelected
            )
        } else {
            selectPhoto(photo, allPhotos)
            // 异步查询本地是否有同名文件
            viewModelScope.launch {
                checkAndUpdateLocalPhoto()
            }
        }
    }

    /** 全选当前组内所有照片 */
    fun selectAllInGroup(group: GalleryGroup) {
        val allIds = group.photos.map { it.id }.toSet()
        _uiState.value = _uiState.value.copy(selectedIds = allIds)
    }

    /** 退出多选模式 */
    fun clearSelection() {
        _uiState.value = _uiState.value.copy(
            isSelecting = false,
            selectedIds = emptySet()
        )
    }

    /** 选中数量 */
    val selectedCount: Int get() = _uiState.value.selectedIds.size

    /** 批量删除 */
    fun deleteSelected(onSuccess: () -> Unit = {}) {
        val state = _uiState.value
        if (state.selectedIds.isEmpty()) return

        val paths = mutableListOf<String>()
        for (group in state.groups) {
            for (photo in group.photos) {
                if (photo.id in state.selectedIds) {
                    paths.add(photo.path)
                }
            }
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isOperationInProgress = true, operationMessage = null)
            try {
                val response = ApiClient.getApi().deletePhotos(DeletePhotosRequest(paths))
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    val msg = if (body.failed.isNotEmpty()) {
                        "删除完成：${body.deleted.size} 张成功，${body.failed.size} 张失败"
                    } else {
                        "已删除 ${body.deleted.size} 张照片"
                    }
                    _uiState.value = _uiState.value.copy(
                        isOperationInProgress = false,
                        operationMessage = msg,
                        isSelecting = false,
                        selectedIds = emptySet()
                    )
                    loadGallery()
                    onSuccess()
                } else {
                    _uiState.value = _uiState.value.copy(
                        isOperationInProgress = false,
                        operationMessage = "删除失败: ${response.code()}"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isOperationInProgress = false,
                    operationMessage = "删除失败: ${e.message}"
                )
            }
        }
    }

    /** 重命名 */
    fun renamePhoto(oldPath: String, newName: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isOperationInProgress = true, operationMessage = null)
            try {
                val response = ApiClient.getApi().renamePhoto(RenamePhotoRequest(oldPath, newName))
                if (response.isSuccessful && response.body() != null) {
                    _uiState.value = _uiState.value.copy(
                        isOperationInProgress = false,
                        operationMessage = "重命名成功",
                        isSelecting = false,
                        selectedIds = emptySet()
                    )
                    loadGallery()
                    onSuccess()
                } else {
                    val errMsg = when (response.code()) {
                        404 -> "文件不存在"
                        409 -> "文件名已存在"
                        else -> "重命名失败: ${response.code()}"
                    }
                    _uiState.value = _uiState.value.copy(
                        isOperationInProgress = false,
                        operationMessage = errMsg
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isOperationInProgress = false,
                    operationMessage = "重命名失败: ${e.message}"
                )
            }
        }
    }

    fun clearOperationMessage() {
        _uiState.value = _uiState.value.copy(operationMessage = null)
    }
}
