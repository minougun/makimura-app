package com.minou.pedometer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        MetricsRepository.initialize(context.applicationContext)

        if (MetricsRepository.uiState.value.isTracking) {
            TrackingService.start(context.applicationContext)
        }
    }
}
