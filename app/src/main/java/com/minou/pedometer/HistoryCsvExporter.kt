package com.minou.pedometer

import android.content.Intent
import android.content.Context
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object HistoryCsvExporter {
    private val fileTimestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    fun export(context: Context, history: List<DailyHistory>): Result<File> {
        return runCatching {
            require(history.isNotEmpty()) { "履歴データがありません。" }

            val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
            val exportDir = File(baseDir, "exports")
            if (!exportDir.exists() && !exportDir.mkdirs()) {
                error("エクスポート先フォルダを作成できませんでした。")
            }

            val timestamp = LocalDateTime.now().format(fileTimestamp)
            val targetFile = File(exportDir, "pedometer_history_${timestamp}.csv")
            targetFile.writeText(HistoryAnalytics.toCsv(history), Charsets.UTF_8)
            targetFile
        }
    }

    fun buildShareIntent(context: Context, file: File): Intent {
        val uri = toFileUri(context, file)

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Pedometer history CSV")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun buildViewIntent(context: Context, file: File): Intent {
        val uri = toFileUri(context, file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/csv")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun toFileUri(context: Context, file: File) = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
}
