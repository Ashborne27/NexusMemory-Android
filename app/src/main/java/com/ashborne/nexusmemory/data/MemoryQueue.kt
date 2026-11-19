package com.ashborne.nexusmemory.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "pending_memories")
data class PendingMemory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val encryptedPayload: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "synced_memories")
data class SyncedMemory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val encryptedPayload: String,
    val syncedAt: Long = System.currentTimeMillis()
)

@Dao
interface MemoryDao {
    @Insert
    suspend fun insert(item: PendingMemory): Long

    @Query("SELECT * FROM pending_memories ORDER BY id ASC")
    suspend fun getAllPending(): List<PendingMemory>

    @Query("SELECT COUNT(*) FROM pending_memories")
    fun observePendingCount(): Flow<Int>

    @Query("DELETE FROM pending_memories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Insert
    suspend fun insertSynced(item: SyncedMemory): Long

    @Query("SELECT * FROM synced_memories ORDER BY id DESC LIMIT 30")
    fun observeRecentSynced(): Flow<List<SyncedMemory>>
}

@Database(
    entities = [PendingMemory::class, SyncedMemory::class],
    version = 2,
    exportSchema = false
)
abstract class NexusDatabase : RoomDatabase() {
    abstract fun dao(): MemoryDao

    companion object {
        @Volatile private var INSTANCE: NexusDatabase? = null
        fun get(context: Context): NexusDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NexusDatabase::class.java,
                    "nexus_mobile_vault.db"
                ).fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
