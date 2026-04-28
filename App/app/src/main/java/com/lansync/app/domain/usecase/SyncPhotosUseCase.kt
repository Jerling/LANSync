package com.lansync.app.domain.usecase

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.lansync.app.data.repository.SyncRepository
import com.lansync.app.domain.model.FileCheckItem
import com.lansync.app.domain.model.FileNameSizeItem
import com.lansync.app.domain.model.PhotoFile
import com.lansync.app.domain.model.SyncState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.security.MessageDigest
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
     * 过滤未同步的照片（两阶段：快速 name+size 过滤 + 懒计算 SHA256 确认）
     *
     * 阶段1：用 name+size 快速问 Server，返回在 Server 上存在的文件
     * 阶段2：对 name+size 查不到的（可能新文件或 Server 缺失），计算 SHA256 确认
     *
     * 重装 APP 后，本地 Room DB 被清空，但通过 name+size 和 SHA256 双重校验，
     * 实现真正意义上的内容去重，不依赖本地记录。
     */
    suspend fun filterUnsyncedPhotos(photos: List<PhotoFile>): List<PhotoFile> {
        if (photos.isEmpty()) return emptyList()

        // ========== 阶段1: name+size 快速过滤（一次网络往返，不计算哈希）==========
        android.util.Log.d("SyncPhotos", "Stage 1: checking ${photos.size} files by name+size...")
        val nameSizeItems = photos.mapNotNull { photo ->
            if (photo.name.isNullOrBlank()) null
            else FileNameSizeItem(photo.name, photo.size)
        }

        val nameSizeResult = repository.checkFilesByNamesOnServer(nameSizeItems)

        val nameSizeExists = nameSizeResult.getOrNull() ?: emptyMap()
        val definitelySynced = mutableSetOf<String>()  // name_size keys that exist on Server
        val needHashCheck = mutableListOf<PhotoFile>()  // name+size not found, need SHA256

        photos.forEach { photo ->
            val key = "${photo.name}_${photo.size}"
            if (nameSizeExists[key] == true) {
                definitelySynced.add(key)
            } else {
                needHashCheck.add(photo)
            }
        }

        android.util.Log.d("SyncPhotos", "Stage 1 result: ${definitelySynced.size} confirmed synced, ${needHashCheck.size} need hash check")

        // 如果全部已在 Server，直接返回空
        if (needHashCheck.isEmpty()) {
            return emptyList()
        }

        // ========== 阶段2: 对可能需要上传的文件计算 SHA256 并确认 ==========
        android.util.Log.d("SyncPhotos", "Stage 2: computing SHA256 for ${needHashCheck.size} files...")
        val photosWithHash = mutableListOf<Pair<PhotoFile, String>>()

        for (photo in needHashCheck) {
            if (photo.name.isNullOrBlank()) continue
            val hash = computeSha256(photo.contentUri)
            if (hash.isNotEmpty()) {
                photosWithHash.add(photo to hash)
            }
        }

        if (photosWithHash.isEmpty()) {
            return emptyList()
        }

        // 用 SHA256 精确确认（可能 name+size 相同但内容不同，或 Server 漏存的）
        val checkItems = photosWithHash.map { (photo, hash) -> FileCheckItem(photo.name, photo.size, hash) }
        val hashResult = repository.checkFilesOnServer(checkItems)
        val hashExists = hashResult.getOrNull() ?: emptyMap()

        return photosWithHash.filter { (photo, hash) ->
            val result = hashExists[hash]
            when {
                result == null -> {
                    // hash 查询没返回（网络问题），降级：传 name+size 都不存在才上传
                    android.util.Log.w("SyncPhotos", "Hash check returned null for ${photo.name}, falling back to name+size")
                    val key = "${photo.name}_${photo.size}"
                    definitelySynced.add(key)
                    false
                }
                result.exists -> {
                    // Server 有，跳过
                    false
                }
                else -> {
                    // Server 没有，需要上传
                    true
                }
            }
        }.map { (photo, hash) -> photo.copy(hash = hash) }
    }

    /**
     * 流式计算文件的 SHA256（分块读取，恒定 ~64KB 内存）
     */
    private fun computeSha256(uri: android.net.Uri): String {
        return try {
            val digest = MessageDigest.getInstance("SHA256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(65536)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt()) }
        } catch (e: Exception) {
            android.util.Log.e("SyncPhotos", "computeSha256 failed for $uri", e)
            ""
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
            emit(SyncState.AllSynced)
            return@flow
        }

        emit(SyncState.Uploading)
        var successCount = 0
        var failCount = 0
        val failedFileNames = mutableListOf<String>()

        unsyncedPhotos.forEachIndexed { index, photo ->
            emit(SyncState.Progress(index + 1, total))

            val result = repository.uploadPhotoResumable(
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
