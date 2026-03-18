package com.minou.pedometer

import java.time.LocalDate
import java.time.ZoneId

data class TodayMetrics(
    val dayEpoch: Long = currentDayEpoch(),
    val steps: Int = 0,
    val totalDistanceMeters: Double = 0.0,
    val totalCaloriesKcal: Double = 0.0,
    val movingDurationMs: Long = 0L,
    val briskDistanceMeters: Double = 0.0,
    val briskDurationMs: Long = 0L,
    val runningDistanceMeters: Double = 0.0,
    val runningDurationMs: Long = 0L,
    val lastUpdatedEpochMs: Long = 0L
) {
    val averageSpeedMps: Double
        get() = if (movingDurationMs > 0L) {
            totalDistanceMeters / (movingDurationMs / 1_000.0)
        } else {
            0.0
        }

    fun hasActivity(): Boolean {
        return steps > 0 ||
            totalDistanceMeters > 0.0 ||
            totalCaloriesKcal > 0.0 ||
            movingDurationMs > 0L ||
            briskDurationMs > 0L ||
            runningDurationMs > 0L
    }
}

enum class MovementZone {
    WALKING,
    BRISK,
    RUNNING,
}

enum class UserSex {
    MALE,
    FEMALE,
    OTHER,
}

enum class WeatherCondition {
    SUNNY,
    CLOUDY,
    RAINY,
    SNOWY,
}

enum class AppetiteLevel {
    LIGHT,
    NORMAL,
    HUNGRY,
}

enum class MoodPreference {
    ANY,
    RICH,
    REFRESHING,
    WARMING,
}

data class WeatherContext(
    val condition: WeatherCondition = WeatherCondition.SUNNY,
    val temperatureC: Int = 20,
) {
    val normalizedTemperatureC: Int
        get() = temperatureC.coerceIn(-20, 45)
}

data class UserProfile(
    val heightCm: Int = 170,
    val sex: UserSex = UserSex.OTHER,
    val strideScale: Double = 1.0,
    val weightKg: Double? = null,
) {
    val normalizedHeightCm: Int
        get() = heightCm.coerceIn(120, 220)

    val normalizedStrideScale: Double
        get() = strideScale.coerceIn(0.7, 1.3)

    val normalizedWeightKg: Double?
        get() = weightKg?.coerceIn(30.0, 200.0)
}

data class RecommendationPreferences(
    val excludedToppings: Set<String> = emptySet(),
    val appetiteLevel: AppetiteLevel = AppetiteLevel.NORMAL,
    val moodPreference: MoodPreference = MoodPreference.ANY,
    val shopHoursNote: String = MakimuraShop.DEFAULT_HOURS_NOTE,
    val crowdNote: String = MakimuraShop.DEFAULT_CROWD_NOTE,
) {
    val normalizedShopHoursNote: String
        get() = shopHoursNote.trim().ifBlank { MakimuraShop.DEFAULT_HOURS_NOTE }.take(200)

    val normalizedCrowdNote: String
        get() = crowdNote.trim().ifBlank { MakimuraShop.DEFAULT_CROWD_NOTE }.take(200)
}

data class RecommendationHistoryEntry(
    val createdAtEpochMs: Long,
    val dayEpoch: Long,
    val tier: RecommendationTier,
    val steps: Int,
    val weatherCondition: WeatherCondition,
    val temperatureC: Int,
    val itemNames: List<String>,
    val totalYen: Int,
    val reason: String,
    val signature: String,
)

data class DailyHistory(
    val dayEpoch: Long,
    val steps: Int,
    val totalDistanceMeters: Double,
    val movingDurationMs: Long,
    val briskDistanceMeters: Double,
    val briskDurationMs: Long,
    val runningDistanceMeters: Double,
    val runningDurationMs: Long,
    val totalCaloriesKcal: Double = 0.0,
)

data class TrackingUiState(
    val metrics: TodayMetrics = TodayMetrics(),
    val history: List<DailyHistory> = emptyList(),
    val recommendationHistory: List<RecommendationHistoryEntry> = emptyList(),
    val userProfile: UserProfile = UserProfile(),
    val recommendationPreferences: RecommendationPreferences = RecommendationPreferences(),
    val weatherContext: WeatherContext = WeatherContext(),
    val weatherCity: String = MakimuraShop.ADDRESS_LABEL,
    val weatherUpdatedAtEpochMs: Long = 0L,
    val isTracking: Boolean = false,
    val sensorSupported: Boolean = true,
    val persistenceEnabled: Boolean = false,
)

internal fun currentDayEpoch(): Long = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
