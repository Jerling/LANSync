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
}
