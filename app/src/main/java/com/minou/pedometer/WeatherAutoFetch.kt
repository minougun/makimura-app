package com.minou.pedometer

import androidx.annotation.WorkerThread
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.json.JSONObject
import kotlin.math.roundToInt

data class WeatherFetchResult(
    val cityLabel: String,
    val weatherContext: WeatherContext,
)

object WeatherAutoFetch {
    @WorkerThread
    fun fetchByCity(cityQuery: String): Result<WeatherFetchResult> {
        return runCatching {
            val normalizedQuery = cityQuery.trim()
            require(normalizedQuery.isNotEmpty()) { "都市名を入力してください。" }

            val geo = fetchGeocoding(normalizedQuery)
            val weatherContext = fetchCurrentWeather(geo.latitude, geo.longitude)
            val cityLabel = listOfNotNull(geo.name, geo.country).joinToString(", ")
                .ifBlank { normalizedQuery }

            WeatherFetchResult(
                cityLabel = cityLabel,
                weatherContext = weatherContext,
            )
        }
    }

    @WorkerThread
    fun fetchForMakimuraShop(): Result<WeatherFetchResult> {
        return fetchByCity(MakimuraShop.WEATHER_QUERY).map { result ->
            result.copy(cityLabel = MakimuraShop.ADDRESS_LABEL)
        }
    }

    private fun fetchGeocoding(query: String): GeocodingResult {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        val endpoint = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=ja&format=json"
        val response = get(endpoint)
        val root = JSONObject(response.body)
        val results = root.optJSONArray("results")
            ?: error("地名が見つかりませんでした。")
        if (results.length() == 0) {
            error("地名が見つかりませんでした。")
        }

        val first = results.getJSONObject(0)
        return GeocodingResult(
            latitude = first.getDouble("latitude"),
            longitude = first.getDouble("longitude"),
            name = first.optString("name").ifBlank { null },
            country = first.optString("country").ifBlank { null },
        )
    }

    private fun fetchCurrentWeather(latitude: Double, longitude: Double): WeatherContext {
        val lat = String.format(Locale.US, "%.6f", latitude)
        val lon = String.format(Locale.US, "%.6f", longitude)
        val endpoint = buildString {
            append("https://api.open-meteo.com/v1/forecast?")
            append("latitude=").append(lat)
            append("&longitude=").append(lon)
            append("&current=temperature_2m,weather_code")
            append("&timezone=auto")
        }
        val response = get(endpoint)
        val root = JSONObject(response.body)
        val current = root.optJSONObject("current")
            ?: error("天気データを取得できませんでした。")

        val tempC = current.optDouble("temperature_2m", Double.NaN)
        val weatherCode = current.optInt("weather_code", Int.MIN_VALUE)
        if (tempC.isNaN() || weatherCode == Int.MIN_VALUE) {
            error("天気データ形式が不正です。")
        }

        return WeatherContext(
            condition = weatherConditionFromCode(weatherCode),
            temperatureC = tempC.roundToInt(),
        )
    }

    internal fun weatherConditionFromCode(code: Int): WeatherCondition {
        return when (code) {
            0, 1 -> WeatherCondition.SUNNY
            2, 3, 45, 48 -> WeatherCondition.CLOUDY
            51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99 -> WeatherCondition.RAINY
            71, 73, 75, 77, 85, 86 -> WeatherCondition.SNOWY
            else -> WeatherCondition.CLOUDY
        }
    }

    private fun get(url: String): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }

        return connection.useAndRead()
    }
}

private data class GeocodingResult(
    val latitude: Double,
    val longitude: Double,
    val name: String?,
    val country: String?,
)

private data class HttpResponse(
    val code: Int,
    val body: String,
)

private fun HttpURLConnection.useAndRead(): HttpResponse {
    return try {
        val code = responseCode
        val stream = if (code in 200..299) inputStream else errorStream
        val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val compactBody = body.replace('\n', ' ').trim().take(300)
            error("天気APIエラー($code): $compactBody")
        }
        HttpResponse(code = code, body = body)
    } finally {
        disconnect()
    }
}
