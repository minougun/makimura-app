package com.minou.pedometer

object WeatherRefreshPolicy {
    private const val STALE_DURATION_MS = 3 * 60 * 60 * 1_000L

    fun shouldAutoRefresh(
        updatedAtEpochMs: Long,
        staleDurationMs: Long = STALE_DURATION_MS,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (updatedAtEpochMs <= 0L) return true
        val elapsed = nowEpochMs - updatedAtEpochMs
        return elapsed >= staleDurationMs
    }
}
