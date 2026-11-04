package com.github.mytv.core.dynamiccolor

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "seed_cache", primaryKeys = ["source_key", "algorithm_version"])
data class SeedCacheEntity(
    @ColumnInfo(name = "source_key") val sourceKey: String,
    @ColumnInfo(name = "algorithm_version") val algorithmVersion: Int,
    @ColumnInfo(name = "result") val result: Int,
)

@Dao
interface SeedCacheDao {
    @Query("SELECT result FROM seed_cache WHERE source_key = :key AND algorithm_version = :version")
    suspend fun get(key: String, version: Int): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: SeedCacheEntity)

    @Query("DELETE FROM seed_cache WHERE algorithm_version != :version")
    suspend fun dropOtherVersions(version: Int)
}

@Database(entities = [SeedCacheEntity::class], version = 1, exportSchema = false)
abstract class SeedDatabase : RoomDatabase() {
    abstract fun seedCacheDao(): SeedCacheDao
}

class RoomSeedCache(context: Context) : SeedCache {
    private val db by lazy {
        Room.databaseBuilder(context.applicationContext, SeedDatabase::class.java, "seed_cache.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    override suspend fun get(key: String, algorithmVersion: Int): Int? =
        db.seedCacheDao().get(key, algorithmVersion)

    override suspend fun put(key: String, algorithmVersion: Int, result: Int) {
        db.seedCacheDao().put(SeedCacheEntity(key, algorithmVersion, result))
    }
}
