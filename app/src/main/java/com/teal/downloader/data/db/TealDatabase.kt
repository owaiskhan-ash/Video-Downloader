package com.teal.downloader.data.db

import android.annotation.SuppressLint
import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A finished (or failed) download, kept so the user can find it again. */
@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val thumbnail: String?,
    val filePath: String?,
    val contentUri: String?,
    val ext: String?,
    val sizeBytes: Long?,
    val durationSeconds: Long?,
    val isAudioOnly: Boolean,
    val succeeded: Boolean,
    val error: String?,
    val timestamp: Long,
)

@Entity(tableName = "command_templates")
data class CommandTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): HistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HistoryEntity)

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface CommandTemplateDao {
    @Query("SELECT * FROM command_templates ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<CommandTemplateEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CommandTemplateEntity): Long

    @Update
    suspend fun update(entity: CommandTemplateEntity)

    @Delete
    suspend fun delete(entity: CommandTemplateEntity)

    @Query("DELETE FROM command_templates WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Database(
    entities = [HistoryEntity::class, CommandTemplateEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class TealDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun commandTemplateDao(): CommandTemplateDao

    companion object {
        // Built from context.applicationContext below, so this static reference
        // cannot pin an Activity. Lint cannot see that from the field alone.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: TealDatabase? = null

        fun get(context: Context): TealDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TealDatabase::class.java,
                    "teal.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
