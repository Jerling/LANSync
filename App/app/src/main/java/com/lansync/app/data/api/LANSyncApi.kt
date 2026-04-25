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
}
