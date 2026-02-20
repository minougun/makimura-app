package com.minou.pedometer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class HistorySafExporterTest {

    @Test
    fun suggestedFilename_hasExpectedPattern() {
        val fixed = LocalDateTime.of(2026, 2, 20, 13, 45, 8)

        val filename = HistorySafExporter.suggestedFilename(fixed)

        assertEquals("pedometer_history_20260220_134508.csv", filename)
    }

    @Test
    fun suggestedFilename_hasCsvExtension() {
        val filename = HistorySafExporter.suggestedFilename(LocalDateTime.of(2030, 1, 1, 0, 0, 0))

        assertTrue(filename.startsWith("pedometer_history_"))
        assertTrue(filename.endsWith(".csv"))
    }
}
