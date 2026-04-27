package com.lansync.app.data.repository

import android.content.Context
import com.lansync.app.data.api.ApiClient
import com.lansync.app.data.api.LANSyncApi
import com.lansync.app.data.local.SyncedFileDao
import com.lansync.app.data.local.SyncedFileEntity
import com.lansync.app.data.local.TokenManager
import com.lansync.app.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenManager: TokenManager,
    private val syncedFileDao: SyncedFileDao
) {
    private val api: LANSyncApi
        get() = ApiClient.getApi()

    // ==================== 认证相关 ====================

    suspend fun login(username: String, password: String): Result<LoginResponse> {
        return try {
            val response = api.login(LoginRequest(username, password))
            if (response.isSuccessful && response.body() != null) {
                val loginResponse = response.body()!!
                tokenManager.saveToken(loginResponse.token)
                tokenManager.saveUsername(loginResponse.username)
                ApiClient.setAuthToken(loginResponse.token)
                Result.success(loginResponse)
            } else {
                Result.failure(Exception(response.errorBody()?.string() ?: "Login failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun logout() {
        tokenManager.clearAll()
        ApiClient.setAuthToken(null)
    }

    suspend fun isLoggedIn(): Boolean {
        val token = tokenManager.getToken()
        return !token.isNullOrEmpty()
    }

    // ==================== 服务器信息 ====================

    suspend fun getSyncStatus(): Result<SyncStatus> {
        return try {
            val response = api.getSyncStatus()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to get sync status"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getExistingFiles(): Result<Map<String, FileMetadata>> {
        return try {
            val response = api.getExistingFiles()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.files)
            } else {
                Result.failure(Exception("Failed to get existing files"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun healthCheck(): Result<HealthResponse> {
        return try {
            val response = api.healthCheck()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Server not available"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== 文件同步 ====================

    suspend fun isFileSyncedByNameAndSize(name: String, size: Long): Boolean {
        val existing = syncedFileDao.findByNameAndSize(name, size)
        return existing != null
    }

    suspend fun uploadPhotoFromUri(
        contentUri: android.net.Uri,
        fileName: String,
        size: Long,
        timestamp: Long?,
        deviceId: String
    ): Result<UploadResponse> {
        return try {
            val contentResolver = context.contentResolver
            // 流式 UploadStreamingBody 避免 OOM：分块读取不一次性加载整个文件到内存
            val isVideo = fileName.endsWith(".mp4") || fileName.endsWith(".mov") ||
                           fileName.endsWith(".avi") || fileName.endsWith(".mkv")
            val mediaTypeStr = if (isVideo) "video/*" else "image/*"
            val streamingBody = object : RequestBody() {
                override fun contentType() = mediaTypeStr.toMediaTypeOrNull()
                override fun contentLength() = size.coerceAtMost(Long.MAX_VALUE)
                override fun writeTo(sink: BufferedSink) {
                    contentResolver.openInputStream(contentUri)?.use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            sink.write(buffer, 0, bytesRead)
                        }
                    }
                }
            }
            val requestBody = streamingBody
            val multipartBody = MultipartBody.Part.createFormData("file", fileName, requestBody)
            val timestampBody = timestamp?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())
            val deviceIdBody = deviceId.toRequestBody("text/plain".toMediaTypeOrNull())

            val response = api.uploadPhoto(multipartBody, timestampBody, deviceIdBody)
            if (response.isSuccessful && response.body() != null) {
                val uploadResponse = response.body()!!
                // 记录已同步的文件
                syncedFileDao.insert(
                    SyncedFileEntity(
                        filePath = contentUri.toString(),
                        fileName = fileName,
                        fileSize = size,
                        timestamp = timestamp ?: 0,
                        serverPath = uploadResponse.data?.savedPath ?: ""
                    )
                )
                Result.success(uploadResponse)
            } else {
                Result.failure(Exception(response.errorBody()?.string() ?: "Upload failed"))
            }
        } catch (e: Exception) {
            android.util.Log.e("SyncRepository", "uploadPhotoFromUri failed", e)
            Result.failure(e)
        }
    }

    suspend fun uploadPhoto(
        file: File,
        timestamp: Long?,
        deviceId: String,
        onProgress: (Int) -> Unit = {}
    ): Result<UploadResponse> {
        return try {
            val requestBody = file.asRequestBody("image/*".toMediaTypeOrNull())
            val multipartBody = MultipartBody.Part.createFormData("file", file.name, requestBody)
            val timestampBody = timestamp?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())
            val deviceIdBody = deviceId.toRequestBody("text/plain".toMediaTypeOrNull())

            val response = api.uploadPhoto(multipartBody, timestampBody, deviceIdBody)
            if (response.isSuccessful && response.body() != null) {
                val uploadResponse = response.body()!!
                // 记录已同步的文件
                syncedFileDao.insert(
                    SyncedFileEntity(
                        filePath = file.absolutePath,
                        fileName = file.name,
                        fileSize = file.length(),
                        timestamp = timestamp ?: file.lastModified(),
                        serverPath = uploadResponse.data?.savedPath ?: ""
                    )
                )
                Result.success(uploadResponse)
            } else {
                Result.failure(Exception(response.errorBody()?.string() ?: "Upload failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun isFileSynced(file: File): Boolean {
        val existing = syncedFileDao.findByNameAndSize(file.name, file.length())
        return existing != null
    }

    suspend fun getSyncedFilesCount(): Int {
        return syncedFileDao.getCount()
    }

    // ==================== 分片续传 ====================

    companion object {
        /** 每片大小 1MB，与 Server 保持一致 */
        const val CHUNK_SIZE = 1024 * 1024L
    }

    /**
     * 生成稳定的文件ID（用于断点续传）
     */
    fun generateFileId(fileName: String, fileSize: Long): String {
        val input = "$fileName|$fileSize"
        val md = java.security.MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 分片上传（支持断点续传）
     * 流程: init -> query status -> chunk (loop) -> complete
     * OOM 修复: 固定 1MB buffer，每次读取后立即上传，不做中间缓冲
     *           峰值内存 = 1MB chunk + 8KB read buffer，恒定 ~1.01MB
     */
    suspend fun uploadPhotoResumable(
        contentUri: android.net.Uri,
        fileName: String,
        size: Long,
        timestamp: Long?,
        deviceId: String
    ): Result<UploadResponse> {
        val fileId = generateFileId(fileName, size)
        val contentResolver = context.contentResolver

        // Step 1: 初始化，查询断点
        val initResult = api.resumeInit(ResumeInitRequest(fileId, size, fileName, timestamp))
        if (!initResult.isSuccessful || initResult.body() == null) {
            return Result.failure(Exception("Failed to init resumable upload: ${initResult.errorBody()?.string() ?: "unknown"}"))
        }
        val uploadedSize = initResult.body()!!.uploadedSize

        // Step 2: 从断点开始，每次只读 1MB 上传（恒定内存，无 OOM）
        if (uploadedSize < size) {
            val isVideo = fileName.endsWith(".mp4") || fileName.endsWith(".mov") ||
                          fileName.endsWith(".avi") || fileName.endsWith(".mkv")
            val mediaType = if (isVideo) "video/*" else "image/*"

            contentResolver.openInputStream(contentUri)?.use { input ->
                input.skip(uploadedSize)

                // 固定 1MB chunk buffer，不随文件大小增长
                val chunk = ByteArray(CHUNK_SIZE.toInt())
                var offset = 0L
                var bytesRead: Int

                while (input.read(chunk, 0, chunk.size).also { bytesRead = it } != -1) {
                    if (bytesRead == 0) break

                    // 将当前已读数据（bytesRead 字节）上传
                    val result = uploadChunk(chunk, bytesRead, fileId, fileName, mediaType)
                    if (result != null) return result  // 失败则立即返回
                    offset += bytesRead

                    // 如果最后一块不足 CHUNK_SIZE，说明到文件尾了，跳出循环
                    if (bytesRead < chunk.size) break
                }
            } ?: return Result.failure(Exception("Cannot open input stream for $fileName"))
        }

        // Step 3: 完成上传
        val completeResult = api.resumeComplete(ResumeCompleteRequest(fileId, timestamp))
        if (!completeResult.isSuccessful || completeResult.body() == null || completeResult.body()!!.data == null) {
            return Result.failure(Exception("Failed to complete upload: ${completeResult.errorBody()?.string() ?: "unknown"}"))
        }

        val uploadData = completeResult.body()!!.data!!
        syncedFileDao.insert(
            SyncedFileEntity(
                filePath = contentUri.toString(),
                fileName = fileName,
                fileSize = size,
                timestamp = timestamp ?: 0,
                serverPath = uploadData.savedPath
            )
        )
        return Result.success(UploadResponse(true, "Uploaded", uploadData))
    }

    /**
     * 上传一个 chunk，返回失败 Result；返回 null 表示成功
     */
    private suspend fun uploadChunk(
        chunk: ByteArray,
        validLen: Int,
        fileId: String,
        fileName: String,
        mediaType: String
    ): Result<UploadResponse>? {
        val chunkBody = object : okhttp3.RequestBody() {
            override fun contentType() = mediaType.toMediaTypeOrNull()
            override fun contentLength() = validLen.toLong()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                sink.write(chunk, 0, validLen)
            }
        }
        val fileIdBody = fileId.toRequestBody("text/plain".toMediaTypeOrNull())
        val chunkPart = MultipartBody.Part.createFormData("chunk", fileName, chunkBody)

        val chunkResult = api.resumeChunk(fileIdBody, chunkPart)
        if (!chunkResult.isSuccessful || chunkResult.body() == null) {
            return Result.failure(Exception("Chunk upload failed: ${chunkResult.errorBody()?.string() ?: "unknown"}"))
        }
        return null  // 成功
    }
}
