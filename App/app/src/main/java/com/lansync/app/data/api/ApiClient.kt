package com.lansync.app.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val DEFAULT_TIMEOUT = 300L

    // TODO: 后续从设置界面配置服务器地址
    private const val DEFAULT_BASE_URL = "http://192.168.0.108:8765/"

    private var baseUrl: String = DEFAULT_BASE_URL
    private var authToken: String? = null

    private var okHttpClient: OkHttpClient = buildOkHttpClient()
    private var retrofit: Retrofit = createRetrofit()

    private fun buildOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.NONE // BODY 会将上传内容全部读入内存打印，日志无用且极易 OOM
        }

        val authInterceptor = okhttp3.Interceptor { chain ->
            val request = if (authToken != null) {
                chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $authToken")
                    .build()
            } else {
                chain.request()
            }
            chain.proceed(request)
        }

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .addInterceptor(authInterceptor)
            .connectTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
            .build()
    }

    private fun createRetrofit(): Retrofit {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    fun setBaseUrl(url: String) {
        baseUrl = if (url.endsWith("/")) url else "$url/"
        retrofit = createRetrofit()
    }

    fun setAuthToken(token: String?) {
        authToken = token
        okHttpClient = buildOkHttpClient()
        retrofit = createRetrofit()
    }

    fun getBaseUrl(): String = baseUrl

    fun getApi(): LANSyncApi = retrofit.create(LANSyncApi::class.java)

    fun getOkHttpClient(): OkHttpClient = okHttpClient
}
