package com.minou.pedometer

import android.content.Context
import android.net.Uri
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object HistorySafExporter {
    private val fileTimestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    fun suggestedFilename(now: LocalDateTime = LocalDateTime.now()): String {
        return "pedometer_history_${now.format(fileTimestamp)}.csv"
    }

    fun writeToUri(context: Context, uri: Uri, history: List<DailyHistory>): Result<Unit> {
        return runCatching {
            require(history.isNotEmpty()) { "履歴データがありません。" }

            val csv = HistoryAnalytics.toCsv(history)
            val output = context.contentResolver.openOutputStream(uri)
                ?: error("保存先に書き込みできませんでした。")

            output.use { stream ->
                stream.write(csv.toByteArray(Charsets.UTF_8))
                stream.flush()
            }
        }
    }
}
