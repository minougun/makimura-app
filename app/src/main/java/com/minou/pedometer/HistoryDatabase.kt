package com.minou.pedometer

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "daily_history")
data class DailyHistoryEntity(
    @PrimaryKey val dayEpoch: Long,
    val steps: Int,
    val totalDistanceMeters: Double,
    val totalCaloriesKcal: Double,
    val movingDurationMs: Long,
    val briskDistanceMeters: Double,
    val briskDurationMs: Long,
    val runningDistanceMeters: Double,
    val runningDurationMs: Long,
)

@Dao
interface DailyHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DailyHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<DailyHistoryEntity>)

    @Query("DELETE FROM daily_history")
    suspend fun clearAll()

    @Query("SELECT * FROM daily_history ORDER BY dayEpoch DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DailyHistoryEntity>>
}

@Database(
    entities = [DailyHistoryEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun dailyHistoryDao(): DailyHistoryDao
}

fun TodayMetrics.toHistoryEntity(): DailyHistoryEntity {
    return DailyHistoryEntity(
        dayEpoch = dayEpoch,
        steps = steps,
        totalDistanceMeters = totalDistanceMeters,
        totalCaloriesKcal = totalCaloriesKcal,
        movingDurationMs = movingDurationMs,
        briskDistanceMeters = briskDistanceMeters,
        briskDurationMs = briskDurationMs,
        runningDistanceMeters = runningDistanceMeters,
        runningDurationMs = runningDurationMs,
    )
}

fun DailyHistoryEntity.toModel(): DailyHistory {
    return DailyHistory(
        dayEpoch = dayEpoch,
        steps = steps,
        totalDistanceMeters = totalDistanceMeters,
        totalCaloriesKcal = totalCaloriesKcal,
        movingDurationMs = movingDurationMs,
        briskDistanceMeters = briskDistanceMeters,
        briskDurationMs = briskDurationMs,
        runningDistanceMeters = runningDistanceMeters,
        runningDurationMs = runningDurationMs,
    )
}
