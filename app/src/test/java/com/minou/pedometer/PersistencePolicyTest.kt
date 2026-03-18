package com.minou.pedometer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PersistencePolicyTest {
    @Test
    fun trackingUiState_defaults_to_non_persistent() {
        assertFalse(TrackingUiState().persistenceEnabled)
    }

    @Test
    fun upsertRecentHistory_replaces_same_day_and_limits_count() {
        val history = listOf(
            DailyHistory(dayEpoch = 3, steps = 30, totalDistanceMeters = 3.0, movingDurationMs = 30, briskDistanceMeters = 0.0, briskDurationMs = 0, runningDistanceMeters = 0.0, runningDurationMs = 0, totalCaloriesKcal = 3.0),
            DailyHistory(dayEpoch = 2, steps = 20, totalDistanceMeters = 2.0, movingDurationMs = 20, briskDistanceMeters = 0.0, briskDurationMs = 0, runningDistanceMeters = 0.0, runningDurationMs = 0, totalCaloriesKcal = 2.0),
            DailyHistory(dayEpoch = 1, steps = 10, totalDistanceMeters = 1.0, movingDurationMs = 10, briskDistanceMeters = 0.0, briskDurationMs = 0, runningDistanceMeters = 0.0, runningDurationMs = 0, totalCaloriesKcal = 1.0),
        )

        val updated = upsertRecentHistory(
            history = history,
            day = DailyHistory(dayEpoch = 2, steps = 99, totalDistanceMeters = 9.9, movingDurationMs = 99, briskDistanceMeters = 0.0, briskDurationMs = 0, runningDistanceMeters = 0.0, runningDurationMs = 0, totalCaloriesKcal = 9.9),
            limit = 2,
        )

        assertEquals(listOf(3L, 2L), updated.map { it.dayEpoch })
        assertEquals(99, updated.last().steps)
    }
}
