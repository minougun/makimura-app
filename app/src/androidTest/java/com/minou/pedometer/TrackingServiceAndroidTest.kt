package com.minou.pedometer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackingServiceAndroidTest {

    @Test
    fun startAndStopFromActivity_eventuallyStopsTracking() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        MetricsRepository.initialize(context)
        MetricsRepository.resetAllForTesting()

        grantRuntimePermissionsIfNeeded(context)

        ActivityScenario.launch(MainActivity::class.java).use {
            TrackingService.start(context)
            Thread.sleep(1_500L)
            TrackingService.stop(context)

            val deadline = SystemClock.elapsedRealtime() + 8_000L
            while (SystemClock.elapsedRealtime() < deadline && MetricsRepository.uiState.value.isTracking) {
                Thread.sleep(150L)
            }

            assertFalse(
                "Tracking state should be false after stop command.",
                MetricsRepository.uiState.value.isTracking,
            )
        }
    }

    private fun grantRuntimePermissionsIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            grantRuntimePermissionIfNeeded(context, Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            grantRuntimePermissionIfNeeded(context, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun grantRuntimePermissionIfNeeded(context: Context, permission: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, permission)
        }
    }
}
