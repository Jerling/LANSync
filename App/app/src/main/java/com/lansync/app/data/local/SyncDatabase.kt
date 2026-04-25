package com.lansync.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 已同步文件记录
 */
@Entity(tableName = "synced_files")
data class SyncedFileEntity(
    @PrimaryKey
    val filePath: String,
    val fileName: String,
    val fileSize: Long,
    val timestamp: Long,
    val syncedAt: Long = System.currentTimeMillis(),
    val serverPath: String
)

/**
 * DAO for synced files
 */
@Dao
interface SyncedFileDao {
    @Query("SELECT * FROM synced_files")
    fun getAllSyncedFiles(): Flow<List<SyncedFileEntity>>

    @Query("SELECT filePath FROM synced_files")
    suspend fun getAllSyncedPaths(): List<String>

    @Query("SELECT * FROM synced_files WHERE fileName = :fileName AND fileSize = :fileSize")
    suspend fun findByNameAndSize(fileName: String, fileSize: Long): SyncedFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(syncedFile: SyncedFileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(syncedFiles: List<SyncedFileEntity>)

    @Delete
    suspend fun delete(syncedFile: SyncedFileEntity)

    @Query("DELETE FROM synced_files")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM synced_files")
    suspend fun getCount(): Int
}

/**
 * Room Database
 */
@Database(entities = [SyncedFileEntity::class], version = 1, exportSchema = false)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun syncedFileDao(): SyncedFileDao
}
