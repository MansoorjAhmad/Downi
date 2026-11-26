package com.omnidownloader.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

enum class JobStatus {
    QUEUED,
    RUNNING,
    MERGING,
    SHIELDING,
    SAVING,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED

    val isActive: Boolean
        get() = !isTerminal
}

@Entity(tableName = "download_jobs")
data class DownloadJobEntity(
    @PrimaryKey val id: String,
    val url: String,
    val title: String = "",
    val thumbnailUrl: String = "",
    val platform: String = "other",
    val formatId: String = "best",
    val subtitles: Boolean = false,
    val subLang: String = "en",
    val shieldTier: String = "standard",
    val makeUnique: Boolean = true,
    val isDrop: Boolean = false,
    val status: JobStatus = JobStatus.QUEUED,
    val progress: Int = 0,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBps: Double = 0.0,
    val etaSec: Long = 0L,
    val statusText: String = "Queued",
    val destinationLabel: String = "",
    val mediaUri: String = "",
    val mimeType: String = "video/mp4",
    val fileBytes: Long = 0L,
    val shieldMode: String = "",
    val shieldReason: String = "",
    val shieldInputSha: String = "",
    val shieldOutputSha: String = "",
    val errorCause: String = "",
    val errorRaw: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface DownloadJobDao {
    @Query("SELECT * FROM download_jobs ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadJobEntity>>

    @Query("SELECT * FROM download_jobs WHERE status IN ('QUEUED', 'RUNNING', 'MERGING', 'SHIELDING', 'SAVING') ORDER BY createdAt ASC")
    fun observeActive(): Flow<List<DownloadJobEntity>>

    @Query("SELECT * FROM download_jobs WHERE status = 'COMPLETED' ORDER BY updatedAt DESC")
    fun observeCompleted(): Flow<List<DownloadJobEntity>>

    @Query("SELECT * FROM download_jobs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DownloadJobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: DownloadJobEntity)

    @Query("DELETE FROM download_jobs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM download_jobs WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED')")
    suspend fun clearTerminal()

    @Query("SELECT * FROM download_jobs WHERE status = 'QUEUED' ORDER BY createdAt ASC")
    suspend fun getQueuedJobs(): List<DownloadJobEntity>

    @Query(
        "UPDATE download_jobs SET status = 'FAILED', errorCause = 'interrupted', " +
            "errorRaw = 'Transfer interrupted before completion.', statusText = 'Interrupted', " +
            "updatedAt = :now WHERE status IN ('RUNNING', 'MERGING', 'SHIELDING', 'SAVING')"
    )
    suspend fun markInterruptedOnBoot(now: Long = System.currentTimeMillis())

    @Query(
        "UPDATE download_jobs SET status = 'CANCELLED', statusText = 'Cancelled', " +
            "updatedAt = :now WHERE status IN ('QUEUED', 'RUNNING', 'MERGING', 'SHIELDING', 'SAVING')"
    )
    suspend fun markAllCancelled(now: Long = System.currentTimeMillis())
}

@Database(entities = [DownloadJobEntity::class], version = 2, exportSchema = false)
abstract class DowniDatabase : RoomDatabase() {
    abstract fun downloadJobDao(): DownloadJobDao

    companion object {
        @Volatile
        private var instance: DowniDatabase? = null

        fun get(context: Context): DowniDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DowniDatabase::class.java,
                    "downi.db"
                ).fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
