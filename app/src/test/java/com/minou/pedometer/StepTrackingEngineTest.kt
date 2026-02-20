package com.minou.pedometer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StepTrackingEngineTest {

    @Test
    fun zoneFromCadence_classifiesWalkingBriskRunning() {
        assertEquals(MovementZone.WALKING, StepTrackingEngine.zoneFromCadence(90.0))
        assertEquals(MovementZone.BRISK, StepTrackingEngine.zoneFromCadence(125.0))
        assertEquals(MovementZone.RUNNING, StepTrackingEngine.zoneFromCadence(160.0))
    }

    @Test
    fun onStepDetector_withoutStepCounter_incrementsStepsAndDistance() {
        val engine = StepTrackingEngine(
            initialMetrics = TodayMetrics(dayEpoch = 100L),
            initialStepCounterBaseline = null,
            hasStepCounterSensor = false,
        )

        engine.onStepDetector(timestampNs = 0L, nowMs = 1_000L, profile = UserProfile())
        engine.onStepDetector(timestampNs = 500_000_000L, nowMs = 1_500L, profile = UserProfile())
        val update = engine.onStepDetector(timestampNs = 1_000_000_000L, nowMs = 2_000L, profile = UserProfile())

        assertEquals(3, update.metrics.steps)
        assertEquals(1_000L, update.metrics.movingDurationMs)
        assertTrue(update.metrics.totalDistanceMeters > 0.0)
        assertTrue(update.metrics.briskDistanceMeters > 0.0)
        assertTrue(update.metrics.totalCaloriesKcal > 0.0)
    }

    @Test
    fun onStepCounter_setsBaselineAndTracksDailySteps() {
        val engine = StepTrackingEngine(
            initialMetrics = TodayMetrics(dayEpoch = 100L),
            initialStepCounterBaseline = null,
            hasStepCounterSensor = true,
        )

        val first = engine.onStepCounter(totalSinceBoot = 1000f, nowMs = 1_000L)
        assertNotNull(first)
        assertEquals(0, first!!.metrics.steps)

        val second = engine.onStepCounter(totalSinceBoot = 1008f, nowMs = 2_000L)
        assertNotNull(second)
        assertEquals(8, second!!.metrics.steps)
    }

    @Test
    fun onStepDetector_withManualWeight_updatesCaloriesByWeight() {
        val lightEngine = StepTrackingEngine(
            initialMetrics = TodayMetrics(dayEpoch = 100L),
            initialStepCounterBaseline = null,
            hasStepCounterSensor = false,
        )
        val heavyEngine = StepTrackingEngine(
            initialMetrics = TodayMetrics(dayEpoch = 100L),
            initialStepCounterBaseline = null,
            hasStepCounterSensor = false,
        )

        val lightProfile = UserProfile(weightKg = 50.0)
        val heavyProfile = UserProfile(weightKg = 90.0)

        val timestamps = listOf(0L, 600_000_000L, 1_200_000_000L, 1_800_000_000L)
        timestamps.forEachIndexed { index, ts ->
            lightEngine.onStepDetector(timestampNs = ts, nowMs = (index + 1) * 1_000L, profile = lightProfile)
            heavyEngine.onStepDetector(timestampNs = ts, nowMs = (index + 1) * 1_000L, profile = heavyProfile)
        }

        assertTrue(heavyEngine.metrics.totalCaloriesKcal > lightEngine.metrics.totalCaloriesKcal)
    }

    @Test
    fun resetForDay_clearsMetricsAndBaseline() {
        val engine = StepTrackingEngine(
            initialMetrics = TodayMetrics(
                dayEpoch = 100L,
                steps = 123,
                totalDistanceMeters = 456.0,
                movingDurationMs = 5_000L,
                briskDistanceMeters = 50.0,
                briskDurationMs = 600L,
                runningDistanceMeters = 30.0,
                runningDurationMs = 400L,
                lastUpdatedEpochMs = 9_999L,
            ),
            initialStepCounterBaseline = 800f,
            hasStepCounterSensor = true,
        )

        val reset = engine.resetForDay(dayEpoch = 101L, nowMs = 10_000L)

        assertEquals(101L, reset.metrics.dayEpoch)
        assertEquals(0, reset.metrics.steps)
        assertEquals(0.0, reset.metrics.totalDistanceMeters, 0.0001)
        assertEquals(0L, reset.metrics.movingDurationMs)
        assertEquals(0L, reset.metrics.briskDurationMs)
        assertEquals(0L, reset.metrics.runningDurationMs)
        assertEquals(null, reset.stepCounterBaseline)
    }
}
