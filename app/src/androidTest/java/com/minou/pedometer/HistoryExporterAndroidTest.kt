package com.minou.pedometer

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryExporterAndroidTest {

    @Test
    fun export_createsCsvFile() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val history = listOf(
            DailyHistory(
                dayEpoch = currentDayEpoch(),
                steps = 1234,
                totalDistanceMeters = 987.6,
                movingDurationMs = 54_321L,
                briskDistanceMeters = 111.1,
                briskDurationMs = 2_222L,
                runningDistanceMeters = 222.2,
                runningDurationMs = 3_333L,
            )
        )

        val result = HistoryCsvExporter.export(context, history)

        assertTrue(result.isSuccess)
        val file = result.getOrThrow()
        assertTrue(file.exists())
        assertTrue(file.length() > 0)
    }

    @Test
    fun buildViewIntent_hasCsvMimeTypeAndReadPermissionFlag() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val history = listOf(
            DailyHistory(
                dayEpoch = currentDayEpoch(),
                steps = 10,
                totalDistanceMeters = 12.3,
                movingDurationMs = 1_000L,
                briskDistanceMeters = 0.0,
                briskDurationMs = 0L,
                runningDistanceMeters = 0.0,
                runningDurationMs = 0L,
            )
        )

        val file = HistoryCsvExporter.export(context, history).getOrThrow()
        val intent = HistoryCsvExporter.buildViewIntent(context, file)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("text/csv", intent.type)
        assertNotNull(intent.data)
        assertTrue((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
    }
}
