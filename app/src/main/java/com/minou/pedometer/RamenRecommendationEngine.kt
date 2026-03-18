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

    fun recommend(metrics: TodayMetrics, weatherContext: WeatherContext): RamenRecommendation {
        val tier = tierFromSteps(metrics.steps)
        val selectedNames = baseItemsByTier(tier).toMutableList()
        val reasons = mutableListOf("歩数 ${metrics.steps}歩に合わせた${tierLabel(tier)}構成")

        val isColdOrWet = weatherContext.temperatureC <= 8 ||
            weatherContext.condition == WeatherCondition.RAINY ||
            weatherContext.condition == WeatherCondition.SNOWY
        val isHot = weatherContext.temperatureC >= 28

        if (isColdOrWet) {
            selectedNames.addIfMissing("ニンニク")
            if (tier != RecommendationTier.LIGHT) {
                selectedNames.addIfMissing("キムチ")
            }
            reasons += "低気温/雨雪なので温まり系を追加"
        } else if (isHot) {
            selectedNames.remove("キムチ")
            selectedNames.remove("ニンニク")
            selectedNames.replaceIfPresent("ミニチャーシュー丼", "明太子ご飯")
            selectedNames.addIfMissing("ねぎ")
            selectedNames.addIfMissing("コーン")
            if (tier != RecommendationTier.LIGHT) {
                selectedNames.addIfMissing("煮卵")
            }
            reasons += "高気温なのでさっぱり系を優先"
        } else {
            reasons += "気温は中間帯なので標準バランス"
        }

        if (tier == RecommendationTier.REWARD) {
            selectedNames.addIfMissing("チャーシュー3枚")
            reasons += "高運動量なのでチャーシュー3枚でタンパク質補給"
        } else if (tier == RecommendationTier.BALANCE && isColdOrWet) {
            selectedNames.addIfMissing("チャーシュー2枚")
            reasons += "寒い日はチャーシュー2枚で栄養補給"
        } else if (tier == RecommendationTier.BALANCE) {
            selectedNames.addIfMissing("チャーシュー1枚")
            reasons += "バランス運動量なのでチャーシュー1枚を追加"
        }

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

    fun homeSummary(
        metrics: TodayMetrics,
        weatherContext: WeatherContext,
        recommendation: RamenRecommendation,
    ): String {
        return buildString {
            append(highlight(recommendation))
            append(' ')
            append("運動量 ${metrics.steps}歩・${summaryWeatherLabel(weatherContext.condition)} ${weatherContext.temperatureC}°Cの")
            append(tierLabel(recommendation.tier))
            append("提案です。")
        }
    }

    fun highlight(recommendation: RamenRecommendation): String {
        val toppingNames = recommendation.items
            .map { item -> item.name }
            .filter { name -> name in RamenMenuCatalog.toppingItemNames }

        return when (toppingNames.size) {
            0 -> "今日は定番のラーメン構成です。"
            1 -> "今日は「${toppingNames[0]}」推しです。"
            2 -> "今日は「${toppingNames[0]}」と「${toppingNames[1]}」推しです。"
            else -> "今日は「${toppingNames[0]}」と「${toppingNames[1]}」を中心におすすめします。"
        }
    }

    private fun tierFromSteps(steps: Int): RecommendationTier {
        return when {
            steps < 4_000 -> RecommendationTier.LIGHT
            steps < 10_000 -> RecommendationTier.BALANCE
            else -> RecommendationTier.REWARD
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

private fun MutableList<String>.replaceIfPresent(oldValue: String, newValue: String) {
    val index = indexOf(oldValue)
    if (index < 0) return
    set(index, newValue)
}
