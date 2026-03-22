package com.minou.pedometer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MetricsRepository.initialize(applicationContext)

        setContent {
            PedometerTheme {
                PedometerScreen()
            }
        }
    }
}

private enum class AppTab(val label: String) {
    HOME("ホーム"),
    ORDER("注文"),
    ACTIVITY("運動"),
    SETTINGS("設定"),
}

private enum class HistoryRange(val label: String, val days: Int?) {
    ALL("全期間", null),
    WEEK("7日", 7),
    MONTH("30日", 30),
}

private const val WEATHER_INPUT_TAG = "WeatherInput"
private val temperatureInputRegex = Regex("""-?\d{0,2}""")

private val AppBackgroundGradient = Brush.linearGradient(
    colors = listOf(
        Color(0xFFF0D4A0),
        Color(0xFFF0A86A),
        Color(0xFFE8944E),
    ),
    start = Offset.Zero,
    end = Offset(1600f, 2200f),
)

private val PedometerColorScheme = lightColorScheme(
    primary = Color(0xFF7D1F12),
    onPrimary = Color.White,
    secondary = Color(0xFFC73A27),
    onSecondary = Color.White,
    tertiary = Color(0xFF4E6B4C),
    background = Color(0xFFF7F2E8),
    onBackground = Color(0xFF1E1A16),
    surface = Color(0xFFFEFAF3),
    onSurface = Color(0xFF1E1A16),
)

@Composable
private fun recommendationChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.secondary,
    selectedLabelColor = MaterialTheme.colorScheme.onSecondary,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondary,
)

@Composable
private fun PedometerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PedometerColorScheme,
        content = content,
    )
}

@Composable
private fun PedometerScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by MetricsRepository.uiState.collectAsStateWithLifecycle()
    val recommendation = remember(
        uiState.metrics.steps,
        uiState.weatherContext.condition,
        uiState.weatherContext.temperatureC,
        uiState.recommendationPreferences,
    ) {
        RamenRecommendationEngine.recommend(
            metrics = uiState.metrics,
            weatherContext = uiState.weatherContext,
            preferences = uiState.recommendationPreferences,
        )
    }

    val permissions = remember { requiredPermissions() }
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.HOME) }
    var selectedOrderItems by rememberSaveable {
        mutableStateOf(RamenMenuCatalog.requiredItemNames.toList())
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val allGranted = permissions.all { permission ->
            result[permission] == true || hasPermission(context, permission)
        }
        if (allGranted) {
            TrackingService.start(context)
        }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val latestState = MetricsRepository.uiState.value
                val shouldRefreshNow =
                    latestState.weatherCity != MakimuraShop.ADDRESS_LABEL ||
                        WeatherRefreshPolicy.shouldAutoRefresh(
                            updatedAtEpochMs = latestState.weatherUpdatedAtEpochMs,
                            staleDurationMs = MakimuraShop.WEATHER_REFRESH_INTERVAL_MS,
                        )

                if (shouldRefreshNow) {
                    val result = withContext(Dispatchers.IO) {
                        WeatherAutoFetch.fetchForMakimuraShop()
                    }
                    result.onSuccess { fetched ->
                        MetricsRepository.updateWeatherCity(MakimuraShop.ADDRESS_LABEL)
                        MetricsRepository.updateWeatherContext(
                            weatherContext = fetched.weatherContext,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        )
                    }.onFailure { error ->
                        Log.w(WEATHER_INPUT_TAG, "Auto weather refresh failed", error)
                    }
                }

                delay(60_000L)
            }
        }
    }

    LaunchedEffect(recommendation, uiState.metrics.dayEpoch) {
        MetricsRepository.recordRecommendationHistory(
            RecommendationHistoryEntry(
                createdAtEpochMs = System.currentTimeMillis(),
                dayEpoch = uiState.metrics.dayEpoch,
                tier = recommendation.tier,
                steps = uiState.metrics.steps,
                weatherCondition = uiState.weatherContext.condition,
                temperatureC = uiState.weatherContext.temperatureC,
                itemNames = recommendation.items.map { it.name },
                totalYen = recommendation.totalYen,
                reason = recommendation.reason.take(300),
                signature = RamenRecommendationEngine.signature(
                    metrics = uiState.metrics,
                    weatherContext = uiState.weatherContext,
                    preferences = uiState.recommendationPreferences,
                    recommendation = recommendation,
                ),
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackgroundGradient),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "麺家まきむら",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "その日の気分と運動量と天気で、今日の一杯を提案",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White.copy(alpha = 0.62f),
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                ) {
                    TabRow(
                        selectedTabIndex = selectedTab.ordinal,
                        containerColor = Color.Transparent,
                    ) {
                        AppTab.entries.forEach { tab ->
                            Tab(
                                selected = selectedTab == tab,
                                onClick = { selectedTab = tab },
                                text = { Text(tab.label) },
                            )
                        }
                    }
                }

                when (selectedTab) {
                    AppTab.HOME -> HomeTabContent(
                        uiState = uiState,
                        recommendation = recommendation,
                        onApplyRecommendation = {
                            selectedOrderItems = recommendation.items.map { item -> item.name }
                        },
                    )

                    AppTab.ORDER -> OrderTab(
                        recommendation = recommendation,
                        recommendationPreferences = uiState.recommendationPreferences,
                        selectedItemNames = selectedOrderItems.toSet(),
                        onSelectedItemNamesChange = { names ->
                            selectedOrderItems = names.toList()
                        },
                    )

                    AppTab.ACTIVITY -> ActivityTab(
                        uiState = uiState,
                        onStart = {
                            if (hasAllPermissions(context, permissions)) {
                                TrackingService.start(context)
                            } else {
                                permissionLauncher.launch(permissions)
                            }
                        },
                        onStop = { TrackingService.stop(context) },
                        onOpenSettings = { selectedTab = AppTab.SETTINGS },
                    )

                    AppTab.SETTINGS -> SettingsTab(
                        profile = uiState.userProfile,
                        recommendationPreferences = uiState.recommendationPreferences,
                        weatherContext = uiState.weatherContext,
                        weatherCity = uiState.weatherCity,
                        weatherUpdatedAtEpochMs = uiState.weatherUpdatedAtEpochMs,
                        persistenceEnabled = uiState.persistenceEnabled,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeTabContent(
    uiState: TrackingUiState,
    recommendation: RamenRecommendation,
    onApplyRecommendation: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RecommendationPreferencesCard(
            recommendationPreferences = uiState.recommendationPreferences,
        )

        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val toppingHighlights = remember(recommendation) {
                    RamenRecommendationEngine.toppingHighlights(recommendation)
                }
                Text(
                    text = "本日の推しトッピング",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                toppingHighlights.forEach { item ->
                    Text(
                        text = "・$item",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (toppingHighlights.isEmpty()) {
                    Text(
                        text = "・定番のラーメン構成",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    text = RamenRecommendationEngine.homeSummary(
                        metrics = uiState.metrics,
                        weatherContext = uiState.weatherContext,
                        recommendation = recommendation,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = onApplyRecommendation,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("このおすすめを注文に反映")
                }
            }
        }

        RecommendationCard(
            weatherContext = uiState.weatherContext,
            recommendation = recommendation,
            recommendationPreferences = uiState.recommendationPreferences,
            weatherCity = uiState.weatherCity,
            weatherUpdatedAtEpochMs = uiState.weatherUpdatedAtEpochMs,
        )

        RecommendationHistoryPreviewCard(
            history = uiState.recommendationHistory,
        )

        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("提案に使っている運動データ", style = MaterialTheme.typography.titleSmall)
                MetricRow("歩数", "${uiState.metrics.steps} 歩")
                MetricRow("消費カロリー", formatCalories(uiState.metrics.totalCaloriesKcal))
                MetricRow("早歩き時間", formatDuration(uiState.metrics.briskDurationMs))
                Text(
                    text = "運動の詳細計測は「運動」タブで調整できます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun OrderTab(
    recommendation: RamenRecommendation,
    recommendationPreferences: RecommendationPreferences,
    selectedItemNames: Set<String>,
    onSelectedItemNamesChange: (Set<String>) -> Unit,
) {
    val normalizedSelection = remember(selectedItemNames) {
        selectedItemNames + RamenMenuCatalog.requiredItemNames
    }
    val groupedMenu = remember {
        RamenMenuCatalog.items.groupBy { item -> item.category }
    }
    val totalYen = remember(normalizedSelection) {
        normalizedSelection.sumOf { name -> RamenMenuCatalog.priceTable[name] ?: 0 }
    }
    val recommendedSelection = remember(recommendation) {
        recommendation.items.map { it.name }.toSet() + RamenMenuCatalog.requiredItemNames
    }
    val addedItems = remember(normalizedSelection, recommendedSelection) {
        normalizedSelection.subtract(recommendedSelection).subtract(RamenMenuCatalog.requiredItemNames).sorted()
    }
    val removedItems = remember(normalizedSelection, recommendedSelection) {
        recommendedSelection.subtract(normalizedSelection).subtract(RamenMenuCatalog.requiredItemNames).sorted()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("トッピングを選ぶ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "ラーメンは必須で選択済み。好みでトッピング・ご飯・ドリンクを追加できます。",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (recommendationPreferences.excludedToppings.isNotEmpty()) {
                    Text(
                        "除外中: ${recommendationPreferences.excludedToppings.sorted().joinToString(" / ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Button(
                    onClick = { onSelectedItemNamesChange(recommendedSelection) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("今日のおすすめセットを適用")
                }
                Button(
                    onClick = { onSelectedItemNamesChange(RamenMenuCatalog.requiredItemNames) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("選択をリセット")
                }
            }
        }

        RamenMenuCategory.entries.forEach { category ->
            val menuItems = groupedMenu[category].orEmpty()
            if (menuItems.isEmpty()) return@forEach

            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(category.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    menuItems.forEach { item ->
                        val selected = item.required || normalizedSelection.contains(item.name)
                        FilterChip(
                            selected = selected,
                            onClick = {
                                if (item.required) return@FilterChip
                                val next = RamenMenuCatalog.toggleSelection(
                                    selectedNames = normalizedSelection,
                                    targetName = item.name,
                                )
                                onSelectedItemNamesChange(next)
                            },
                            label = { Text("${item.name} ${formatYen(item.priceYen)}") },
                        )
                    }
                }
            }
        }

        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("現在の注文", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                normalizedSelection
                    .sortedBy { name -> RamenMenuCatalog.priceTable[name] ?: 0 }
                    .forEach { name ->
                        MetricRow(name, formatYen(RamenMenuCatalog.priceTable[name] ?: 0))
                    }
                MetricRow("合計", formatYen(totalYen))
            }
        }

        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("おすすめとの差分", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("追加した項目", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                if (addedItems.isNotEmpty()) {
                    addedItems.forEach { name -> Text("・$name", style = MaterialTheme.typography.bodyMedium) }
                } else {
                    Text("追加なし", style = MaterialTheme.typography.bodyMedium)
                }
                Text("外した項目", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                if (removedItems.isNotEmpty()) {
                    removedItems.forEach { name -> Text("・$name", style = MaterialTheme.typography.bodyMedium) }
                } else {
                    Text("外した項目なし", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ActivityTab(
    uiState: TrackingUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var showHistory by rememberSaveable { mutableStateOf(false) }

    if (showHistory) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { showHistory = false },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("運動ダッシュボードに戻る")
            }
            HistoryTab(
                history = uiState.history,
                recommendationHistory = uiState.recommendationHistory,
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("運動データ（サブ機能）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "このデータはおすすめ精度を上げるために使います。ラーメン選びは「ホーム」「注文」がメインです。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { showHistory = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("運動履歴を開く")
                }
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("天気・体重設定を開く")
                }
            }
        }

        ControlCard(
            isTracking = uiState.isTracking,
            onStart = onStart,
            onStop = onStop,
        )

        if (!uiState.sensorSupported) {
            Text(
                text = LocalContext.current.getString(R.string.sensor_not_supported),
                color = MaterialTheme.colorScheme.error,
            )
        }

        MetricsCard(uiState.metrics)
    }
}

@Composable
private fun HistoryTab(
    history: List<DailyHistory>,
    recommendationHistory: List<RecommendationHistoryEntry>,
) {
    val context = LocalContext.current
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var lastExportedFilePath by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedRange by rememberSaveable { mutableStateOf(HistoryRange.ALL) }
    val scope = rememberCoroutineScope()
    val filteredHistory = remember(history, selectedRange) {
        HistoryAnalytics.filterByLastDays(
            history = history,
            days = selectedRange.days,
        )
    }
    val canExportCurrentRange = filteredHistory.isNotEmpty()
    val latestExportedFile = remember(lastExportedFilePath) {
        lastExportedFilePath?.let(::File)?.takeIf { it.exists() }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri == null) {
            exportMessage = "保存をキャンセルしました。"
            return@rememberLauncherForActivityResult
        }

        scope.launch {
            exportMessage = withContext(Dispatchers.IO) {
                HistorySafExporter.writeToUri(context, uri, filteredHistory).fold(
                    onSuccess = { "保存先にCSVを書き込みました。" },
                    onFailure = { error -> "CSV保存に失敗しました: ${error.message ?: "unknown"}" },
                )
            }
        }
    }

    if (history.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "履歴データはまだありません。日付が変わると自動で保存されます。",
                    modifier = Modifier.padding(16.dp),
                )
            }
            Button(
                onClick = { exportMessage = "履歴データがないためCSVを出力できません。" },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("CSVエクスポート")
            }
            Button(
                onClick = { exportMessage = "履歴データがないためCSV共有はできません。" },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("CSV共有")
            }
            Button(
                onClick = { exportMessage = "履歴データがないため保存できません。" },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存先を選んで保存")
            }
            if (!exportMessage.isNullOrBlank()) {
                Text(
                    text = exportMessage ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            RecommendationHistoryCard(history = recommendationHistory)
        }
        return
    }

    val sortedByDayAsc = remember(filteredHistory) { filteredHistory.sortedBy { it.dayEpoch } }
    val weekly = remember(history) { HistoryAnalytics.weeklySummary(history) }
    val monthly = remember(history) { HistoryAnalytics.monthlySummary(history) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("集計", style = MaterialTheme.typography.titleSmall)
                MetricRow("${weekly.title} 歩数", "${weekly.steps} 歩")
                MetricRow("${weekly.title} 距離", formatDistance(weekly.distanceMeters))
                MetricRow("${weekly.title} 消費", formatCalories(weekly.caloriesKcal))
                MetricRow("${weekly.title} 移動時間", formatDuration(weekly.movingDurationMs))
                Spacer(modifier = Modifier.height(4.dp))
                MetricRow("${monthly.title} 歩数", "${monthly.steps} 歩")
                MetricRow("${monthly.title} 距離", formatDistance(monthly.distanceMeters))
                MetricRow("${monthly.title} 消費", formatCalories(monthly.caloriesKcal))
                MetricRow("${monthly.title} 移動時間", formatDuration(monthly.movingDurationMs))
            }
        }

        AppCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HistoryRange.entries.forEach { range ->
                    FilterChip(
                        selected = selectedRange == range,
                        onClick = { selectedRange = range },
                        label = { Text(range.label) },
                    )
                }
            }
        }

        if (filteredHistory.isEmpty()) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "選択中の期間に履歴データがありません。",
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("直近の歩数推移", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    HistoryBarChart(days = sortedByDayAsc)
                }
            }
        }

        Button(
            onClick = {
                scope.launch {
                    val exportResult = withContext(Dispatchers.IO) {
                        HistoryCsvExporter.export(context, filteredHistory)
                    }

                    exportMessage = exportResult.fold(
                        onSuccess = { file ->
                            lastExportedFilePath = file.absolutePath
                            "CSVを保存しました: ${file.absolutePath}"
                        },
                        onFailure = { error -> "CSV出力に失敗しました: ${error.message ?: "unknown"}" },
                    )
                }
            },
            enabled = canExportCurrentRange,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("CSVエクスポート")
        }

        Button(
            onClick = {
                scope.launch {
                    val exportResult = withContext(Dispatchers.IO) {
                        HistoryCsvExporter.export(context, filteredHistory)
                    }

                    val sharedMessage = exportResult.fold(
                        onSuccess = { file ->
                            lastExportedFilePath = file.absolutePath
                            runCatching {
                                val shareIntent = HistoryCsvExporter.buildShareIntent(context, file)
                                context.startActivity(
                                    Intent.createChooser(shareIntent, "CSVを共有").apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                                "CSV共有シートを開きました。"
                            }.getOrElse { error ->
                                "CSV共有に失敗しました: ${error.message ?: "unknown"}"
                            }
                        },
                        onFailure = { error -> "CSV共有に失敗しました: ${error.message ?: "unknown"}" },
                    )

                    exportMessage = sharedMessage
                }
            },
            enabled = canExportCurrentRange,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("CSV共有")
        }

        Button(
            onClick = {
                val file = latestExportedFile
                if (file == null) {
                    exportMessage = "開けるCSVが見つかりません。先にCSVを出力してください。"
                    return@Button
                }

                exportMessage = runCatching {
                    val viewIntent = HistoryCsvExporter.buildViewIntent(context, file)
                    context.startActivity(
                        Intent.createChooser(viewIntent, "CSVを開く").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                    "最新CSVを開きました。"
                }.getOrElse { error ->
                    "CSVを開けませんでした: ${error.message ?: "unknown"}"
                }
            },
            enabled = latestExportedFile != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("最新CSVを開く")
        }

        Button(
            onClick = {
                createDocumentLauncher.launch(HistorySafExporter.suggestedFilename())
            },
            enabled = canExportCurrentRange,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存先を選んで保存")
        }

        Text(
            text = "表示件数: ${filteredHistory.size} 日分",
            style = MaterialTheme.typography.bodySmall,
        )

        if (!exportMessage.isNullOrBlank()) {
            Text(
                text = exportMessage ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        AppCard(modifier = Modifier.fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(filteredHistory) { day ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(formatDay(day.dayEpoch), fontWeight = FontWeight.SemiBold)
                        Text("${day.steps}歩 / ${formatDistance(day.totalDistanceMeters)} / ${formatCalories(day.totalCaloriesKcal)}")
                    }
                }
            }
        }

        RecommendationHistoryCard(history = recommendationHistory)
    }
}

@Composable
private fun RecommendationHistoryCard(history: List<RecommendationHistoryEntry>) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("おすすめ履歴", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (history.isEmpty()) {
                Text("おすすめ履歴はまだありません。", style = MaterialTheme.typography.bodySmall)
                return@Column
            }

            history.take(10).forEach { entry ->
                Text(
                    "${formatDateTime(entry.createdAtEpochMs)} / ${recommendationTierLabel(entry.tier)} / ${formatYen(entry.totalYen)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(entry.itemNames.joinToString(" / "), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun RecommendationHistoryPreviewCard(history: List<RecommendationHistoryEntry>) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("最近のおすすめ履歴", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (history.isEmpty()) {
                Text("まだおすすめ履歴はありません。", style = MaterialTheme.typography.bodySmall)
                return@Column
            }

            history.take(3).forEach { entry ->
                MetricRow(
                    "${formatDateTime(entry.createdAtEpochMs)} / ${recommendationTierLabel(entry.tier)}",
                    formatYen(entry.totalYen),
                )
            }
        }
    }
}

@Composable
private fun HistoryBarChart(days: List<DailyHistory>) {
    val maxSteps = max(days.maxOfOrNull { it.steps } ?: 1, 1)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
    ) {
        val itemCount = days.size
        if (itemCount == 0) return@Canvas

        val spacing = 6.dp.toPx()
        val availableWidth = size.width - spacing * (itemCount + 1)
        val barWidth = (availableWidth / itemCount).coerceAtLeast(2f)

        days.forEachIndexed { index, day ->
            val ratio = day.steps.toFloat() / maxSteps.toFloat()
            val barHeight = size.height * ratio
            val left = spacing + index * (barWidth + spacing)
            val top = size.height - barHeight

            drawRect(
                color = Color(0xFF3A7AFE),
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight),
            )
        }
    }
}

@Composable
private fun SettingsTab(
    profile: UserProfile,
    recommendationPreferences: RecommendationPreferences,
    weatherContext: WeatherContext,
    weatherCity: String,
    weatherUpdatedAtEpochMs: Long,
    persistenceEnabled: Boolean,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var heightInput by remember(profile) { mutableStateOf(profile.heightCm.toString()) }
    var weightInput by remember(profile) { mutableStateOf(profile.weightKg?.let(::formatWeightInput) ?: "") }
    var selectedSex by remember(profile) { mutableStateOf(profile.sex) }
    var strideScale by remember(profile) { mutableFloatStateOf(profile.strideScale.toFloat()) }
    var calibrationStepsInput by remember { mutableStateOf("") }
    var calibrationDistanceInput by remember { mutableStateOf("") }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var selectedWeather by remember(weatherContext) { mutableStateOf(weatherContext.condition) }
    var temperatureInput by remember(weatherContext) { mutableStateOf(weatherContext.temperatureC.toString()) }
    var weatherCityInput by remember(weatherCity) { mutableStateOf(MakimuraShop.ADDRESS_LABEL) }
    var weatherLoading by remember { mutableStateOf(false) }
    var weatherSaveMessage by remember { mutableStateOf<String?>(null) }
    var weatherMessageIsError by remember { mutableStateOf(false) }
    var persistenceSaveMessage by remember { mutableStateOf<String?>(null) }
    var crowdNote by remember(recommendationPreferences) { mutableStateOf(recommendationPreferences.crowdNote) }
    var crowdNoteSaveMessage by remember { mutableStateOf<String?>(null) }

    val previewProfile = remember(heightInput, weightInput, selectedSex, strideScale) {
        UserProfile(
            heightCm = heightInput.toIntOrNull() ?: profile.heightCm,
            sex = selectedSex,
            strideScale = strideScale.toDouble(),
            weightKg = weightInput.toDoubleOrNull() ?: profile.weightKg,
        )
    }
    val parsedHeight = heightInput.toIntOrNull()
    val isHeightInvalid = parsedHeight == null || parsedHeight !in 120..220
    val parsedWeight = weightInput.toDoubleOrNull()
    val isWeightInvalid = weightInput.isNotEmpty() && (parsedWeight == null || parsedWeight !in 30.0..200.0)
    val parsedTemperature = temperatureInput.toIntOrNull()
    val isTemperatureInvalid = parsedTemperature == null || parsedTemperature !in -20..45

    val strideModel = remember(previewProfile) {
        StrideEstimator.estimate(previewProfile)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("歩幅推定の設定", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = heightInput,
                    onValueChange = { value ->
                        if (value.all { it.isDigit() } || value.isEmpty()) {
                            heightInput = value
                        }
                    },
                    label = { Text("身長 (cm)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = heightInput.isNotEmpty() && isHeightInvalid,
                    supportingText = {
                        if (heightInput.isNotEmpty() && isHeightInvalid) {
                            Text("120〜220 の範囲で入力してください。")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focusState ->
                            if (!focusState.isFocused) {
                                val v = heightInput.toIntOrNull()
                                if (v != null) {
                                    heightInput = v.coerceIn(120, 220).toString()
                                }
                            }
                        },
                )

                OutlinedTextField(
                    value = weightInput,
                    onValueChange = { value ->
                        if (value.matches(Regex("""\d*(\.\d{0,1})?"""))) {
                            weightInput = value
                        }
                    },
                    label = { Text("体重 (kg, 任意)") },
                    placeholder = { Text("未入力なら推定(30〜200kg)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = isWeightInvalid,
                    supportingText = {
                        if (isWeightInvalid) {
                            Text("30.0〜200.0 の範囲で入力してください。")
                        } else {
                            Text("空欄なら身長・性別から推定します。")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focusState ->
                            if (!focusState.isFocused && weightInput.isNotEmpty()) {
                                val v = weightInput.toDoubleOrNull()
                                if (v != null) {
                                    weightInput = formatWeightInput(v.coerceIn(30.0, 200.0))
                                }
                            }
                        },
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UserSex.entries.forEach { sex ->
                        FilterChip(
                            selected = selectedSex == sex,
                            onClick = { selectedSex = sex },
                            label = { Text(sexLabel(sex)) },
                        )
                    }
                }

                Text("歩幅補正: ${"%.2f".format(strideScale)}x")
                Slider(
                    value = strideScale,
                    onValueChange = { strideScale = it },
                    valueRange = 0.7f..1.3f,
                    steps = 11,
                )

                Text("推定歩幅 (歩行): ${formatMeters(strideModel.walkMeters)}")
                Text("推定歩幅 (早歩き): ${formatMeters(strideModel.briskMeters)}")
                Text("推定歩幅 (走行): ${formatMeters(strideModel.runMeters)}")
                Text(
                    "カロリー計算体重: ${
                        previewProfile.normalizedWeightKg?.let { "${formatWeightInput(it)} kg" } ?: "未入力（自動推定）"
                    }"
                )

                Spacer(modifier = Modifier.height(4.dp))
                Text("実測で補正を計算", style = MaterialTheme.typography.titleSmall)

                OutlinedTextField(
                    value = calibrationStepsInput,
                    onValueChange = { value ->
                        if (value.all { it.isDigit() } || value.isEmpty()) {
                            calibrationStepsInput = value
                        }
                    },
                    label = { Text("実測時の歩数") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = calibrationDistanceInput,
                    onValueChange = { value ->
                        if (value.matches(Regex("""\d*(\.\d*)?"""))) {
                            calibrationDistanceInput = value
                        }
                    },
                    label = { Text("実測距離 (m)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = {
                        val measuredSteps = calibrationStepsInput.toIntOrNull()
                        val measuredDistance = calibrationDistanceInput.toDoubleOrNull()
                        val height = heightInput.toIntOrNull() ?: profile.heightCm

                        if (measuredSteps == null || measuredSteps <= 0 || measuredDistance == null || measuredDistance <= 0.0) {
                            saveMessage = "実測歩数と実測距離を正しく入力してください。"
                            return@Button
                        }

                        val baseWalkStride = StrideEstimator.estimate(
                            UserProfile(
                                heightCm = height,
                                sex = selectedSex,
                                strideScale = 1.0,
                            )
                        ).walkMeters

                        val recalculatedScale = ((measuredDistance / measuredSteps) / baseWalkStride)
                            .coerceIn(0.7, 1.3)

                        strideScale = recalculatedScale.toFloat()
                        saveMessage = "補正係数を再計算しました。必要なら「設定を保存」を押してください。"
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("実測から補正算出")
                }

                Button(
                    onClick = {
                        val height = heightInput.toIntOrNull()
                        if (height == null || height !in 120..220) {
                            saveMessage = "身長は 120〜220 cm の範囲で入力してください。"
                            return@Button
                        }
                        if (isWeightInvalid) {
                            saveMessage = "体重は 30.0〜200.0 kg の範囲で入力してください。"
                            return@Button
                        }

                        val weightKg = if (weightInput.isBlank()) null else parsedWeight

                        MetricsRepository.updateUserProfile(
                            UserProfile(
                                heightCm = height,
                                sex = selectedSex,
                                strideScale = strideScale.toDouble(),
                                weightKg = weightKg,
                            )
                        )
                        saveMessage = "保存しました。"
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("設定を保存")
                }

                if (saveMessage != null) {
                    Text(saveMessage!!, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("混雑メモ", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = crowdNote,
                    onValueChange = { value -> crowdNote = value.take(200) },
                    label = { Text("混雑メモ") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Google Maps の公式 API では Popular Times を直接取得できないため、ここでは手動メモで管理します。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Button(
                    onClick = {
                        MetricsRepository.updateRecommendationPreferences(
                            recommendationPreferences.copy(crowdNote = crowdNote)
                        )
                        crowdNoteSaveMessage = "混雑メモを保存しました。"
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("混雑メモを保存")
                }
                crowdNoteSaveMessage?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("ラーメン提案の天候設定", style = MaterialTheme.typography.titleMedium)
                Text(
                    "店舗住所の天気をリアルタイム更新して、おすすめ提案に反映します。",
                    style = MaterialTheme.typography.bodySmall,
                )

                Text("観測地点: ${MakimuraShop.ADDRESS_LABEL}", style = MaterialTheme.typography.bodySmall)

                Button(
                    onClick = {
                        weatherLoading = true
                        weatherSaveMessage = null
                        weatherMessageIsError = false
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                WeatherAutoFetch.fetchForMakimuraShop()
                            }
                            weatherLoading = false

                            result.fold(
                                onSuccess = { fetched ->
                                    selectedWeather = fetched.weatherContext.condition
                                    temperatureInput = fetched.weatherContext.temperatureC.toString()
                                    weatherCityInput = MakimuraShop.ADDRESS_LABEL

                                    MetricsRepository.updateWeatherCity(MakimuraShop.ADDRESS_LABEL)
                                    MetricsRepository.updateWeatherContext(
                                        fetched.weatherContext,
                                        updatedAtEpochMs = System.currentTimeMillis(),
                                    )
                                    weatherSaveMessage = "店舗天気を更新しました。"
                                    weatherMessageIsError = false
                                },
                                onFailure = { error ->
                                    Log.w(WEATHER_INPUT_TAG, "Weather auto fetch failed", error)
                                    weatherSaveMessage = "自動取得に失敗しました。しばらくして再試行してください。"
                                    weatherMessageIsError = true
                                },
                            )
                        }
                    },
                    enabled = !weatherLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (weatherLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("店舗天気を今すぐ更新")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WeatherCondition.entries.forEach { condition ->
                        FilterChip(
                            selected = selectedWeather == condition,
                            onClick = { selectedWeather = condition },
                            label = { Text(weatherLabel(condition)) },
                        )
                    }
                }

                OutlinedTextField(
                    value = temperatureInput,
                    onValueChange = { value ->
                        if (value.matches(temperatureInputRegex)) {
                            temperatureInput = value
                        }
                    },
                    label = { Text("気温 (°C)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = temperatureInput.isNotEmpty() && isTemperatureInvalid,
                    supportingText = {
                        if (temperatureInput.isNotEmpty() && isTemperatureInvalid) {
                            Text("-20〜45 の範囲で入力してください。")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = {
                        val temperature = temperatureInput.toIntOrNull()
                        if (temperature == null || temperature !in -20..45) {
                            weatherSaveMessage = "気温は -20〜45 °C の範囲で入力してください。"
                            weatherMessageIsError = true
                            return@Button
                        }

                        MetricsRepository.updateWeatherContext(
                            WeatherContext(
                                condition = selectedWeather,
                                temperatureC = temperature,
                            )
                        )
                        MetricsRepository.updateWeatherCity(MakimuraShop.ADDRESS_LABEL)
                        weatherSaveMessage = "天候設定を保存しました。"
                        weatherMessageIsError = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("天候設定を保存")
                }

                if (weatherUpdatedAtEpochMs > 0L) {
                    Text(
                        text = "最終更新: ${formatDateTime(weatherUpdatedAtEpochMs)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                weatherSaveMessage?.let { message ->
                    Text(
                        message,
                        color = if (weatherMessageIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
        }

        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("保存設定", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("この端末にデータを保存する")
                    Switch(
                        checked = persistenceEnabled,
                        onCheckedChange = { enabled ->
                            MetricsRepository.setPersistenceEnabled(enabled)
                            persistenceSaveMessage = if (enabled) {
                                "この端末への保存を有効にしました。共有端末ではオフを推奨します。"
                            } else {
                                "この端末への永続保存を無効にしました。以後はアプリ起動中だけ保持します。"
                            }
                        },
                    )
                }
                Text(
                    text = if (persistenceEnabled) {
                        "永続保存: ON。履歴と設定をこの端末に保存します。"
                    } else {
                        "永続保存: OFF。アプリを終了すると履歴と設定を破棄します。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "既定で端末にデータを保存します。共有端末ではオフにすることを推奨します。",
                    style = MaterialTheme.typography.bodySmall,
                )
                persistenceSaveMessage?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        TrackingService.stop(context)
                        MetricsRepository.clearAllData()
                        heightInput = UserProfile().heightCm.toString()
                        weightInput = ""
                        selectedSex = UserSex.OTHER
                        strideScale = 1.0f
                        calibrationStepsInput = ""
                        calibrationDistanceInput = ""
                        selectedWeather = WeatherCondition.SUNNY
                        temperatureInput = WeatherContext().temperatureC.toString()
                        weatherCityInput = MakimuraShop.ADDRESS_LABEL
                        crowdNote = RecommendationPreferences().crowdNote
                        saveMessage = null
                        weatherSaveMessage = null
                        weatherMessageIsError = false
                        persistenceSaveMessage = "保存データを削除しました。"
                        crowdNoteSaveMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("保存データを削除")
                }
                Text(
                    text = "共有端末では使用後に保存データの削除を推奨します。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun RecommendationPreferencesCard(
    recommendationPreferences: RecommendationPreferences,
) {
    val chipColors = recommendationChipColors()
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("おすすめの好み設定", style = MaterialTheme.typography.titleMedium)
            Text("苦手なトッピングや今の気分をおすすめに反映します。", style = MaterialTheme.typography.bodySmall)

            Text("空腹度", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppetiteLevel.entries.forEach { level ->
                    FilterChip(
                        selected = recommendationPreferences.appetiteLevel == level,
                        onClick = {
                            MetricsRepository.updateRecommendationPreferences(
                                recommendationPreferences.copy(appetiteLevel = level)
                            )
                        },
                        colors = chipColors,
                        label = { Text(appetiteLabel(level)) },
                    )
                }
            }

            Text("気分", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoodPreference.entries.forEach { mood ->
                    FilterChip(
                        selected = recommendationPreferences.moodPreference == mood,
                        onClick = {
                            MetricsRepository.updateRecommendationPreferences(
                                recommendationPreferences.copy(moodPreference = mood)
                            )
                        },
                        colors = chipColors,
                        label = { Text(moodLabel(mood)) },
                    )
                }
            }

            Text("除外するトッピング", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "ねぎ",
                    "ニンニク",
                    "コーン",
                    "煮卵",
                    "メンマ",
                    "キムチ",
                    "納豆",
                    "チャーシュー1枚",
                    "チャーシュー2枚",
                    "チャーシュー3枚",
                ).forEach { name ->
                    FilterChip(
                        selected = recommendationPreferences.excludedToppings.contains(name),
                        onClick = {
                            val nextExcluded = if (recommendationPreferences.excludedToppings.contains(name)) {
                                recommendationPreferences.excludedToppings - name
                            } else {
                                recommendationPreferences.excludedToppings + name
                            }
                            MetricsRepository.updateRecommendationPreferences(
                                recommendationPreferences.copy(excludedToppings = nextExcluded)
                            )
                        },
                        colors = chipColors,
                        label = { Text(name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.78f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        content = { content() },
    )
}

@Composable
private fun ControlCard(
    isTracking: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (isTracking) "計測中" else "停止中",
                style = MaterialTheme.typography.titleMedium,
            )

            Button(
                onClick = { if (isTracking) onStop() else onStart() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (isTracking) {
                        LocalContext.current.getString(R.string.stop_tracking)
                    } else {
                        LocalContext.current.getString(R.string.start_tracking)
                    }
                )
            }
        }
    }
}

@Composable
private fun MetricsCard(metrics: TodayMetrics) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MetricRow("歩数", "%d 歩".format(metrics.steps))
            MetricRow("総距離", formatDistance(metrics.totalDistanceMeters))
            MetricRow("平均速度", formatSpeed(metrics.averageSpeedMps))
            MetricRow("消費カロリー", formatCalories(metrics.totalCaloriesKcal))

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "早歩き",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            MetricRow("時間", formatDuration(metrics.briskDurationMs))
            MetricRow("距離", formatDistance(metrics.briskDistanceMeters))

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "走行",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            MetricRow("時間", formatDuration(metrics.runningDurationMs))
            MetricRow("距離", formatDistance(metrics.runningDistanceMeters))
        }
    }
}

@Composable
private fun RecommendationCard(
    weatherContext: WeatherContext,
    recommendation: RamenRecommendation,
    recommendationPreferences: RecommendationPreferences,
    weatherCity: String,
    weatherUpdatedAtEpochMs: Long,
) {
    var showDetailedReason by rememberSaveable { mutableStateOf(false) }
    val shortReason = remember(recommendation.reason) {
        recommendation.reason.split(" / ").firstOrNull().orEmpty()
    }
    val detailedReason = remember(recommendation, recommendationPreferences, weatherContext) {
        buildDetailedRecommendationReason(
            recommendation = recommendation,
            weatherContext = weatherContext,
            preferences = recommendationPreferences,
        )
    }
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "本日のおすすめ",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = weatherCity,
                style = MaterialTheme.typography.bodySmall,
            )
            MetricRow("天気", "${weatherLabel(weatherContext.condition)} / ${weatherContext.temperatureC}°C")
            MetricRow("提案タイプ", recommendationTierLabel(recommendation.tier))

            Spacer(modifier = Modifier.height(4.dp))
            recommendation.items.forEach { item ->
                MetricRow(item.name, formatYen(item.priceYen))
            }

            Spacer(modifier = Modifier.height(4.dp))
            MetricRow("合計", formatYen(recommendation.totalYen))
            FilterChip(
                selected = showDetailedReason,
                onClick = { showDetailedReason = !showDetailedReason },
                label = { Text(if (showDetailedReason) "ひとことで見る" else "詳しく見る") },
            )
            RecommendationInfoPanel(
                title = "提案理由",
                body = if (showDetailedReason) detailedReason else shortReason,
            )
            RecommendationInfoPanel(
                title = "反映中の条件",
                body = recommendationPreferenceSummary(recommendationPreferences),
            )
            if (showDetailedReason && recommendationPreferences.crowdNote.isNotBlank()) {
                RecommendationMemoPanel(
                    title = "混雑メモ",
                    body = recommendationPreferences.crowdNote.trim(),
                )
            }
            if (weatherUpdatedAtEpochMs > 0L) {
                Text(
                    text = "天気更新: ${formatDateTime(weatherUpdatedAtEpochMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun RecommendationInfoPanel(
    title: String,
    body: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = Color.Black.copy(alpha = 0.12f),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun RecommendationMemoPanel(
    title: String,
    body: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = Color(0xAA7A4C1A),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFFFE4BE),
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFFFF5E8),
        )
    }
}

private fun buildDetailedRecommendationReason(
    recommendation: RamenRecommendation,
    weatherContext: WeatherContext,
    preferences: RecommendationPreferences,
): String {
    val lines = mutableListOf<String>()
    lines += "今日は ${recommendationTierLabel(recommendation.tier)}寄りの構成で考えています。"
    lines += when {
        weatherContext.condition == WeatherCondition.RAINY ||
            weatherContext.condition == WeatherCondition.SNOWY ||
            weatherContext.temperatureC <= 8 ->
            "天気は${weatherLabel(weatherContext.condition)}で ${weatherContext.temperatureC}°C なので、温まりやすい方向に寄せました。"
        weatherContext.temperatureC >= 28 ->
            "気温が ${weatherContext.temperatureC}°C と高めなので、重すぎない食べやすさを優先しています。"
        else ->
            "気温と天候が極端ではないため、定番寄りのバランスで組んでいます。"
    }
    lines += "空腹度は「${appetiteLabel(preferences.appetiteLevel)}」、気分は「${moodLabel(preferences.moodPreference)}」として反映しています。"
    if (preferences.excludedToppings.isNotEmpty()) {
        lines += "苦手設定の ${preferences.excludedToppings.sorted().joinToString(" / ")} は候補から外しました。"
    }
    lines += "最終的には ${recommendation.items.joinToString(" + ") { it.name }} を提案し、合計は ${formatYen(recommendation.totalYen)} です。"
    return lines.joinToString("\n")
}

private fun recommendationPreferenceSummary(preferences: RecommendationPreferences): String {
    return listOf(
        "空腹度: ${appetiteLabel(preferences.appetiteLevel)}",
        "気分: ${moodLabel(preferences.moodPreference)}",
        "除外トッピング: ${
            if (preferences.excludedToppings.isEmpty()) "なし" else preferences.excludedToppings.sorted().joinToString(" / ")
        }",
    ).joinToString("\n")
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

private fun requiredPermissions(): Array<String> {
    val permissions = mutableListOf<String>()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        permissions += Manifest.permission.ACTIVITY_RECOGNITION
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions += Manifest.permission.POST_NOTIFICATIONS
    }

    return permissions.toTypedArray()
}

private fun hasAllPermissions(context: Context, permissions: Array<String>): Boolean {
    return permissions.all { permission -> hasPermission(context, permission) }
}

private fun hasPermission(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

private fun sexLabel(sex: UserSex): String {
    return when (sex) {
        UserSex.MALE -> "男性"
        UserSex.FEMALE -> "女性"
        UserSex.OTHER -> "その他"
    }
}

private fun weatherLabel(condition: WeatherCondition): String {
    return when (condition) {
        WeatherCondition.SUNNY -> "晴れ"
        WeatherCondition.CLOUDY -> "くもり"
        WeatherCondition.RAINY -> "雨"
        WeatherCondition.SNOWY -> "雪"
    }
}

private fun appetiteLabel(level: AppetiteLevel): String {
    return when (level) {
        AppetiteLevel.LIGHT -> "軽め"
        AppetiteLevel.NORMAL -> "普通"
        AppetiteLevel.HUNGRY -> "がっつり"
    }
}

private fun moodLabel(mood: MoodPreference): String {
    return when (mood) {
        MoodPreference.ANY -> "おまかせ"
        MoodPreference.RICH -> "こってり"
        MoodPreference.REFRESHING -> "あっさり"
        MoodPreference.WARMING -> "温まりたい"
    }
}

private fun recommendationTierLabel(tier: RecommendationTier): String {
    return when (tier) {
        RecommendationTier.LIGHT -> "ライト"
        RecommendationTier.BALANCE -> "バランス"
        RecommendationTier.REWARD -> "ご褒美"
    }
}

private fun formatDay(dayEpoch: Long): String {
    val date = LocalDate.ofEpochDay(dayEpoch)
    return "%d/%d".format(date.monthValue, date.dayOfMonth)
}

private fun formatDateTime(epochMs: Long): String {
    val dateTime = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    return "%d/%d %02d:%02d".format(
        dateTime.monthValue,
        dateTime.dayOfMonth,
        dateTime.hour,
        dateTime.minute,
    )
}

private fun formatDistance(distanceMeters: Double): String {
    return String.format(Locale.JAPAN, "%.2f km", distanceMeters / 1_000.0)
}

private fun formatMeters(meters: Double): String {
    return String.format(Locale.JAPAN, "%.2f m", meters)
}

private fun formatSpeed(speedMps: Double): String {
    return String.format(Locale.JAPAN, "%.2f km/h", speedMps * 3.6)
}

private fun formatCalories(caloriesKcal: Double): String {
    return String.format(Locale.JAPAN, "%.0f kcal", caloriesKcal)
}

private fun formatYen(priceYen: Int): String {
    return String.format(Locale.JAPAN, "¥%,d", priceYen)
}

private fun formatWeightInput(weightKg: Double): String {
    return if (weightKg % 1.0 == 0.0) {
        String.format(Locale.JAPAN, "%.0f", weightKg)
    } else {
        String.format(Locale.JAPAN, "%.1f", weightKg)
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSec = durationMs / 1_000
    val hours = totalSec / 3_600
    val minutes = (totalSec % 3_600) / 60
    val seconds = totalSec % 60

    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
