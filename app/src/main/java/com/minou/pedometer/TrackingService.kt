package com.minou.pedometer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.abs

class TrackingService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var stepCounterSensor: Sensor? = null
    private var stepDetectorSensor: Sensor? = null

    private var listenersRegistered = false
    private var isStartInProgress = false
    private var stepCounterRegistered = false

    private var metrics = TodayMetrics()
    private var stepCounterBaseline: Float? = null
    private lateinit var engine: StepTrackingEngine

    private var lastPersistEpochMs: Long = 0L
    private var lastPersistedSteps: Int = 0
    private var lastNotificationEpochMs: Long = 0L
    private var lastNotifiedSteps: Int = -1
    private var lastNotifiedDistanceMeters: Double = -1.0
    private var isScreenInteractive: Boolean = true
    private var startRetryAttempts: Int = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startRetryRunnable = Runnable { startTrackingIfNeeded() }
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> isScreenInteractive = true
                Intent.ACTION_SCREEN_OFF -> isScreenInteractive = false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        MetricsRepository.initialize(applicationContext)

        metrics = MetricsRepository.uiState.value.metrics
        if (metrics.dayEpoch != currentDayEpoch()) {
            MetricsRepository.resetForDay(currentDayEpoch())
            metrics = MetricsRepository.uiState.value.metrics
        }
        lastPersistedSteps = metrics.steps

        stepCounterBaseline = MetricsRepository.getStepCounterBaseline(metrics.dayEpoch)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

        engine = StepTrackingEngine(
            initialMetrics = metrics,
            initialStepCounterBaseline = stepCounterBaseline,
            hasStepCounterSensor = stepCounterSensor != null,
        )

        isScreenInteractive = currentScreenInteractiveState()
        registerScreenStateReceiver()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, null -> startTrackingIfNeeded()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(startRetryRunnable)
        runCatching { unregisterReceiver(screenStateReceiver) }

        if (listenersRegistered) {
            sensorManager.unregisterListener(this)
        }

        listenersRegistered = false
        isStartInProgress = false
        stepCounterRegistered = false

        MetricsRepository.replaceMetrics(metrics, persistImmediately = true)
        MetricsRepository.flushPendingMetrics()
        MetricsRepository.setTracking(false)

        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Synchronized
    override fun onSensorChanged(event: SensorEvent) {
        if (isStartInProgress) return

        runCatching {
            ensureCurrentDay()

            when (event.sensor.type) {
                Sensor.TYPE_STEP_COUNTER -> {
                    val total = event.values.firstOrNull() ?: return
                    val update = engine.onStepCounter(totalSinceBoot = total, nowMs = System.currentTimeMillis())
                    if (update != null) {
                        publishUpdate(update)
                    }
                }

                Sensor.TYPE_STEP_DETECTOR -> {
                    val profile = MetricsRepository.uiState.value.userProfile
                    val update = engine.onStepDetector(
                        timestampNs = event.timestamp,
                        nowMs = System.currentTimeMillis(),
                        profile = profile,
                    )
                    publishUpdate(update)
                }
            }
        }.onFailure {
            Log.e(TAG, "Sensor handling failed.", it)
            updateNotificationIfNeeded(force = true)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun startTrackingIfNeeded() {
        if (listenersRegistered) {
            updateNotificationIfNeeded(force = true)
            return
        }

        if (isStartInProgress) {
            return
        }

        isStartInProgress = true
        mainHandler.removeCallbacks(startRetryRunnable)

        val counterRegistered = runCatching {
            stepCounterSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            } ?: false
        }.getOrDefault(false)

        val detectorRegistered = runCatching {
            stepDetectorSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            } ?: false
        }.getOrDefault(false)

        val supported = counterRegistered || detectorRegistered
        MetricsRepository.setSensorSupported(supported)

        if (!supported) {
            isStartInProgress = false
            if (startRetryAttempts < MAX_START_RETRIES) {
                startRetryAttempts++
                mainHandler.postDelayed(startRetryRunnable, START_RETRY_DELAY_MS)
            } else {
                startRetryAttempts = 0
                MetricsRepository.setTracking(false)
                stopSelf()
            }
            return
        }

        startRetryAttempts = 0
        listenersRegistered = true
        stepCounterRegistered = counterRegistered
        isStartInProgress = false

        engine = StepTrackingEngine(
            initialMetrics = metrics,
            initialStepCounterBaseline = stepCounterBaseline,
            hasStepCounterSensor = stepCounterRegistered,
        )

        MetricsRepository.setTracking(true)
        startForeground(NOTIFICATION_ID, buildNotification())
        updateNotificationCache()
    }

    private fun ensureCurrentDay() {
        val today = currentDayEpoch()
        if (metrics.dayEpoch == today) return

        MetricsRepository.resetForDay(today)

        val reset = engine.resetForDay(
            dayEpoch = today,
            nowMs = System.currentTimeMillis(),
        )

        metrics = reset.metrics
        stepCounterBaseline = null
        lastPersistEpochMs = 0L
        lastPersistedSteps = metrics.steps

        updateNotificationIfNeeded(force = true)
    }

    private fun publishUpdate(update: EngineUpdate) {
        metrics = update.metrics

        if (update.stepCounterBaseline != null && update.stepCounterBaseline != stepCounterBaseline) {
            stepCounterBaseline = update.stepCounterBaseline
            MetricsRepository.saveStepCounterBaseline(metrics.dayEpoch, update.stepCounterBaseline)
        }

        val now = System.currentTimeMillis()
        val elapsedSincePersist = now - lastPersistEpochMs
        val stepDeltaSincePersist = abs(metrics.steps - lastPersistedSteps)
        val shouldPersist =
            elapsedSincePersist >= METRICS_PERSIST_MAX_INTERVAL_MS ||
                (stepDeltaSincePersist >= METRICS_PERSIST_STEP_DELTA &&
                    elapsedSincePersist >= METRICS_PERSIST_MIN_INTERVAL_MS)

        MetricsRepository.replaceMetrics(metrics, persistImmediately = shouldPersist)
        if (shouldPersist) {
            lastPersistEpochMs = now
            lastPersistedSteps = metrics.steps
        }

        updateNotificationIfNeeded()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_title),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val distanceKm = metrics.totalDistanceMeters / 1_000.0
        val content = String.format(Locale.JAPAN, "%d歩  %.2fkm", metrics.steps, distanceKm)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_walk)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun updateNotificationIfNeeded(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val distanceDelta = abs(metrics.totalDistanceMeters - lastNotifiedDistanceMeters)
        val stepDelta = abs(metrics.steps - lastNotifiedSteps)
        val updateIntervalMs = if (isScreenInteractive) {
            NOTIFICATION_UPDATE_INTERVAL_SCREEN_ON_MS
        } else {
            NOTIFICATION_UPDATE_INTERVAL_SCREEN_OFF_MS
        }
        val stepThreshold = if (isScreenInteractive) {
            NOTIFICATION_STEP_DELTA_SCREEN_ON
        } else {
            NOTIFICATION_STEP_DELTA_SCREEN_OFF
        }

        val shouldUpdate = force ||
            lastNotificationEpochMs == 0L ||
            stepDelta >= stepThreshold ||
            distanceDelta >= NOTIFICATION_DISTANCE_DELTA_M ||
            now - lastNotificationEpochMs >= updateIntervalMs

        if (!shouldUpdate) return

        updateNotification()
        updateNotificationCache(now)
    }

    private fun updateNotificationCache(timestamp: Long = System.currentTimeMillis()) {
        lastNotificationEpochMs = timestamp
        lastNotifiedSteps = metrics.steps
        lastNotifiedDistanceMeters = metrics.totalDistanceMeters
    }

    private fun currentScreenInteractiveState(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isInteractive ?: true
    }

    private fun registerScreenStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenStateReceiver, filter)
        }
    }

    companion object {
        private const val TAG = "TrackingService"
        private const val CHANNEL_ID = "tracking_channel"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_START = "com.minou.pedometer.action.START"
        private const val ACTION_STOP = "com.minou.pedometer.action.STOP"

        private const val METRICS_PERSIST_MIN_INTERVAL_MS = 1_000L
        private const val METRICS_PERSIST_MAX_INTERVAL_MS = 5_000L
        private const val METRICS_PERSIST_STEP_DELTA = 1
        private const val NOTIFICATION_UPDATE_INTERVAL_SCREEN_ON_MS = 2_000L
        private const val NOTIFICATION_UPDATE_INTERVAL_SCREEN_OFF_MS = 10_000L
        private const val NOTIFICATION_STEP_DELTA_SCREEN_ON = 1
        private const val NOTIFICATION_STEP_DELTA_SCREEN_OFF = 8
        private const val NOTIFICATION_DISTANCE_DELTA_M = 5.0
        private const val MAX_START_RETRIES = 3
        private const val START_RETRY_DELAY_MS = 2_000L

        fun start(context: Context) {
            val intent = Intent(context, TrackingService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }
}
