package com.lansync.app.domain.usecase

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.lansync.app.data.repository.SyncRepository
import com.lansync.app.domain.model.PhotoFile
import com.lansync.app.domain.model.SyncState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import javax.inject.Inject

class SyncPhotosUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SyncRepository
) {
    /**
     * 扫描本地照片
     */
    suspend fun scanLocalPhotos(): List<PhotoFile> {
        val photos = mutableListOf<PhotoFile>()

        val imageProjection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATA
        )

        val videoProjection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DATA
        )

        // 扫描图片
        queryMediaStore(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            imageProjection,
            photos,
            isVideo = false
        )

        // 扫描视频
        queryMediaStore(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            videoProjection,
            photos,
            isVideo = true
        )

        android.util.Log.d("SyncPhotos", "scanLocalPhotos: found ${photos.size} photos/videos")
        return photos
    }

    private fun queryMediaStore(
        uri: android.net.Uri,
        projection: Array<String>,
        photos: MutableList<PhotoFile>,
        isVideo: Boolean
    ) {
        val contentResolver: ContentResolver = context.contentResolver

        val selection = "${MediaStore.MediaColumns.SIZE} > 0"
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        contentResolver.query(
            uri,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(projection[0])
            val nameColumn = cursor.getColumnIndexOrThrow(projection[1])
            val sizeColumn = cursor.getColumnIndexOrThrow(projection[2])
            val dateColumn = cursor.getColumnIndexOrThrow(projection[3])
            val dateModifiedColumn = cursor.getColumnIndexOrThrow(projection[4])
            val dataColumn = cursor.getColumnIndexOrThrow(projection[5])

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val size = cursor.getLong(sizeColumn)
                val dateTaken = cursor.getLong(dateColumn)
                val dateModified = cursor.getLong(dateModifiedColumn)
                val path = cursor.getString(dataColumn)

                // 跳过无效文件（路径为空）
                if (path.isNullOrEmpty()) continue
                // 跳过 name 为空的记录（极少数情况下 DISPLAY_NAME 可能为 null）
                if (name.isNullOrEmpty()) continue

                // 优先用 DATE_TAKEN，为0则用 DATE_MODIFIED
                val effectiveTimestamp = if (dateTaken > 0) dateTaken else dateModified

                // 构建内容URI用于访问文件
                val contentUri = ContentUris.withAppendedId(uri, id)

                photos.add(
                    PhotoFile(
                        contentUri = contentUri,
                        path = path,
                        name = name,
                        size = size,
                        timestamp = effectiveTimestamp,
                        isVideo = isVideo
                    )
                )
            }
        }
    }

    /**
     * 过滤未同步的照片（基于文件名和大小判断，同时检查服务器是否真的存在）
     */
    suspend fun filterUnsyncedPhotos(photos: List<PhotoFile>): List<PhotoFile> {
        // 先获取服务器上已存在的文件列表
        val serverFilesResult = repository.getExistingFiles()
        if (serverFilesResult.isFailure) {
            android.util.Log.e("SyncPhotos", "getExistingFiles failed: ${serverFilesResult.exceptionOrNull()?.message}")
            // 网络失败时，走本地记录判断，视为未同步需要上传
            return photos.filter { !repository.isFileSyncedByNameAndSize(it.name, it.size) }
        }
        val serverFiles = serverFilesResult.getOrNull() ?: emptyMap()

        return photos.filter { photo ->
            // 跳过 name 为空的无效记录
            if (photo.name.isNullOrBlank()) {
                android.util.Log.w("SyncPhotos", "Skipping photo with null/empty name: path=${photo.path}")
                return@filter false
            }

            val localSynced = repository.isFileSyncedByNameAndSize(photo.name, photo.size)
            if (localSynced) {
                // 本地记录已同步，但服务器文件可能已被删除
                // 检查服务器是否真的有这个文件
                val serverHasFile = serverFiles.containsKey(photo.name)
                if (!serverHasFile) {
                    android.util.Log.d("SyncPhotos", "File ${photo.name} marked synced but missing on server, will re-upload")
                }
                // 如果服务器没有，则需要重新上传
                !serverHasFile
            } else {
                true // 本地没记录，需要上传
            }
        }
    }

    /**
     * 上传照片流程
     */
    fun uploadPhotos(photos: List<PhotoFile>, deviceId: String): Flow<SyncState> = flow {
        emit(SyncState.Scanning)

        val unsyncedPhotos = filterUnsyncedPhotos(photos)
        val total = unsyncedPhotos.size

        if (total == 0) {
            emit(SyncState.Completed)
            return@flow
        }

        emit(SyncState.Uploading)
        var successCount = 0
        var failCount = 0
        val failedFileNames = mutableListOf<String>()

        unsyncedPhotos.forEachIndexed { index, photo ->
            emit(SyncState.Progress(index + 1, total))

            val result = repository.uploadPhotoFromUri(
                contentUri = photo.contentUri,
                fileName = photo.name,
                size = photo.size,
                timestamp = photo.timestamp,
                deviceId = deviceId
            )

            if (result.isSuccess) {
                successCount++
            } else {
                android.util.Log.e("SyncPhotos", "upload failed: ${result.exceptionOrNull()?.message}")
                failCount++
                failedFileNames.add(photo.name)
            }
            // 每张照片处理完后主动回收内存，避免多张照片叠加导致 OOM
            System.gc()
        }

        if (failCount > 0) {
            emit(SyncState.Error("上传完成: $successCount 成功, $failCount 失败", failedFileNames))
        } else {
            emit(SyncState.Completed)
        }
    }
}
