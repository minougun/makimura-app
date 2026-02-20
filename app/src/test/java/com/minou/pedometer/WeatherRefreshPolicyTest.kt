package com.minou.pedometer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherRefreshPolicyTest {

    @Test
    fun shouldAutoRefresh_whenNeverUpdated_returnsTrue() {
        assertTrue(WeatherRefreshPolicy.shouldAutoRefresh(updatedAtEpochMs = 0L, nowEpochMs = 1_000L))
    }

    @Test
    fun shouldAutoRefresh_whenStale_returnsTrue() {
        val now = 10_000_000L
        val updatedAt = now - (3 * 60 * 60 * 1_000L) - 1L
        assertTrue(WeatherRefreshPolicy.shouldAutoRefresh(updatedAtEpochMs = updatedAt, nowEpochMs = now))
    }

    @Test
    fun shouldAutoRefresh_whenFresh_returnsFalse() {
        val now = 10_000_000L
        val updatedAt = now - (60 * 60 * 1_000L)
        assertFalse(WeatherRefreshPolicy.shouldAutoRefresh(updatedAtEpochMs = updatedAt, nowEpochMs = now))
    }
}
