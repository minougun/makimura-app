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
        assertEquals(1_150, recommendation.totalYen)
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
        assertEquals(1_510, recommendation.totalYen)
    }
}
