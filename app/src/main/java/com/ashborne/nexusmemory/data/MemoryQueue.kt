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
}

@Database(entities = [PendingMemory::class], version = 1, exportSchema = false)
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
                ).build().also { INSTANCE = it }
            }
    }
}
