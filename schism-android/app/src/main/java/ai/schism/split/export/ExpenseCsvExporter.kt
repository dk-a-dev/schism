package ai.schism.split.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One exported line. Deliberately only the fields the user already sees on screen — no ids, no
 * tokens, no raw SMS, no debug fields — because an export leaves the app the moment it's shared.
 */
data class ExportRow(
    val timestamp: Long,
    val merchant: String,
    val amountMinor: Long,
    val currency: String,
)

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

internal fun ExportRow.dateString(zone: ZoneId): String =
    Instant.ofEpochMilli(timestamp).atZone(zone).format(DATE_FORMAT)

/** Money as a plain decimal so spreadsheets read it as a number, not as text with a symbol. */
internal fun amountString(amountMinor: Long): String {
    val sign = if (amountMinor < 0) "-" else ""
    val abs = kotlin.math.abs(amountMinor)
    return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
}

/**
 * RFC 4180 CSV. A field is quoted when it contains a comma, a quote, or a newline, and embedded
 * quotes are doubled — so a merchant called `Bob"s, Diner` survives a round trip intact.
 */
fun toCsv(rows: List<ExportRow>, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
    append("Date,Merchant,Amount,Currency\r\n")
    rows.forEach { row ->
        append(csvField(row.dateString(zone))).append(',')
        append(csvField(row.merchant)).append(',')
        append(csvField(amountString(row.amountMinor))).append(',')
        append(csvField(row.currency)).append("\r\n")
    }
}

private fun csvField(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else {
        value
    }

/**
 * Writes [rows] as UTF-8 CSV into the app cache and returns a `content://` URI for it. Nothing is
 * uploaded and nothing is stored on the backend — the file exists only until [clearExports] runs or
 * the OS reclaims the cache.
 */
fun writeCsvExport(context: Context, rows: List<ExportRow>, fileName: String = "schism-spending.csv"): Uri {
    val file = File(exportDir(context), fileName)
    file.writeText(toCsv(rows), Charsets.UTF_8)
    return file.toShareUri(context)
}

internal fun exportDir(context: Context): File =
    File(context.cacheDir, "exports").apply { mkdirs() }

internal fun File.toShareUri(context: Context): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.exports", this)

/**
 * A user-initiated share. `createChooser` is deliberate: the user picks where the file goes, and the
 * read grant is scoped to that one URI for that one launch.
 */
fun shareExportIntent(uri: Uri, mimeType: String, title: String): Intent =
    Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
        title,
    )

/** Drops previously written exports so shared temporary files don't linger in the cache. */
fun clearExports(context: Context) {
    exportDir(context).listFiles()?.forEach { it.delete() }
}
