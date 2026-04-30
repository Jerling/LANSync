package com.lansync.app.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lansync.app.data.api.ApiClient
import com.lansync.app.data.local.SyncedFileDao
import com.lansync.app.data.local.TokenManager
import com.lansync.app.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
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
    // 批量下载状态
    val isBatchDownloading: Boolean = false,
    val batchDownloadCurrent: Int = 0,          // 当前下载到第几张
    val batchDownloadTotal: Int = 0,            // 总数
    val batchDownloadProgress: Int = 0,         // 当前文件下载进度 0-100
    val batchDownloadMessage: String? = null,   // 批次完成消息
    // 单张预览下载状态
    val isPreviewDownloading: Boolean = false,
    val previewDownloadProgress: Int = 0,       // 0-100
    val previewDownloadMessage: String? = null   // 预览下载结果消息
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
        _uiState.value = _uiState.value.copy(
            selectedPhoto = null,
            batchDownloadMessage = null,
            previewDownloadMessage = null
        )
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
            selectedIds = emptySet(),
            batchDownloadMessage = null
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

    fun clearBatchDownloadMessage() {
        _uiState.value = _uiState.value.copy(batchDownloadMessage = null)
    }

    fun clearPreviewDownloadMessage() {
        _uiState.value = _uiState.value.copy(previewDownloadMessage = null)
    }

    /** 预览弹窗中下载单张照片 */
    fun downloadPreviewPhoto(photo: GalleryPhoto, context: Context) {
        if (_uiState.value.isPreviewDownloading) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isPreviewDownloading = true,
                previewDownloadProgress = 0,
                previewDownloadMessage = null
            )

            val result = downloadSinglePhoto(photo, context)

            if (result != null) {
                _uiState.value = _uiState.value.copy(
                    isPreviewDownloading = false,
                    previewDownloadProgress = 100,
                    previewDownloadMessage = "已保存到本地相册"
                )
                updateSelectedPhotoLocal(result.toString())
            } else {
                _uiState.value = _uiState.value.copy(
                    isPreviewDownloading = false,
                    previewDownloadMessage = "下载失败"
                )
            }
        }
    }

    /** 批量下载选中的照片到本地相册 */
    fun downloadSelected(context: Context) {
        if (_uiState.value.isBatchDownloading) return

        val state = _uiState.value
        if (state.selectedIds.isEmpty()) return

        val photos = state.groups.flatMap { it.photos }.filter { it.id in state.selectedIds }
        if (photos.isEmpty()) return

        viewModelScope.launch {
            _uiState.value = state.copy(
                isBatchDownloading = true,
                batchDownloadCurrent = 0,
                batchDownloadTotal = photos.size,
                batchDownloadProgress = 0,
                batchDownloadMessage = null
            )

            var successCount = 0
            var failCount = 0

            for ((index, photo) in photos.withIndex()) {
                _uiState.value = _uiState.value.copy(
                    batchDownloadCurrent = index + 1,
                    batchDownloadProgress = 0
                )

                val result = downloadSinglePhoto(photo, context)
                if (result != null) {
                    successCount++
                } else {
                    failCount++
                }
            }

            val msg = when {
                failCount == 0 -> "已保存 $successCount 张到本地相册"
                successCount == 0 -> "下载失败"
                else -> "已保存 $successCount 张，$failCount 张失败"
            }

            _uiState.value = _uiState.value.copy(
                isBatchDownloading = false,
                batchDownloadProgress = 100,
                batchDownloadMessage = msg
            )
        }
    }

    /**
     * 下载单张照片，返回保存后的 Uri（失败返回 null）
     * 在 withContext(Dispatchers.IO) 中调用
     */
    private suspend fun downloadSinglePhoto(photo: GalleryPhoto, context: Context): android.net.Uri? {
        return withContext(Dispatchers.IO) {
            try {
                val photoUrl = "${baseUrl}api/gallery/photo/${photo.path}?token=$authToken"
                val request = Request.Builder().url(photoUrl).build()

                ApiClient.getOkHttpClient().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null

                    val body = response.body ?: return@withContext null
                    val totalBytes = body.contentLength()
                    val contentType = body.contentType()?.toString() ?: "image/*"

                    val mimeType = when {
                        photo.type == "video" -> "video/*"
                        photo.name.endsWith(".mp4") -> "video/mp4"
                        photo.name.endsWith(".mov") -> "video/quicktime"
                        photo.name.endsWith(".png") -> "image/png"
                        photo.name.endsWith(".gif") -> "image/gif"
                        contentType.contains("video") -> "video/*"
                        else -> "image/*"
                    }

                    val isVideo = mimeType.startsWith("video")
                    val contentUri = if (isVideo) {
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    } else {
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    }

                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, photo.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) "Movies/LANSync" else "Pictures/LANSync")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                    }

                    val resolver = context.contentResolver
                    val uri = resolver.insert(contentUri, contentValues) ?: return@withContext null

                    resolver.openOutputStream(uri)?.use { outputStream ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L
                        body.byteStream().use { inputStream ->
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                outputStream.write(buffer, 0, bytesRead)
                                totalRead += bytesRead
                                if (totalBytes > 0) {
                                    val progress = ((totalRead * 100) / totalBytes).toInt().coerceIn(0, 100)
                                    _uiState.value = _uiState.value.copy(batchDownloadProgress = progress)
                                }
                            }
                        }
                    }

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        contentValues.clear()
                        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, contentValues, null, null)
                    }

                    syncedFileDao.insert(
                        com.lansync.app.data.local.SyncedFileEntity(
                            fileName = photo.name,
                            fileSize = photo.size,
                            serverPath = photo.path,
                            hash = photo.id,
                            filePath = uri.toString(),
                            timestamp = System.currentTimeMillis()
                        )
                    )

                    uri
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun updateSelectedPhotoLocal(uri: String) {
        val selected = _uiState.value.selectedPhoto ?: return
        _uiState.value = _uiState.value.copy(
            selectedPhoto = selected.copy(hasLocal = true, localUri = uri)
        )
    }
}
