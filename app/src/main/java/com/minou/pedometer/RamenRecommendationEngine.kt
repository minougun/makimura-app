package com.minou.pedometer

enum class RecommendationTier {
    LIGHT,
    BALANCE,
    REWARD,
}

data class RecommendedMenuItem(
    val name: String,
    val priceYen: Int,
)

data class RamenRecommendation(
    val tier: RecommendationTier,
    val items: List<RecommendedMenuItem>,
    val reason: String,
    val totalYen: Int,
)

object RamenRecommendationEngine {
    private val menuPrices = RamenMenuCatalog.priceTable

    fun recommend(
        metrics: TodayMetrics,
        weatherContext: WeatherContext,
        preferences: RecommendationPreferences = RecommendationPreferences(),
    ): RamenRecommendation {
        val tier = adjustedTier(
            baseTier = tierFromSteps(metrics.steps),
            appetiteLevel = preferences.appetiteLevel,
        )
        val selectedNames = baseItemsByTier(tier).toMutableList()
        val reasons = mutableListOf("歩数 ${metrics.steps}歩に合わせた${tierLabel(tier)}構成")
        val excludedToppings = preferences.excludedToppings.intersect(RamenMenuCatalog.toppingItemNames)

        val isColdOrWet = weatherContext.temperatureC <= 8 ||
            weatherContext.condition == WeatherCondition.RAINY ||
            weatherContext.condition == WeatherCondition.SNOWY
        val isHot = weatherContext.temperatureC >= 28

        if (isColdOrWet) {
            selectedNames.addIfAllowed("ニンニク", excludedToppings)
            if (tier != RecommendationTier.LIGHT) {
                selectedNames.addIfAllowed("キムチ", excludedToppings)
            }
            reasons += "低気温/雨雪なので温まり系を追加"
        } else if (isHot) {
            selectedNames.remove("キムチ")
            selectedNames.remove("ニンニク")
            selectedNames.replaceIfPresent("ミニチャーシュー丼", "明太子ご飯")
            selectedNames.addIfAllowed("ねぎ", excludedToppings)
            selectedNames.addIfAllowed("コーン", excludedToppings)
            if (tier != RecommendationTier.LIGHT) {
                selectedNames.addIfAllowed("煮卵", excludedToppings)
            }
            reasons += "高気温なのでさっぱり系を優先"
        } else {
            reasons += "気温は中間帯なので標準バランス"
        }

        if (tier == RecommendationTier.REWARD) {
            selectedNames.addIfAllowed("チャーシュー3枚", excludedToppings)
            reasons += "高運動量なのでチャーシュー3枚でタンパク質補給"
        } else if (tier == RecommendationTier.BALANCE && isColdOrWet) {
            selectedNames.addIfAllowed("チャーシュー2枚", excludedToppings)
            reasons += "寒い日はチャーシュー2枚で栄養補給"
        } else if (tier == RecommendationTier.BALANCE) {
            selectedNames.addIfAllowed("チャーシュー1枚", excludedToppings)
            reasons += "バランス運動量なのでチャーシュー1枚を追加"
        }

        when (preferences.moodPreference) {
            MoodPreference.RICH -> {
                selectedNames.addFirstAllowed(excludedToppings, "チャーシュー2枚", "チャーシュー1枚", "煮卵")
                selectedNames.addIfAllowed("ニンニク", excludedToppings)
                reasons += "気分設定でこってり寄りに調整"
            }

            MoodPreference.REFRESHING -> {
                selectedNames.remove("ニンニク")
                selectedNames.remove("キムチ")
                selectedNames.addIfAllowed("ねぎ", excludedToppings)
                selectedNames.addIfAllowed("コーン", excludedToppings)
                reasons += "気分設定でさっぱり寄りに調整"
            }

            MoodPreference.WARMING -> {
                selectedNames.addIfAllowed("ニンニク", excludedToppings)
                selectedNames.addIfAllowed("キムチ", excludedToppings)
                reasons += "気分設定で温まり系を優先"
            }

            MoodPreference.ANY -> Unit
        }

        applyToppingExclusions(selectedNames, tier, excludedToppings)

        val items = selectedNames.map { name ->
            RecommendedMenuItem(name = name, priceYen = menuPrices[name] ?: 0)
        }
        val totalYen = items.sumOf { it.priceYen }

        return RamenRecommendation(
            tier = tier,
            items = items,
            reason = reasons.joinToString(" / "),
            totalYen = totalYen,
        )
    }

    fun signature(
        metrics: TodayMetrics,
        weatherContext: WeatherContext,
        preferences: RecommendationPreferences,
        recommendation: RamenRecommendation,
    ): String {
        return listOf(
            tierLabel(recommendation.tier),
            weatherContext.condition.name,
            weatherContext.temperatureC.toString(),
            preferences.appetiteLevel.name,
            preferences.moodPreference.name,
            preferences.excludedToppings.sorted().joinToString(","),
            recommendation.items.joinToString(",") { it.name },
        ).joinToString("|")
    }

    fun homeSummary(
        metrics: TodayMetrics,
        weatherContext: WeatherContext,
        recommendation: RamenRecommendation,
    ): String {
        val prefix = if (toppingHighlights(recommendation).isEmpty()) {
            "今日は定番のラーメン構成です。 "
        } else {
            ""
        }
        return buildString {
            append(prefix)
            append("運動量 ${metrics.steps}歩・${summaryWeatherLabel(weatherContext.condition)} ${weatherContext.temperatureC}°Cの")
            append(tierLabel(recommendation.tier))
            append("提案です。")
        }
    }

    fun toppingHighlights(recommendation: RamenRecommendation): List<String> {
        return recommendation.items
            .map { item -> item.name }
            .filter { name -> name in RamenMenuCatalog.toppingItemNames }
    }

    private fun tierFromSteps(steps: Int): RecommendationTier {
        return when {
            steps < 4_000 -> RecommendationTier.LIGHT
            steps < 10_000 -> RecommendationTier.BALANCE
            else -> RecommendationTier.REWARD
        }
    }

    private fun adjustedTier(
        baseTier: RecommendationTier,
        appetiteLevel: AppetiteLevel,
    ): RecommendationTier {
        return when (appetiteLevel) {
            AppetiteLevel.NORMAL -> baseTier
            AppetiteLevel.LIGHT -> when (baseTier) {
                RecommendationTier.LIGHT -> RecommendationTier.LIGHT
                RecommendationTier.BALANCE -> RecommendationTier.LIGHT
                RecommendationTier.REWARD -> RecommendationTier.BALANCE
            }

            AppetiteLevel.HUNGRY -> when (baseTier) {
                RecommendationTier.LIGHT -> RecommendationTier.BALANCE
                RecommendationTier.BALANCE -> RecommendationTier.REWARD
                RecommendationTier.REWARD -> RecommendationTier.REWARD
            }
        }
    }

    private fun summaryWeatherLabel(condition: WeatherCondition): String {
        return when (condition) {
            WeatherCondition.SUNNY -> "晴れ"
            WeatherCondition.CLOUDY -> "くもり"
            WeatherCondition.RAINY -> "雨"
            WeatherCondition.SNOWY -> "雪"
        }
    }

    private fun baseItemsByTier(tier: RecommendationTier): List<String> {
        return when (tier) {
            RecommendationTier.LIGHT -> listOf("ラーメン", "ねぎ")
            RecommendationTier.BALANCE -> listOf("ラーメン", "煮卵", "メンマ")
            RecommendationTier.REWARD -> listOf("ラーメン", "替玉", "ミニチャーシュー丼")
        }
    }

    private fun applyToppingExclusions(
        selectedNames: MutableList<String>,
        tier: RecommendationTier,
        excludedToppings: Set<String>,
    ) {
        if (excludedToppings.isEmpty()) return

        selectedNames.removeAll(excludedToppings)
        val targetCount = when (tier) {
            RecommendationTier.LIGHT -> 1
            RecommendationTier.BALANCE -> 2
            RecommendationTier.REWARD -> 2
        }
        val currentToppingCount = selectedNames.count { it in RamenMenuCatalog.toppingItemNames }
        if (currentToppingCount >= targetCount) return

        fallbackToppingsForTier(tier).forEach { candidate ->
            if (selectedNames.count { it in RamenMenuCatalog.toppingItemNames } >= targetCount) return
            selectedNames.addIfAllowed(candidate, excludedToppings)
        }
    }

    private fun fallbackToppingsForTier(tier: RecommendationTier): List<String> {
        return when (tier) {
            RecommendationTier.LIGHT -> listOf("ねぎ", "コーン", "煮卵", "メンマ")
            RecommendationTier.BALANCE -> listOf("煮卵", "メンマ", "ねぎ", "コーン", "チャーシュー1枚")
            RecommendationTier.REWARD -> listOf("チャーシュー2枚", "煮卵", "メンマ", "ねぎ", "コーン")
        }
    }

    private fun tierLabel(tier: RecommendationTier): String {
        return when (tier) {
            RecommendationTier.LIGHT -> "ライト"
            RecommendationTier.BALANCE -> "バランス"
            RecommendationTier.REWARD -> "ご褒美"
        }
    }
}

private fun MutableList<String>.addIfMissing(name: String) {
    if (!contains(name)) add(name)
}

private fun MutableList<String>.addIfAllowed(name: String, excludedToppings: Set<String>) {
    if (name in excludedToppings) return
    addIfMissing(name)
}

private fun MutableList<String>.addFirstAllowed(
    excludedToppings: Set<String>,
    vararg candidates: String,
) {
    candidates.firstOrNull { candidate -> candidate !in excludedToppings }?.let(::addIfMissing)
}

private fun MutableList<String>.replaceIfPresent(oldValue: String, newValue: String) {
    val index = indexOf(oldValue)
    if (index < 0) return
    set(index, newValue)
}
