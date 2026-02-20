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

    private fun tierFromSteps(steps: Int): RecommendationTier {
        return when {
            steps < 4_000 -> RecommendationTier.LIGHT
            steps < 10_000 -> RecommendationTier.BALANCE
            else -> RecommendationTier.REWARD
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
