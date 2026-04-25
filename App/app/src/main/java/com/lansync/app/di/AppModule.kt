package com.lansync.app.di

import android.content.Context
import androidx.room.Room
import com.lansync.app.data.local.SyncDatabase
import com.lansync.app.data.local.SyncedFileDao
import com.lansync.app.data.local.TokenManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SyncDatabase {
        return Room.databaseBuilder(
            context,
            SyncDatabase::class.java,
            "lansync_database"
        ).build()
    }

    @Provides
    @Singleton
    fun provideSyncedFileDao(database: SyncDatabase): SyncedFileDao {
        return database.syncedFileDao()
    }

    @Provides
    @Singleton
    fun provideTokenManager(@ApplicationContext context: Context): TokenManager {
        return TokenManager(context)
    }
}
