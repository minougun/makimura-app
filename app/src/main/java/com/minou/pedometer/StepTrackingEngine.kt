package com.minou.pedometer

import kotlin.math.max

data class EngineUpdate(
    val metrics: TodayMetrics,
    val stepCounterBaseline: Float?,
)

class StepTrackingEngine(
    initialMetrics: TodayMetrics,
    initialStepCounterBaseline: Float?,
    private val hasStepCounterSensor: Boolean,
) {
    @Volatile
    var metrics: TodayMetrics = initialMetrics
        private set

    @Volatile
    var stepCounterBaseline: Float? = initialStepCounterBaseline
        private set

    private var lastStepDetectorTimestampNs: Long? = null
    private val recentStepTimestampsNs = ArrayDeque<Long>()

    @Synchronized
    fun resetForDay(dayEpoch: Long, nowMs: Long): EngineUpdate {
        metrics = TodayMetrics(dayEpoch = dayEpoch, lastUpdatedEpochMs = nowMs)
        stepCounterBaseline = null
        lastStepDetectorTimestampNs = null
        recentStepTimestampsNs.clear()

        return EngineUpdate(metrics = metrics, stepCounterBaseline = stepCounterBaseline)
    }

    @Synchronized
    fun onStepCounter(totalSinceBoot: Float, nowMs: Long): EngineUpdate? {
        var baselineInitialized = false
        val baseline = stepCounterBaseline ?: totalSinceBoot.also {
            stepCounterBaseline = it
            baselineInitialized = true
        }

        val dailySteps = max(0, (totalSinceBoot - baseline).toInt())

        if (dailySteps != metrics.steps) {
            metrics = metrics.copy(
                steps = dailySteps,
                lastUpdatedEpochMs = nowMs,
            )
            return EngineUpdate(metrics = metrics, stepCounterBaseline = stepCounterBaseline)
        }

        if (baselineInitialized) {
            return EngineUpdate(metrics = metrics, stepCounterBaseline = stepCounterBaseline)
        }

        return null
    }

    @Synchronized
    fun onStepDetector(timestampNs: Long, nowMs: Long, profile: UserProfile): EngineUpdate {
        recentStepTimestampsNs.addLast(timestampNs)
        while (recentStepTimestampsNs.isNotEmpty() && timestampNs - recentStepTimestampsNs.first() > CADENCE_WINDOW_NS) {
            recentStepTimestampsNs.removeFirst()
        }

        val cadenceSpm = cadenceSpm(timestampNs)
        val movementZone = zoneFromCadence(cadenceSpm)
        val stride = strideLengthMeters(movementZone, profile)
        val stepCaloriesKcal = estimateStepCaloriesKcal(
            zone = movementZone,
            profile = profile,
            strideMeters = stride,
        )

        val movementDeltaMs = lastStepDetectorTimestampNs?.let { previous ->
            val delta = (timestampNs - previous) / 1_000_000L
            if (delta in MIN_STEP_GAP_MS..MAX_STEP_GAP_MS) delta else 0L
        } ?: 0L

        lastStepDetectorTimestampNs = timestampNs

        val nextSteps = if (hasStepCounterSensor) metrics.steps else metrics.steps + 1

        metrics = metrics.copy(
            steps = nextSteps,
            totalDistanceMeters = metrics.totalDistanceMeters + stride,
            totalCaloriesKcal = metrics.totalCaloriesKcal + stepCaloriesKcal,
            movingDurationMs = metrics.movingDurationMs + movementDeltaMs,
            briskDistanceMeters = metrics.briskDistanceMeters + if (movementZone == MovementZone.BRISK) stride else 0.0,
            briskDurationMs = metrics.briskDurationMs + if (movementZone == MovementZone.BRISK) movementDeltaMs else 0L,
            runningDistanceMeters = metrics.runningDistanceMeters + if (movementZone == MovementZone.RUNNING) stride else 0.0,
            runningDurationMs = metrics.runningDurationMs + if (movementZone == MovementZone.RUNNING) movementDeltaMs else 0L,
            lastUpdatedEpochMs = nowMs,
        )

        return EngineUpdate(metrics = metrics, stepCounterBaseline = stepCounterBaseline)
    }

    private fun cadenceSpm(nowNs: Long): Double {
        if (recentStepTimestampsNs.size < 3) return 0.0

        val oldest = recentStepTimestampsNs.first()
        val windowSeconds = max((nowNs - oldest) / 1_000_000_000.0, 1.0)
        val stepIntervals = (recentStepTimestampsNs.size - 1).coerceAtLeast(1)
        return stepIntervals / windowSeconds * 60.0
    }

    private fun estimateStepCaloriesKcal(
        zone: MovementZone,
        profile: UserProfile,
        strideMeters: Double,
    ): Double {
        val distanceKm = strideMeters / 1_000.0
        val weightKg = estimatedWeightKg(profile)
        val kcalPerKgPerKm = when (zone) {
            MovementZone.WALKING -> 0.90
            MovementZone.BRISK -> 1.00
            MovementZone.RUNNING -> 1.08
        }

        return (weightKg * kcalPerKgPerKm * distanceKm).coerceAtLeast(0.0)
    }

    private fun estimatedWeightKg(profile: UserProfile): Double {
        profile.normalizedWeightKg?.let { return it }

        val heightMeters = profile.normalizedHeightCm / 100.0
        val bmi = when (profile.sex) {
            UserSex.MALE -> 22.5
            UserSex.FEMALE -> 21.5
            UserSex.OTHER -> 22.0
        }

        return (bmi * heightMeters * heightMeters).coerceIn(40.0, 120.0)
    }

    private fun strideLengthMeters(zone: MovementZone, profile: UserProfile): Double {
        val stride = StrideEstimator.estimate(profile)
        return when (zone) {
            MovementZone.WALKING -> stride.walkMeters
            MovementZone.BRISK -> stride.briskMeters
            MovementZone.RUNNING -> stride.runMeters
        }
    }

    companion object {
        private const val CADENCE_WINDOW_NS = 12_000_000_000L

        private const val BRISK_CADENCE_SPM = 120.0
        private const val RUNNING_CADENCE_SPM = 150.0

        private const val MIN_STEP_GAP_MS = 200L
        private const val MAX_STEP_GAP_MS = 3_000L

        fun zoneFromCadence(cadenceSpm: Double): MovementZone {
            return when {
                cadenceSpm >= RUNNING_CADENCE_SPM -> MovementZone.RUNNING
                cadenceSpm >= BRISK_CADENCE_SPM -> MovementZone.BRISK
                else -> MovementZone.WALKING
            }
        }
    }
}
