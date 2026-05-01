package com.lansync.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lansync_prefs")

@Singleton
class TokenManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val TOKEN_KEY = stringPreferencesKey("token")
        private val USERNAME_KEY = stringPreferencesKey("username")
        private val PASSWORD_KEY = stringPreferencesKey("password")  // 新增：保存密码
        private val SERVER_URL_KEY = stringPreferencesKey("server_url")
        private val TARGET_WIFI_SSID_KEY = stringPreferencesKey("target_wifi_ssid")
    }

    val passwordFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PASSWORD_KEY]
    }

    val tokenFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[TOKEN_KEY]
    }

    val usernameFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[USERNAME_KEY]
    }

    val serverUrlFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[SERVER_URL_KEY]
    }

    val targetWifiSsidFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[TARGET_WIFI_SSID_KEY]
    }

    suspend fun saveToken(token: String) {
        context.dataStore.edit { preferences ->
            preferences[TOKEN_KEY] = token
        }
    }

    suspend fun saveUsername(username: String) {
        context.dataStore.edit { preferences ->
            preferences[USERNAME_KEY] = username
        }
    }

    suspend fun savePassword(password: String) {
        context.dataStore.edit { preferences ->
            preferences[PASSWORD_KEY] = password
        }
    }

    suspend fun saveServerUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[SERVER_URL_KEY] = url
        }
    }

    suspend fun saveTargetWifiSsid(ssid: String) {
        context.dataStore.edit { preferences ->
            preferences[TARGET_WIFI_SSID_KEY] = ssid
        }
    }

    suspend fun getToken(): String? {
        var token: String? = null
        context.dataStore.data.first { preferences ->
            token = preferences[TOKEN_KEY]
            true
        }
        return token
    }

    suspend fun getPassword(): String? {
        var password: String? = null
        context.dataStore.data.first { preferences ->
            password = preferences[PASSWORD_KEY]
            true
        }
        return password
    }

    suspend fun clearAll() {
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
    }
}
