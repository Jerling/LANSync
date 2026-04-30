package com.lansync.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 已同步文件记录
 * 重装 APP 后可从 Server 重新同步哈希来重建本地缓存
 */
@Entity(tableName = "synced_files",
    indices = [Index("fileName", "fileSize")]  // 加速按 name+size 查询本地文件
)
data class SyncedFileEntity(
    @PrimaryKey
    val filePath: String,
    val fileName: String,
    val fileSize: Long,
    val timestamp: Long,
    val syncedAt: Long = System.currentTimeMillis(),
    val serverPath: String? = null,  // 可空，防止老版本数据库 null 值反序列化崩溃
    val hash: String = ""  // SHA256，缓存本地计算结果，加速下次同步
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

    @Query("SELECT * FROM synced_files WHERE hash = :hash LIMIT 1")
    suspend fun findByHash(hash: String): SyncedFileEntity?

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
@Database(entities = [SyncedFileEntity::class], version = 3, exportSchema = false)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun syncedFileDao(): SyncedFileDao
}
