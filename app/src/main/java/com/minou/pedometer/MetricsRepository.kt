package com.minou.pedometer

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object MetricsRepository {
    private const val PREFS_NAME = "pedometer_metrics"
    private const val HISTORY_DB_NAME = "pedometer_history.db"
    private const val HISTORY_LIMIT = 30

    private const val KEY_DAY = "day_epoch"
    private const val KEY_STEPS = "steps"
    private const val KEY_TOTAL_DISTANCE = "total_distance"
    private const val KEY_TOTAL_CALORIES = "total_calories"
    private const val KEY_MOVING_DURATION = "moving_duration"
    private const val KEY_BRISK_DISTANCE = "brisk_distance"
    private const val KEY_BRISK_DURATION = "brisk_duration"
    private const val KEY_RUNNING_DISTANCE = "running_distance"
    private const val KEY_RUNNING_DURATION = "running_duration"
    private const val KEY_LAST_UPDATED = "last_updated"

    private const val KEY_IS_TRACKING = "is_tracking"
    private const val KEY_SENSOR_SUPPORTED = "sensor_supported"

    private const val KEY_BASELINE_DAY = "baseline_day"
    private const val KEY_BASELINE_VALUE = "baseline_value"

    private const val KEY_PROFILE_HEIGHT = "profile_height"
    private const val KEY_PROFILE_SEX = "profile_sex"
    private const val KEY_PROFILE_STRIDE_SCALE = "profile_stride_scale"
    private const val KEY_PROFILE_WEIGHT = "profile_weight"
    private const val KEY_WEATHER_CONDITION = "weather_condition"
    private const val KEY_WEATHER_TEMPERATURE = "weather_temperature_c"
    private const val KEY_WEATHER_CITY = "weather_city"
    private const val KEY_WEATHER_UPDATED_AT = "weather_updated_at"

    private const val KEY_PENDING_ARCHIVE_DAY = "pending_archive_day"
    private const val KEY_PENDING_ARCHIVE_STEPS = "pending_archive_steps"
    private const val KEY_PENDING_ARCHIVE_TOTAL_DISTANCE = "pending_archive_total_distance"
    private const val KEY_PENDING_ARCHIVE_TOTAL_CALORIES = "pending_archive_total_calories"
    private const val KEY_PENDING_ARCHIVE_MOVING_DURATION = "pending_archive_moving_duration"
    private const val KEY_PENDING_ARCHIVE_BRISK_DISTANCE = "pending_archive_brisk_distance"
    private const val KEY_PENDING_ARCHIVE_BRISK_DURATION = "pending_archive_brisk_duration"
    private const val KEY_PENDING_ARCHIVE_RUNNING_DISTANCE = "pending_archive_running_distance"
    private const val KEY_PENDING_ARCHIVE_RUNNING_DURATION = "pending_archive_running_duration"

    private lateinit var prefs: SharedPreferences
    private lateinit var database: HistoryDatabase

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var pendingMetrics: TodayMetrics? = null
    private var metricsDirty = false

    private val _uiState = MutableStateFlow(TrackingUiState())
    val uiState: StateFlow<TrackingUiState> = _uiState.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        if (::prefs.isInitialized) return

        val appContext = context.applicationContext

        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        database = Room.databaseBuilder(appContext, HistoryDatabase::class.java, HISTORY_DB_NAME)
            .fallbackToDestructiveMigration()
            .build()
        flushPendingArchiveIfNeeded()

        val profile = loadUserProfileFromDisk()
        val weatherContext = loadWeatherContextFromDisk()
        val metrics = loadMetricsFromDisk()

        pendingMetrics = metrics
        metricsDirty = false

        _uiState.value = TrackingUiState(
            metrics = metrics,
            userProfile = profile,
            weatherContext = weatherContext,
            weatherCity = loadWeatherCityFromDisk(),
            weatherUpdatedAtEpochMs = prefs.getLong(KEY_WEATHER_UPDATED_AT, 0L),
            isTracking = prefs.getBoolean(KEY_IS_TRACKING, false),
            sensorSupported = prefs.getBoolean(KEY_SENSOR_SUPPORTED, true),
        )

        scope.launch {
            database.dailyHistoryDao().observeRecent(HISTORY_LIMIT).collect { rows ->
                _uiState.update { state ->
                    state.copy(history = rows.map { it.toModel() })
                }
            }
        }
    }

    @Synchronized
    fun replaceMetrics(metrics: TodayMetrics, persistImmediately: Boolean = false) {
        ensureInitialized()

        val safeMetrics = if (metrics.dayEpoch == currentDayEpoch()) {
            metrics
        } else {
            archiveSafelyIfNeeded(metrics)
            TodayMetrics(dayEpoch = currentDayEpoch(), lastUpdatedEpochMs = System.currentTimeMillis())
        }

        pendingMetrics = safeMetrics
        metricsDirty = true
        _uiState.update { it.copy(metrics = safeMetrics) }

        if (persistImmediately) {
            flushPendingMetrics()
        }
    }

    @Synchronized
    fun flushPendingMetrics() {
        ensureInitialized()
        if (!metricsDirty) return

        val metrics = pendingMetrics ?: return
        saveMetricsToDisk(metrics)
        metricsDirty = false
    }

    @Synchronized
    fun resetForDay(dayEpoch: Long = currentDayEpoch()) {
        ensureInitialized()

        val previous = _uiState.value.metrics
        if (previous.dayEpoch != dayEpoch) {
            archiveSafelyIfNeeded(previous)
        }

        clearStepCounterBaseline()

        val reset = TodayMetrics(dayEpoch = dayEpoch, lastUpdatedEpochMs = System.currentTimeMillis())
        pendingMetrics = reset
        metricsDirty = true
        saveMetricsToDisk(reset)
        metricsDirty = false

        _uiState.update { it.copy(metrics = reset) }
    }

    fun setTracking(isTracking: Boolean) {
        ensureInitialized()
        prefs.edit().putBoolean(KEY_IS_TRACKING, isTracking).apply()
        _uiState.update { it.copy(isTracking = isTracking) }
    }

    fun setSensorSupported(supported: Boolean) {
        ensureInitialized()
        prefs.edit().putBoolean(KEY_SENSOR_SUPPORTED, supported).apply()
        _uiState.update { it.copy(sensorSupported = supported) }
    }

    fun getStepCounterBaseline(dayEpoch: Long): Float? {
        ensureInitialized()
        val storedDay = prefs.getLong(KEY_BASELINE_DAY, Long.MIN_VALUE)
        if (storedDay != dayEpoch || !prefs.contains(KEY_BASELINE_VALUE)) return null
        return prefs.getFloat(KEY_BASELINE_VALUE, 0f)
    }

    fun saveStepCounterBaseline(dayEpoch: Long, baseline: Float) {
        ensureInitialized()
        prefs.edit()
            .putLong(KEY_BASELINE_DAY, dayEpoch)
            .putFloat(KEY_BASELINE_VALUE, baseline)
            .apply()
    }

    fun clearStepCounterBaseline() {
        ensureInitialized()
        prefs.edit()
            .remove(KEY_BASELINE_DAY)
            .remove(KEY_BASELINE_VALUE)
            .apply()
    }

    fun updateUserProfile(profile: UserProfile) {
        ensureInitialized()

        val normalized = profile.copy(
            heightCm = profile.normalizedHeightCm,
            strideScale = profile.normalizedStrideScale,
            weightKg = profile.normalizedWeightKg,
        )

        val editor = prefs.edit()
            .putInt(KEY_PROFILE_HEIGHT, normalized.heightCm)
            .putString(KEY_PROFILE_SEX, normalized.sex.name)
            .putDouble(KEY_PROFILE_STRIDE_SCALE, normalized.strideScale)
        if (normalized.weightKg == null) {
            editor.remove(KEY_PROFILE_WEIGHT)
        } else {
            editor.putDouble(KEY_PROFILE_WEIGHT, normalized.weightKg)
        }
        editor.apply()

        _uiState.update { it.copy(userProfile = normalized) }
    }

    fun updateWeatherContext(weatherContext: WeatherContext, updatedAtEpochMs: Long = System.currentTimeMillis()) {
        ensureInitialized()

        val normalized = weatherContext.copy(
            temperatureC = weatherContext.normalizedTemperatureC,
        )

        prefs.edit()
            .putString(KEY_WEATHER_CONDITION, normalized.condition.name)
            .putInt(KEY_WEATHER_TEMPERATURE, normalized.temperatureC)
            .putLong(KEY_WEATHER_UPDATED_AT, updatedAtEpochMs)
            .apply()

        _uiState.update {
            it.copy(
                weatherContext = normalized,
                weatherUpdatedAtEpochMs = updatedAtEpochMs,
            )
        }
    }

    fun updateWeatherCity(city: String) {
        ensureInitialized()

        val normalized = city.trim().ifBlank { MakimuraShop.ADDRESS_LABEL }.take(120)
        prefs.edit()
            .putString(KEY_WEATHER_CITY, normalized)
            .apply()

        _uiState.update { it.copy(weatherCity = normalized) }
    }

    @VisibleForTesting
    suspend fun replaceHistoryForTesting(history: List<DailyHistory>) {
        ensureInitialized()

        val dao = database.dailyHistoryDao()
        dao.clearAll()

        if (history.isNotEmpty()) {
            dao.upsertAll(history.map { day ->
                DailyHistoryEntity(
                    dayEpoch = day.dayEpoch,
                    steps = day.steps,
                    totalDistanceMeters = day.totalDistanceMeters,
                    totalCaloriesKcal = day.totalCaloriesKcal,
                    movingDurationMs = day.movingDurationMs,
                    briskDistanceMeters = day.briskDistanceMeters,
                    briskDurationMs = day.briskDurationMs,
                    runningDistanceMeters = day.runningDistanceMeters,
                    runningDurationMs = day.runningDurationMs,
                )
            })
        }
    }

    @VisibleForTesting
    suspend fun resetAllForTesting(dayEpoch: Long = currentDayEpoch()) {
        replaceHistoryForTesting(emptyList())
        clearStepCounterBaseline()

        val reset = TodayMetrics(dayEpoch = dayEpoch, lastUpdatedEpochMs = System.currentTimeMillis())
        pendingMetrics = reset
        metricsDirty = true
        saveMetricsToDisk(reset)
        metricsDirty = false

        prefs.edit()
            .putBoolean(KEY_IS_TRACKING, false)
            .putBoolean(KEY_SENSOR_SUPPORTED, true)
            .apply()

        _uiState.update {
            it.copy(
                metrics = reset,
                isTracking = false,
                sensorSupported = true,
                history = emptyList(),
            )
        }
    }

    private fun ensureInitialized() {
        check(::prefs.isInitialized) { "MetricsRepository.initialize(context) must be called first." }
    }

    private fun loadMetricsFromDisk(): TodayMetrics {
        val stored = readMetricsRaw()
        val today = currentDayEpoch()

        if (stored.dayEpoch == today) {
            return stored
        }

        archiveSafelyIfNeeded(stored)
        clearStepCounterBaseline()

        val reset = TodayMetrics(dayEpoch = today, lastUpdatedEpochMs = System.currentTimeMillis())
        saveMetricsToDisk(reset)
        return reset
    }

    private fun readMetricsRaw(): TodayMetrics {
        val today = currentDayEpoch()

        return TodayMetrics(
            dayEpoch = prefs.getLong(KEY_DAY, today),
            steps = prefs.getInt(KEY_STEPS, 0),
            totalDistanceMeters = getDouble(KEY_TOTAL_DISTANCE, 0.0),
            totalCaloriesKcal = getDouble(KEY_TOTAL_CALORIES, 0.0),
            movingDurationMs = prefs.getLong(KEY_MOVING_DURATION, 0L),
            briskDistanceMeters = getDouble(KEY_BRISK_DISTANCE, 0.0),
            briskDurationMs = prefs.getLong(KEY_BRISK_DURATION, 0L),
            runningDistanceMeters = getDouble(KEY_RUNNING_DISTANCE, 0.0),
            runningDurationMs = prefs.getLong(KEY_RUNNING_DURATION, 0L),
            lastUpdatedEpochMs = prefs.getLong(KEY_LAST_UPDATED, 0L),
        )
    }

    private fun loadUserProfileFromDisk(): UserProfile {
        val default = UserProfile()
        val height = prefs.getInt(KEY_PROFILE_HEIGHT, default.heightCm)
        val strideScale = getDouble(KEY_PROFILE_STRIDE_SCALE, default.strideScale)
        val weightKg = if (prefs.contains(KEY_PROFILE_WEIGHT)) {
            getDouble(KEY_PROFILE_WEIGHT, 0.0)
        } else {
            null
        }

        val sex = runCatching {
            UserSex.valueOf(prefs.getString(KEY_PROFILE_SEX, default.sex.name) ?: default.sex.name)
        }.getOrElse { default.sex }

        return UserProfile(
            heightCm = height,
            sex = sex,
            strideScale = strideScale,
            weightKg = weightKg,
        ).copy(
            heightCm = height.coerceIn(120, 220),
            strideScale = strideScale.coerceIn(0.7, 1.3),
            weightKg = weightKg?.coerceIn(30.0, 200.0),
        )
    }

    private fun loadWeatherContextFromDisk(): WeatherContext {
        val default = WeatherContext()
        val condition = runCatching {
            WeatherCondition.valueOf(
                prefs.getString(KEY_WEATHER_CONDITION, default.condition.name) ?: default.condition.name
            )
        }.getOrElse { default.condition }

        val temperatureC = prefs.getInt(KEY_WEATHER_TEMPERATURE, default.temperatureC)

        return WeatherContext(
            condition = condition,
            temperatureC = temperatureC,
        ).copy(temperatureC = temperatureC.coerceIn(-20, 45))
    }

    private fun loadWeatherCityFromDisk(): String {
        return prefs.getString(KEY_WEATHER_CITY, MakimuraShop.ADDRESS_LABEL)
            ?.trim()
            ?.ifBlank { MakimuraShop.ADDRESS_LABEL }
            ?.take(120)
            ?: MakimuraShop.ADDRESS_LABEL
    }

    private fun archiveSafelyIfNeeded(metrics: TodayMetrics) {
        if (!metrics.hasActivity()) return

        savePendingArchive(metrics)

        scope.launch {
            runCatching {
                database.dailyHistoryDao().upsert(metrics.toHistoryEntity())
            }.onSuccess {
                val pending = readPendingArchive()
                if (pending?.dayEpoch == metrics.dayEpoch) {
                    clearPendingArchive()
                }
            }
        }
    }

    private fun saveMetricsToDisk(metrics: TodayMetrics) {
        prefs.edit()
            .putLong(KEY_DAY, metrics.dayEpoch)
            .putInt(KEY_STEPS, metrics.steps)
            .putLong(KEY_MOVING_DURATION, metrics.movingDurationMs)
            .putLong(KEY_BRISK_DURATION, metrics.briskDurationMs)
            .putLong(KEY_RUNNING_DURATION, metrics.runningDurationMs)
            .putLong(KEY_LAST_UPDATED, metrics.lastUpdatedEpochMs)
            .putDouble(KEY_TOTAL_DISTANCE, metrics.totalDistanceMeters)
            .putDouble(KEY_TOTAL_CALORIES, metrics.totalCaloriesKcal)
            .putDouble(KEY_BRISK_DISTANCE, metrics.briskDistanceMeters)
            .putDouble(KEY_RUNNING_DISTANCE, metrics.runningDistanceMeters)
            .apply()
    }

    private fun SharedPreferences.Editor.putDouble(key: String, value: Double): SharedPreferences.Editor {
        return putLong(key, java.lang.Double.doubleToRawLongBits(value))
    }

    private fun getDouble(key: String, defaultValue: Double): Double {
        if (!prefs.contains(key)) return defaultValue
        return java.lang.Double.longBitsToDouble(
            prefs.getLong(key, java.lang.Double.doubleToRawLongBits(defaultValue))
        )
    }

    private fun flushPendingArchiveIfNeeded() {
        val pending = readPendingArchive() ?: return
        scope.launch {
            runCatching {
                database.dailyHistoryDao().upsert(pending.toHistoryEntity())
            }.onSuccess {
                val currentPending = readPendingArchive()
                if (currentPending?.dayEpoch == pending.dayEpoch) {
                    clearPendingArchive()
                }
            }
        }
    }

    private fun savePendingArchive(metrics: TodayMetrics) {
        prefs.edit()
            .putLong(KEY_PENDING_ARCHIVE_DAY, metrics.dayEpoch)
            .putInt(KEY_PENDING_ARCHIVE_STEPS, metrics.steps)
            .putLong(KEY_PENDING_ARCHIVE_MOVING_DURATION, metrics.movingDurationMs)
            .putLong(KEY_PENDING_ARCHIVE_BRISK_DURATION, metrics.briskDurationMs)
            .putLong(KEY_PENDING_ARCHIVE_RUNNING_DURATION, metrics.runningDurationMs)
            .putDouble(KEY_PENDING_ARCHIVE_TOTAL_DISTANCE, metrics.totalDistanceMeters)
            .putDouble(KEY_PENDING_ARCHIVE_TOTAL_CALORIES, metrics.totalCaloriesKcal)
            .putDouble(KEY_PENDING_ARCHIVE_BRISK_DISTANCE, metrics.briskDistanceMeters)
            .putDouble(KEY_PENDING_ARCHIVE_RUNNING_DISTANCE, metrics.runningDistanceMeters)
            .apply()
    }

    private fun readPendingArchive(): TodayMetrics? {
        if (!prefs.contains(KEY_PENDING_ARCHIVE_DAY)) return null
        return TodayMetrics(
            dayEpoch = prefs.getLong(KEY_PENDING_ARCHIVE_DAY, Long.MIN_VALUE),
            steps = prefs.getInt(KEY_PENDING_ARCHIVE_STEPS, 0),
            totalDistanceMeters = getDouble(KEY_PENDING_ARCHIVE_TOTAL_DISTANCE, 0.0),
            totalCaloriesKcal = getDouble(KEY_PENDING_ARCHIVE_TOTAL_CALORIES, 0.0),
            movingDurationMs = prefs.getLong(KEY_PENDING_ARCHIVE_MOVING_DURATION, 0L),
            briskDistanceMeters = getDouble(KEY_PENDING_ARCHIVE_BRISK_DISTANCE, 0.0),
            briskDurationMs = prefs.getLong(KEY_PENDING_ARCHIVE_BRISK_DURATION, 0L),
            runningDistanceMeters = getDouble(KEY_PENDING_ARCHIVE_RUNNING_DISTANCE, 0.0),
            runningDurationMs = prefs.getLong(KEY_PENDING_ARCHIVE_RUNNING_DURATION, 0L),
            lastUpdatedEpochMs = 0L,
        )
    }

    private fun clearPendingArchive() {
        prefs.edit()
            .remove(KEY_PENDING_ARCHIVE_DAY)
            .remove(KEY_PENDING_ARCHIVE_STEPS)
            .remove(KEY_PENDING_ARCHIVE_TOTAL_DISTANCE)
            .remove(KEY_PENDING_ARCHIVE_TOTAL_CALORIES)
            .remove(KEY_PENDING_ARCHIVE_MOVING_DURATION)
            .remove(KEY_PENDING_ARCHIVE_BRISK_DISTANCE)
            .remove(KEY_PENDING_ARCHIVE_BRISK_DURATION)
            .remove(KEY_PENDING_ARCHIVE_RUNNING_DISTANCE)
            .remove(KEY_PENDING_ARCHIVE_RUNNING_DURATION)
            .apply()
    }
}
