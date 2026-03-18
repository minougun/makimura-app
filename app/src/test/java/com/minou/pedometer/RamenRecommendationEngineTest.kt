package com.minou.pedometer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RamenRecommendationEngineTest {

    @Test
    fun recommend_lowStepsSunny_returnsLightSet() {
        val recommendation = RamenRecommendationEngine.recommend(
            metrics = TodayMetrics(steps = 1_500),
            weatherContext = WeatherContext(
                condition = WeatherCondition.SUNNY,
                temperatureC = 22,
            ),
        )

        assertEquals(RecommendationTier.LIGHT, recommendation.tier)
        assertEquals(listOf("ラーメン", "ねぎ"), recommendation.items.map { it.name })
        assertEquals(720, recommendation.totalYen)
    }

    @Test
    fun recommend_balancedRainy_addsWarmingToppings() {
        val recommendation = RamenRecommendationEngine.recommend(
            metrics = TodayMetrics(steps = 6_200),
            weatherContext = WeatherContext(
                condition = WeatherCondition.RAINY,
                temperatureC = 7,
            ),
        )

        assertEquals(RecommendationTier.BALANCE, recommendation.tier)
        assertTrue(recommendation.items.any { it.name == "ニンニク" })
        assertTrue(recommendation.items.any { it.name == "キムチ" })
        assertTrue(recommendation.items.any { it.name == "チャーシュー2枚" })
        assertEquals(1_390, recommendation.totalYen)
    }

    @Test
    fun recommend_rewardHot_swapsToRefreshingSet() {
        val recommendation = RamenRecommendationEngine.recommend(
            metrics = TodayMetrics(steps = 12_000),
            weatherContext = WeatherContext(
                condition = WeatherCondition.SUNNY,
                temperatureC = 31,
            ),
        )

        assertEquals(RecommendationTier.REWARD, recommendation.tier)
        assertTrue(recommendation.items.any { it.name == "明太子ご飯" })
        assertFalse(recommendation.items.any { it.name == "ミニチャーシュー丼" })
        assertTrue(recommendation.items.any { it.name == "コーン" })
        assertTrue(recommendation.items.any { it.name == "ねぎ" })
        assertTrue(recommendation.items.any { it.name == "チャーシュー3枚" })
        assertEquals(1_870, recommendation.totalYen)
    }

    @Test
    fun recommend_balancedSunny_addsSingleChashu() {
        val recommendation = RamenRecommendationEngine.recommend(
            metrics = TodayMetrics(steps = 8_100),
            weatherContext = WeatherContext(
                condition = WeatherCondition.SUNNY,
                temperatureC = 20,
            ),
        )

        assertEquals(RecommendationTier.BALANCE, recommendation.tier)
        assertTrue(recommendation.items.any { it.name == "チャーシュー1枚" })
        assertFalse(recommendation.items.any { it.name == "チャーシュー2枚" })
        assertFalse(recommendation.items.any { it.name == "チャーシュー3枚" })
        assertEquals(1_020, recommendation.totalYen)
        assertTrue(recommendation.reason.contains("バランス運動量なのでチャーシュー1枚を追加"))
    }

    @Test
    fun toppingHighlights_returns_recommended_toppings_only() {
        val recommendation = RamenRecommendationEngine.recommend(
            metrics = TodayMetrics(steps = 8_100),
            weatherContext = WeatherContext(
                condition = WeatherCondition.SUNNY,
                temperatureC = 20,
            ),
        )

        assertEquals(
            listOf("煮卵", "メンマ", "チャーシュー1枚"),
            RamenRecommendationEngine.toppingHighlights(recommendation),
        )
    }

    @Test
    fun homeSummary_combines_highlight_and_context() {
        val metrics = TodayMetrics(steps = 1_500)
        val weatherContext = WeatherContext(
            condition = WeatherCondition.SUNNY,
            temperatureC = 22,
        )
        val recommendation = RamenRecommendationEngine.recommend(
            metrics = metrics,
            weatherContext = weatherContext,
        )

        assertEquals(
            "運動量 1500歩・晴れ 22°Cのライト提案です。",
            RamenRecommendationEngine.homeSummary(metrics, weatherContext, recommendation),
        )
    }
}
