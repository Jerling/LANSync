package com.lansync.app

import android.app.Application
import com.lansync.app.data.api.ApiClient
import com.lansync.app.data.local.TokenManager
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltAndroidApp
class LANSyncApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        // 从 DataStore 恢复服务器地址
        applicationScope.launch(Dispatchers.IO) {
            val entryPoint = EntryPointAccessors.fromApplication(
                this@LANSyncApplication,
                LANSyncAppEntryPoint::class.java
            )
            val tokenManager = entryPoint.tokenManager()
            val savedUrl = tokenManager.serverUrlFlow.first()
            if (!savedUrl.isNullOrBlank()) {
                ApiClient.setBaseUrl(savedUrl)
            }
            val savedToken = tokenManager.tokenFlow.first()
            if (!savedToken.isNullOrBlank()) {
                ApiClient.setAuthToken(savedToken)
            }
        }
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface LANSyncAppEntryPoint {
    fun tokenManager(): TokenManager
}
