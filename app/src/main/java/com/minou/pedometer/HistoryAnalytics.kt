package com.minou.pedometer

import java.time.LocalDate

data class HistorySummary(
    val title: String,
    val steps: Int,
    val distanceMeters: Double,
    val caloriesKcal: Double,
    val movingDurationMs: Long,
)

object HistoryAnalytics {

    fun filterByLastDays(
        history: List<DailyHistory>,
        days: Int?,
        todayEpoch: Long = currentDayEpoch(),
    ): List<DailyHistory> {
        if (days == null) return history

        val clampedDays = days.coerceAtLeast(1)
        val minDay = todayEpoch - (clampedDays - 1)
        return history.filter { it.dayEpoch in minDay..todayEpoch }
    }

    fun weeklySummary(history: List<DailyHistory>, todayEpoch: Long = currentDayEpoch()): HistorySummary {
        return summarizeLastDays(history, days = 7, todayEpoch = todayEpoch, title = "直近7日")
    }

    fun monthlySummary(history: List<DailyHistory>, todayEpoch: Long = currentDayEpoch()): HistorySummary {
        return summarizeLastDays(history, days = 30, todayEpoch = todayEpoch, title = "直近30日")
    }

    fun summarizeLastDays(
        history: List<DailyHistory>,
        days: Int,
        todayEpoch: Long,
        title: String,
    ): HistorySummary {
        val filtered = filterByLastDays(history, days, todayEpoch)

        return HistorySummary(
            title = title,
            steps = filtered.sumOf { it.steps },
            distanceMeters = filtered.sumOf { it.totalDistanceMeters },
            caloriesKcal = filtered.sumOf { it.totalCaloriesKcal },
            movingDurationMs = filtered.sumOf { it.movingDurationMs },
        )
    }

    fun toCsv(history: List<DailyHistory>): String {
        val rows = history.sortedBy { it.dayEpoch }

        val builder = StringBuilder()
        builder.append("date,steps,total_distance_m,total_calories_kcal,moving_duration_s,brisk_distance_m,brisk_duration_s,running_distance_m,running_duration_s\n")

        rows.forEach { day ->
            val date = LocalDate.ofEpochDay(day.dayEpoch)
            builder.append(date)
                .append(',')
                .append(day.steps)
                .append(',')
                .append(formatDouble(day.totalDistanceMeters))
                .append(',')
                .append(formatDouble(day.totalCaloriesKcal))
                .append(',')
                .append(day.movingDurationMs / 1_000)
                .append(',')
                .append(formatDouble(day.briskDistanceMeters))
                .append(',')
                .append(day.briskDurationMs / 1_000)
                .append(',')
                .append(formatDouble(day.runningDistanceMeters))
                .append(',')
                .append(day.runningDurationMs / 1_000)
                .append('\n')
        }

        return builder.toString()
    }

    private fun formatDouble(value: Double): String {
        return "%.3f".format(java.util.Locale.US, value)
    }
}
