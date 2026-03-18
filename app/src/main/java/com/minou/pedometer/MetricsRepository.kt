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
    private const val KEY_PERSISTENCE_ENABLED = "persistence_enabled"

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
    private var persistenceEnabled = false
    private var historyObserverStarted = false
    private var stepCounterBaselineDay: Long? = null
    private var stepCounterBaselineValue: Float? = null
    private var inMemoryHistory: List<DailyHistory> = emptyList()

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
        persistenceEnabled = prefs.getBoolean(KEY_PERSISTENCE_ENABLED, false)

        if (persistenceEnabled) {
            flushPendingArchiveIfNeeded()
        } else {
            clearPersistedStateLocked(clearPreference = false)
        }

        val profile = if (persistenceEnabled) loadUserProfileFromDisk() else UserProfile()
        val weatherContext = if (persistenceEnabled) loadWeatherContextFromDisk() else WeatherContext()
        val metrics = if (persistenceEnabled) loadMetricsFromDisk() else TodayMetrics(dayEpoch = currentDayEpoch())
        val weatherUpdatedAt = if (persistenceEnabled) prefs.getLong(KEY_WEATHER_UPDATED_AT, 0L) else 0L
        val weatherCity = if (persistenceEnabled) loadWeatherCityFromDisk() else MakimuraShop.ADDRESS_LABEL
        val isTracking = if (persistenceEnabled) prefs.getBoolean(KEY_IS_TRACKING, false) else false
        val sensorSupported = if (persistenceEnabled) prefs.getBoolean(KEY_SENSOR_SUPPORTED, true) else true

        pendingMetrics = metrics
        metricsDirty = false
        inMemoryHistory = emptyList()
        stepCounterBaselineDay = if (persistenceEnabled && prefs.contains(KEY_BASELINE_VALUE)) {
            prefs.getLong(KEY_BASELINE_DAY, Long.MIN_VALUE)
        } else {
            null
        }
        stepCounterBaselineValue = if (persistenceEnabled && prefs.contains(KEY_BASELINE_VALUE)) {
            prefs.getFloat(KEY_BASELINE_VALUE, 0f)
        } else {
            null
        }

        _uiState.value = TrackingUiState(
            metrics = metrics,
            history = inMemoryHistory,
            userProfile = profile,
            weatherContext = weatherContext,
            weatherCity = weatherCity,
            weatherUpdatedAtEpochMs = weatherUpdatedAt,
            isTracking = isTracking,
            sensorSupported = sensorSupported,
            persistenceEnabled = persistenceEnabled,
        )

        if (persistenceEnabled) {
            startHistoryObserverIfNeeded()
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
        if (persistenceEnabled) {
            saveMetricsToDisk(metrics)
        }
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
        if (persistenceEnabled) {
            saveMetricsToDisk(reset)
        }
        metricsDirty = false

        _uiState.update { it.copy(metrics = reset) }
    }

    fun setTracking(isTracking: Boolean) {
        ensureInitialized()
        if (_uiState.value.isTracking == isTracking) return
        if (persistenceEnabled) {
            prefs.edit().putBoolean(KEY_IS_TRACKING, isTracking).apply()
        }
        _uiState.update { it.copy(isTracking = isTracking) }
    }

    fun setSensorSupported(supported: Boolean) {
        ensureInitialized()
        if (_uiState.value.sensorSupported == supported) return
        if (persistenceEnabled) {
            prefs.edit().putBoolean(KEY_SENSOR_SUPPORTED, supported).apply()
        }
        _uiState.update { it.copy(sensorSupported = supported) }
    }

    fun getStepCounterBaseline(dayEpoch: Long): Float? {
        ensureInitialized()
        return if (stepCounterBaselineDay == dayEpoch) {
            stepCounterBaselineValue
        } else {
            null
        }
    }

    fun saveStepCounterBaseline(dayEpoch: Long, baseline: Float) {
        ensureInitialized()
        stepCounterBaselineDay = dayEpoch
        stepCounterBaselineValue = baseline
        if (persistenceEnabled) {
            prefs.edit()
                .putLong(KEY_BASELINE_DAY, dayEpoch)
                .putFloat(KEY_BASELINE_VALUE, baseline)
                .apply()
        }
    }

    fun clearStepCounterBaseline() {
        ensureInitialized()
        stepCounterBaselineDay = null
        stepCounterBaselineValue = null
        if (persistenceEnabled) {
            prefs.edit()
                .remove(KEY_BASELINE_DAY)
                .remove(KEY_BASELINE_VALUE)
                .apply()
        }
    }

    fun updateUserProfile(profile: UserProfile) {
        ensureInitialized()

        val normalized = profile.copy(
            heightCm = profile.normalizedHeightCm,
            strideScale = profile.normalizedStrideScale,
            weightKg = profile.normalizedWeightKg,
        )
        if (_uiState.value.userProfile == normalized) return

        if (persistenceEnabled) {
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
        }

        _uiState.update { it.copy(userProfile = normalized) }
    }

    fun updateWeatherContext(weatherContext: WeatherContext, updatedAtEpochMs: Long = System.currentTimeMillis()) {
        ensureInitialized()

        val normalized = weatherContext.copy(
            temperatureC = weatherContext.normalizedTemperatureC,
        )
        val currentState = _uiState.value
        if (
            currentState.weatherContext == normalized &&
            currentState.weatherUpdatedAtEpochMs == updatedAtEpochMs
        ) {
            return
        }

        if (persistenceEnabled) {
            prefs.edit()
                .putString(KEY_WEATHER_CONDITION, normalized.condition.name)
                .putInt(KEY_WEATHER_TEMPERATURE, normalized.temperatureC)
                .putLong(KEY_WEATHER_UPDATED_AT, updatedAtEpochMs)
                .apply()
        }

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
        if (_uiState.value.weatherCity == normalized) return
        if (persistenceEnabled) {
            prefs.edit()
                .putString(KEY_WEATHER_CITY, normalized)
                .apply()
        }

        _uiState.update { it.copy(weatherCity = normalized) }
    }

    fun setPersistenceEnabled(enabled: Boolean) {
        ensureInitialized()
        if (persistenceEnabled == enabled) return

        persistenceEnabled = enabled
        prefs.edit().putBoolean(KEY_PERSISTENCE_ENABLED, enabled).apply()

        if (enabled) {
            persistCurrentStateToDisk()
            startHistoryObserverIfNeeded()
        } else {
            clearPersistedStateLocked(clearPreference = false)
        }

        _uiState.update { it.copy(persistenceEnabled = enabled) }
    }

    fun clearAllData() {
        ensureInitialized()

        persistenceEnabled = false
        clearPersistedStateLocked(clearPreference = false)
        pendingMetrics = TodayMetrics(dayEpoch = currentDayEpoch())
        metricsDirty = false
        inMemoryHistory = emptyList()
        stepCounterBaselineDay = null
        stepCounterBaselineValue = null

        _uiState.value = TrackingUiState(
            metrics = TodayMetrics(dayEpoch = currentDayEpoch()),
            history = emptyList(),
            userProfile = UserProfile(),
            weatherContext = WeatherContext(),
            weatherCity = MakimuraShop.ADDRESS_LABEL,
            weatherUpdatedAtEpochMs = 0L,
            isTracking = false,
            sensorSupported = true,
            persistenceEnabled = false,
        )
    }

    @VisibleForTesting
    suspend fun replaceHistoryForTesting(history: List<DailyHistory>) {
        ensureInitialized()

        inMemoryHistory = history.sortedByDescending { it.dayEpoch }.take(HISTORY_LIMIT)
        _uiState.update { it.copy(history = inMemoryHistory) }

        val dao = database.dailyHistoryDao()
        dao.clearAll()

        if (inMemoryHistory.isNotEmpty()) {
            dao.upsertAll(inMemoryHistory.map { day ->
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
        persistenceEnabled = false
        clearPersistedStateLocked(clearPreference = false)
        metricsDirty = false
        inMemoryHistory = emptyList()
        stepCounterBaselineDay = null
        stepCounterBaselineValue = null

        _uiState.update {
            it.copy(
                metrics = reset,
                isTracking = false,
                sensorSupported = true,
                history = emptyList(),
                persistenceEnabled = false,
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

        if (!persistenceEnabled) {
            inMemoryHistory = upsertRecentHistory(
                history = inMemoryHistory,
                day = metrics.toHistoryEntity().toModel(),
                limit = HISTORY_LIMIT,
            )
            _uiState.update { it.copy(history = inMemoryHistory) }
            return
        }

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
        if (!persistenceEnabled) return
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

    private fun startHistoryObserverIfNeeded() {
        if (historyObserverStarted) return
        historyObserverStarted = true

        scope.launch {
            database.dailyHistoryDao().observeRecent(HISTORY_LIMIT).collect { rows ->
                if (!persistenceEnabled) return@collect

                val history = rows.map { it.toModel() }
                if (history == inMemoryHistory) return@collect
                inMemoryHistory = history
                _uiState.update { state ->
                    state.copy(history = history)
                }
            }
        }
    }

    private fun persistCurrentStateToDisk() {
        val state = _uiState.value
        saveMetricsToDisk(state.metrics)
        val editor = prefs.edit()
            .putBoolean(KEY_IS_TRACKING, state.isTracking)
            .putBoolean(KEY_SENSOR_SUPPORTED, state.sensorSupported)
            .putInt(KEY_PROFILE_HEIGHT, state.userProfile.normalizedHeightCm)
            .putString(KEY_PROFILE_SEX, state.userProfile.sex.name)
            .putDouble(KEY_PROFILE_STRIDE_SCALE, state.userProfile.normalizedStrideScale)
            .putString(KEY_WEATHER_CONDITION, state.weatherContext.condition.name)
            .putInt(KEY_WEATHER_TEMPERATURE, state.weatherContext.normalizedTemperatureC)
            .putString(KEY_WEATHER_CITY, state.weatherCity.trim().ifBlank { MakimuraShop.ADDRESS_LABEL }.take(120))
            .putLong(KEY_WEATHER_UPDATED_AT, state.weatherUpdatedAtEpochMs)

        val normalizedWeightKg = state.userProfile.normalizedWeightKg
        if (normalizedWeightKg == null) {
            editor.remove(KEY_PROFILE_WEIGHT)
        } else {
            editor.putDouble(KEY_PROFILE_WEIGHT, normalizedWeightKg)
        }

        if (stepCounterBaselineDay != null && stepCounterBaselineValue != null) {
            editor
                .putLong(KEY_BASELINE_DAY, stepCounterBaselineDay!!)
                .putFloat(KEY_BASELINE_VALUE, stepCounterBaselineValue!!)
        } else {
            editor
                .remove(KEY_BASELINE_DAY)
                .remove(KEY_BASELINE_VALUE)
        }

        editor.apply()

        scope.launch {
            val dao = database.dailyHistoryDao()
            dao.clearAll()
            if (state.history.isNotEmpty()) {
                dao.upsertAll(state.history.map { it.toHistoryEntity() })
            }
        }
    }

    private fun clearPersistedStateLocked(clearPreference: Boolean) {
        val editor = prefs.edit()
            .remove(KEY_DAY)
            .remove(KEY_STEPS)
            .remove(KEY_TOTAL_DISTANCE)
            .remove(KEY_TOTAL_CALORIES)
            .remove(KEY_MOVING_DURATION)
            .remove(KEY_BRISK_DISTANCE)
            .remove(KEY_BRISK_DURATION)
            .remove(KEY_RUNNING_DISTANCE)
            .remove(KEY_RUNNING_DURATION)
            .remove(KEY_LAST_UPDATED)
            .remove(KEY_IS_TRACKING)
            .remove(KEY_SENSOR_SUPPORTED)
            .remove(KEY_BASELINE_DAY)
            .remove(KEY_BASELINE_VALUE)
            .remove(KEY_PROFILE_HEIGHT)
            .remove(KEY_PROFILE_SEX)
            .remove(KEY_PROFILE_STRIDE_SCALE)
            .remove(KEY_PROFILE_WEIGHT)
            .remove(KEY_WEATHER_CONDITION)
            .remove(KEY_WEATHER_TEMPERATURE)
            .remove(KEY_WEATHER_CITY)
            .remove(KEY_WEATHER_UPDATED_AT)
            .remove(KEY_PENDING_ARCHIVE_DAY)
            .remove(KEY_PENDING_ARCHIVE_STEPS)
            .remove(KEY_PENDING_ARCHIVE_TOTAL_DISTANCE)
            .remove(KEY_PENDING_ARCHIVE_TOTAL_CALORIES)
            .remove(KEY_PENDING_ARCHIVE_MOVING_DURATION)
            .remove(KEY_PENDING_ARCHIVE_BRISK_DISTANCE)
            .remove(KEY_PENDING_ARCHIVE_BRISK_DURATION)
            .remove(KEY_PENDING_ARCHIVE_RUNNING_DISTANCE)
            .remove(KEY_PENDING_ARCHIVE_RUNNING_DURATION)
        if (clearPreference) {
            editor.remove(KEY_PERSISTENCE_ENABLED)
        } else {
            editor.putBoolean(KEY_PERSISTENCE_ENABLED, false)
        }
        editor.apply()

        scope.launch {
            database.dailyHistoryDao().clearAll()
        }
    }
}

internal fun upsertRecentHistory(
    history: List<DailyHistory>,
    day: DailyHistory,
    limit: Int,
): List<DailyHistory> {
    return (listOf(day) + history.filterNot { it.dayEpoch == day.dayEpoch })
        .sortedByDescending { it.dayEpoch }
        .take(limit)
}
