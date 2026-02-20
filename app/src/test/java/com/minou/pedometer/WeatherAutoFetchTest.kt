package com.minou.pedometer

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherAutoFetchTest {

    @Test
    fun weatherConditionFromCode_mapsKnownGroups() {
        assertEquals(WeatherCondition.SUNNY, WeatherAutoFetch.weatherConditionFromCode(0))
        assertEquals(WeatherCondition.SUNNY, WeatherAutoFetch.weatherConditionFromCode(1))
        assertEquals(WeatherCondition.CLOUDY, WeatherAutoFetch.weatherConditionFromCode(3))
        assertEquals(WeatherCondition.RAINY, WeatherAutoFetch.weatherConditionFromCode(61))
        assertEquals(WeatherCondition.SNOWY, WeatherAutoFetch.weatherConditionFromCode(73))
        assertEquals(WeatherCondition.RAINY, WeatherAutoFetch.weatherConditionFromCode(95))
    }

    @Test
    fun weatherConditionFromCode_unknownFallsBackToCloudy() {
        assertEquals(WeatherCondition.CLOUDY, WeatherAutoFetch.weatherConditionFromCode(-1))
    }
}
