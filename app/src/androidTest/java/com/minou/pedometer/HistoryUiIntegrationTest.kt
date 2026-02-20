package com.minou.pedometer

import android.content.Context
import android.os.Environment
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryUiIntegrationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var context: Context

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        MetricsRepository.initialize(context)
        MetricsRepository.resetAllForTesting()
        clearExports(context)
    }

    @After
    fun tearDown() = runBlocking {
        clearExports(context)
        MetricsRepository.resetAllForTesting()
    }

    @Test
    fun historyTab_showsWeeklyAndMonthlySummary() = runBlocking {
        val today = currentDayEpoch()
        MetricsRepository.replaceHistoryForTesting(
            listOf(
                DailyHistory(
                    dayEpoch = today,
                    steps = 1000,
                    totalDistanceMeters = 750.0,
                    movingDurationMs = 1_000L,
                    briskDistanceMeters = 0.0,
                    briskDurationMs = 0L,
                    runningDistanceMeters = 0.0,
                    runningDurationMs = 0L,
                ),
                DailyHistory(
                    dayEpoch = today - 3,
                    steps = 2000,
                    totalDistanceMeters = 1400.0,
                    movingDurationMs = 2_000L,
                    briskDistanceMeters = 0.0,
                    briskDurationMs = 0L,
                    runningDistanceMeters = 0.0,
                    runningDurationMs = 0L,
                ),
                DailyHistory(
                    dayEpoch = today - 20,
                    steps = 1500,
                    totalDistanceMeters = 1200.0,
                    movingDurationMs = 1_500L,
                    briskDistanceMeters = 0.0,
                    briskDurationMs = 0L,
                    runningDistanceMeters = 0.0,
                    runningDurationMs = 0L,
                ),
            )
        )

        composeRule.waitUntil(timeoutMillis = 5_000) {
            MetricsRepository.uiState.value.history.size == 3
        }

        composeRule.onNodeWithText("履歴").performClick()
        composeRule.onNodeWithText("3000 歩").assertIsDisplayed()
        composeRule.onNodeWithText("4500 歩").assertIsDisplayed()
    }

    @Test
    fun historyTab_filterChip_updatesDisplayedCount() = runBlocking {
        val today = currentDayEpoch()
        MetricsRepository.replaceHistoryForTesting(
            listOf(
                DailyHistory(
                    dayEpoch = today,
                    steps = 1000,
                    totalDistanceMeters = 700.0,
                    movingDurationMs = 1_000L,
                    briskDistanceMeters = 0.0,
                    briskDurationMs = 0L,
                    runningDistanceMeters = 0.0,
                    runningDurationMs = 0L,
                ),
                DailyHistory(
                    dayEpoch = today - 5,
                    steps = 1500,
                    totalDistanceMeters = 1100.0,
                    movingDurationMs = 1_500L,
                    briskDistanceMeters = 0.0,
                    briskDurationMs = 0L,
                    runningDistanceMeters = 0.0,
                    runningDurationMs = 0L,
                ),
                DailyHistory(
                    dayEpoch = today - 12,
                    steps = 2000,
                    totalDistanceMeters = 1400.0,
                    movingDurationMs = 2_000L,
                    briskDistanceMeters = 0.0,
                    briskDurationMs = 0L,
                    runningDistanceMeters = 0.0,
                    runningDurationMs = 0L,
                ),
            )
        )

        composeRule.waitUntil(timeoutMillis = 5_000) {
            MetricsRepository.uiState.value.history.size == 3
        }

        composeRule.onNodeWithText("履歴").performClick()
        composeRule.onNodeWithText("表示件数: 3 日分").assertIsDisplayed()

        composeRule.onNodeWithText("7日").performClick()
        composeRule.onNodeWithText("表示件数: 2 日分").assertIsDisplayed()

        composeRule.onNodeWithText("30日").performClick()
        composeRule.onNodeWithText("表示件数: 3 日分").assertIsDisplayed()
    }

    @Test
    fun historyTab_csvExport_createsFileAndShowsMessage() = runBlocking {
        val today = currentDayEpoch()
        MetricsRepository.replaceHistoryForTesting(
            listOf(
                DailyHistory(
                    dayEpoch = today,
                    steps = 1200,
                    totalDistanceMeters = 900.0,
                    movingDurationMs = 1_200L,
                    briskDistanceMeters = 0.0,
                    briskDurationMs = 0L,
                    runningDistanceMeters = 0.0,
                    runningDurationMs = 0L,
                )
            )
        )

        composeRule.waitUntil(timeoutMillis = 5_000) {
            MetricsRepository.uiState.value.history.size == 1
        }

        val exportDir = exportsDir(context)
        val beforeCount = exportDir.listFiles()?.size ?: 0

        composeRule.onNodeWithText("履歴").performClick()
        composeRule.onNodeWithText("CSVエクスポート").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            (exportsDir(context).listFiles()?.size ?: 0) > beforeCount
        }

        composeRule.onNodeWithText("CSVを保存しました", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("最新CSVを開く").assertIsDisplayed()
    }

    private fun clearExports(context: Context) {
        val dir = exportsDir(context)
        if (!dir.exists()) return
        dir.listFiles()?.forEach { file -> file.delete() }
    }

    private fun exportsDir(context: Context): File {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        return File(baseDir, "exports")
    }
}
