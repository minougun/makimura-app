package com.minou.pedometer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryAnalyticsTest {

    @Test
    fun filterByLastDays_filtersExpectedWindow() {
        val today = 100L
        val history = listOf(
            DailyHistory(dayEpoch = 100L, steps = 10, totalDistanceMeters = 10.0, movingDurationMs = 10L, briskDistanceMeters = 0.0, briskDurationMs = 0L, runningDistanceMeters = 0.0, runningDurationMs = 0L),
            DailyHistory(dayEpoch = 95L, steps = 20, totalDistanceMeters = 20.0, movingDurationMs = 20L, briskDistanceMeters = 0.0, briskDurationMs = 0L, runningDistanceMeters = 0.0, runningDurationMs = 0L),
            DailyHistory(dayEpoch = 90L, steps = 30, totalDistanceMeters = 30.0, movingDurationMs = 30L, briskDistanceMeters = 0.0, briskDurationMs = 0L, runningDistanceMeters = 0.0, runningDurationMs = 0L),
        )

        val filtered = HistoryAnalytics.filterByLastDays(history, days = 7, todayEpoch = today)

        assertEquals(2, filtered.size)
        assertTrue(filtered.any { it.dayEpoch == 100L })
        assertTrue(filtered.any { it.dayEpoch == 95L })
    }

    @Test
    fun filterByLastDays_nullDays_returnsAll() {
        val history = listOf(
            DailyHistory(dayEpoch = 100L, steps = 10, totalDistanceMeters = 10.0, movingDurationMs = 10L, briskDistanceMeters = 0.0, briskDurationMs = 0L, runningDistanceMeters = 0.0, runningDurationMs = 0L),
            DailyHistory(dayEpoch = 95L, steps = 20, totalDistanceMeters = 20.0, movingDurationMs = 20L, briskDistanceMeters = 0.0, briskDurationMs = 0L, runningDistanceMeters = 0.0, runningDurationMs = 0L),
        )

        val filtered = HistoryAnalytics.filterByLastDays(history, days = null, todayEpoch = 100L)

        assertEquals(history.size, filtered.size)
    }

    @Test
    fun weeklySummary_aggregatesOnlyRecent7Days() {
        val today = 100L
        val history = listOf(
            DailyHistory(dayEpoch = 100L, steps = 1000, totalDistanceMeters = 800.0, movingDurationMs = 1_000L, briskDistanceMeters = 100.0, briskDurationMs = 100L, runningDistanceMeters = 30.0, runningDurationMs = 50L),
            DailyHistory(dayEpoch = 96L, steps = 2000, totalDistanceMeters = 1500.0, movingDurationMs = 2_000L, briskDistanceMeters = 200.0, briskDurationMs = 200L, runningDistanceMeters = 60.0, runningDurationMs = 80L, totalCaloriesKcal = 110.0),
            DailyHistory(dayEpoch = 93L, steps = 3000, totalDistanceMeters = 2200.0, movingDurationMs = 3_000L, briskDistanceMeters = 250.0, briskDurationMs = 300L, runningDistanceMeters = 80.0, runningDurationMs = 120L, totalCaloriesKcal = 220.0),
        )

        val summary = HistoryAnalytics.weeklySummary(history, todayEpoch = today)

        assertEquals(3000, summary.steps)
        assertEquals(2300.0, summary.distanceMeters, 0.0001)
        assertEquals(110.0, summary.caloriesKcal, 0.0001)
        assertEquals(3_000L, summary.movingDurationMs)
    }

    @Test
    fun monthlySummary_aggregatesRecent30Days() {
        val today = 100L
        val history = listOf(
            DailyHistory(dayEpoch = 100L, steps = 100, totalDistanceMeters = 100.0, movingDurationMs = 100L, briskDistanceMeters = 0.0, briskDurationMs = 0L, runningDistanceMeters = 0.0, runningDurationMs = 0L, totalCaloriesKcal = 10.0),
            DailyHistory(dayEpoch = 72L, steps = 200, totalDistanceMeters = 200.0, movingDurationMs = 200L, briskDistanceMeters = 0.0, briskDurationMs = 0L, runningDistanceMeters = 0.0, runningDurationMs = 0L, totalCaloriesKcal = 20.0),
            DailyHistory(dayEpoch = 71L, steps = 300, totalDistanceMeters = 300.0, movingDurationMs = 300L, briskDistanceMeters = 0.0, briskDurationMs = 0L, runningDistanceMeters = 0.0, runningDurationMs = 0L, totalCaloriesKcal = 30.0),
        )

        val summary = HistoryAnalytics.monthlySummary(history, todayEpoch = today)

        assertEquals(600, summary.steps)
        assertEquals(600.0, summary.distanceMeters, 0.0001)
        assertEquals(60.0, summary.caloriesKcal, 0.0001)
        assertEquals(600L, summary.movingDurationMs)
    }

    @Test
    fun toCsv_containsHeaderAndRowsInAscendingDate() {
        val history = listOf(
            DailyHistory(dayEpoch = 101L, steps = 2000, totalDistanceMeters = 1500.5, movingDurationMs = 120_000L, briskDistanceMeters = 300.0, briskDurationMs = 30_000L, runningDistanceMeters = 100.0, runningDurationMs = 10_000L, totalCaloriesKcal = 120.25),
            DailyHistory(dayEpoch = 100L, steps = 1000, totalDistanceMeters = 800.0, movingDurationMs = 60_000L, briskDistanceMeters = 100.0, briskDurationMs = 10_000L, runningDistanceMeters = 50.0, runningDurationMs = 5_000L, totalCaloriesKcal = 64.0),
        )

        val csv = HistoryAnalytics.toCsv(history)
        val lines = csv.trim().split("\n")

        assertEquals("date,steps,total_distance_m,total_calories_kcal,moving_duration_s,brisk_distance_m,brisk_duration_s,running_distance_m,running_duration_s", lines.first())
        assertTrue(lines[1].contains(",1000,800.000,64.000,60,100.000,10,50.000,5"))
        assertTrue(lines[2].contains(",2000,1500.500,120.250,120,300.000,30,100.000,10"))
    }
}
