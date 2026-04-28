package com.lansync.app.data.api

import com.lansync.app.domain.model.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface LANSyncApi {

    /**
     * 用户登录
     */
    @POST("api/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    /**
     * 获取用户信息
     */
    @GET("api/user/info")
    suspend fun getUserInfo(): Response<UserInfo>

    /**
     * 获取同步状态
     */
    @GET("api/sync/status")
    suspend fun getSyncStatus(): Response<SyncStatus>

    /**
     * 获取已存在的文件列表（用于增量同步）
     */
    @GET("api/sync/existing")
    suspend fun getExistingFiles(): Response<ExistingFilesResponse>

    /**
     * 批量检查文件是否已存在（基于内容哈希）
     */
    @POST("api/sync/check")
    suspend fun checkFiles(@Body request: CheckFilesRequest): Response<CheckFilesResponse>

    /**
     * 上传单张照片/视频
     */
    @Multipart
    @POST("api/upload/photo")
    suspend fun uploadPhoto(
        @Part file: MultipartBody.Part,
        @Part("timestamp") timestamp: RequestBody?,
        @Part("device_id") deviceId: RequestBody
    ): Response<UploadResponse>

    /**
     * 健康检查
     */
    @GET("api/health")
    suspend fun healthCheck(): Response<HealthResponse>

    /**
     * 初始化分片上传
     */
    @POST("api/upload/resume/init")
    suspend fun resumeInit(@Body request: ResumeInitRequest): Response<ResumeInitResponse>

    /**
     * 上传分片
     */
    @Multipart
    @POST("api/upload/resume/chunk")
    suspend fun resumeChunk(
        @Part("file_id") fileId: RequestBody,
        @Part chunk: MultipartBody.Part
    ): Response<ResumeChunkResponse>

    /**
     * 完成分片上传
     */
    @POST("api/upload/resume/complete")
    suspend fun resumeComplete(@Body request: ResumeCompleteRequest): Response<ResumeCompleteResponse>

    /**
     * 查询分片上传状态
     */
    @GET("api/upload/resume/status")
    suspend fun resumeStatus(@Query("file_id") fileId: String): Response<ResumeStatusResponse>

    /**
     * 获取云相册照片列表
     */
    @GET("api/gallery/list")
    suspend fun getGalleryList(): Response<GalleryListResponse>

    /**
     * 批量删除云相册照片
     */
    @POST("api/gallery/delete")
    suspend fun deletePhotos(@Body request: DeletePhotosRequest): Response<DeletePhotosResponse>

    /**
     * 重命名云相册照片
     */
    @POST("api/gallery/rename")
    suspend fun renamePhoto(@Body request: RenamePhotoRequest): Response<RenamePhotoResponse>
}
