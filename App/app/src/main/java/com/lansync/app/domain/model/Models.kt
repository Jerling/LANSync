package com.lansync.app.domain.model

import com.google.gson.annotations.SerializedName

/**
 * 登录请求
 */
data class LoginRequest(
    val username: String,
    val password: String
)

/**
 * 登录响应
 */
data class LoginResponse(
    val token: String,
    val username: String,
    val message: String
)

/**
 * 用户信息
 */
data class UserInfo(
    val username: String,
    @SerializedName("server_time")
    val serverTime: String
)

/**
 * 同步状态
 */
data class SyncStatus(
    val status: String,
    val username: String,
    val storage: StorageStats,
    @SerializedName("server_time")
    val serverTime: String
)

/**
 * 存储统计
 */
data class StorageStats(
    @SerializedName("total_files")
    val totalFiles: Int,
    @SerializedName("image_count")
    val imageCount: Int,
    @SerializedName("video_count")
    val videoCount: Int,
    @SerializedName("total_size_bytes")
    val totalSizeBytes: Long,
    @SerializedName("total_size_mb")
    val totalSizeMb: Double,
    @SerializedName("base_dir")
    val baseDir: String
)

/**
 * 文件信息
 */
data class FileInfo(
    val name: String,
    val size: Long,
    val timestamp: Long?
)

/**
 * 已存在文件列表响应
 */
data class ExistingFilesResponse(
    val success: Boolean,
    val count: Int,
    val files: Map<String, FileMetadata>
)

/**
 * 文件元数据
 */
data class FileMetadata(
    val size: Long,
    val mtime: Double
)

/**
 * 上传响应
 */
data class UploadResponse(
    val success: Boolean,
    val message: String,
    val data: UploadData?
)

/**
 * 上传数据
 */
data class UploadData(
    @SerializedName("original_name")
    val originalName: String,
    @SerializedName("saved_path")
    val savedPath: String,
    val size: Long,
    val type: String  // "image" or "video"
)

/**
 * 健康检查响应
 */
data class HealthResponse(
    val status: String,
    @SerializedName("server_time")
    val serverTime: String
)

/**
 * 错误响应
 */
data class ErrorResponse(
    val error: String
)

// ==================== 分片续传相关 ====================

/**
 * 分片上传初始化请求
 */
data class ResumeInitRequest(
    @SerializedName("file_id") val fileId: String,
    @SerializedName("total_size") val totalSize: Long,
    @SerializedName("original_name") val originalName: String,
    val timestamp: Long? = null
)

/**
 * 分片上传初始化响应
 */
data class ResumeInitResponse(
    val success: Boolean,
    @SerializedName("uploaded_size") val uploadedSize: Long,
    @SerializedName("total_size") val totalSize: Long
)

/**
 * 分片上传响应
 */
data class ResumeChunkResponse(
    val success: Boolean,
    @SerializedName("uploaded_size") val uploadedSize: Long,
    @SerializedName("total_size") val totalSize: Long
)

/**
 * 分片上传完成请求
 */
data class ResumeCompleteRequest(
    @SerializedName("file_id") val fileId: String,
    val timestamp: Long? = null
)

/**
 * 分片上传完成响应
 */
data class ResumeCompleteResponse(
    val success: Boolean,
    val data: UploadData?
)

/**
 * 分片上传状态响应
 */
data class ResumeStatusResponse(
    val success: Boolean,
    val data: ResumeStatusData?
)

/**
 * 分片上传状态数据
 */
data class ResumeStatusData(
    val exists: Boolean,
    @SerializedName("uploaded_size") val uploadedSize: Long,
    @SerializedName("total_size") val totalSize: Long,
    @SerializedName("original_name") val originalName: String?
)

// ==================== 云相册相关 ====================

/**
 * 云相册照片条目
 */
data class GalleryPhoto(
    val id: String,
    val name: String,
    val path: String,
    val size: Long,
    val type: String  // "image" or "video"
)

/**
 * 按日期分组的云相册数据
 */
data class GalleryGroup(
    val date: String,
    val photos: List<GalleryPhoto>
)

/**
 * 云相册列表响应
 */
data class GalleryListResponse(
    val success: Boolean,
    val groups: List<GalleryGroup>,
    @SerializedName("total_count") val totalCount: Int
)

/**
 * 照片文件
 */
data class PhotoFile(
    val contentUri: android.net.Uri,
    val path: String,
    val name: String,
    val size: Long,
    val timestamp: Long,
    val isVideo: Boolean,
    val isUploaded: Boolean = false
)

/**
 * 同步状态
 */
sealed class SyncState {
    object Idle : SyncState()
    object LoggingIn : SyncState()
    object Scanning : SyncState()
    object Uploading : SyncState()
    data class Progress(val current: Int, val total: Int) : SyncState()
    object Completed : SyncState()
    data class Error(val message: String, val failedFileNames: List<String> = emptyList()) : SyncState()
}
