package ai.schism.split.export

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.io.File
import java.time.ZoneId

// A4 at 72dpi, the size PdfDocument works in.
private const val PAGE_WIDTH = 595
private const val PAGE_HEIGHT = 842
private const val MARGIN = 40f
private const val LINE_HEIGHT = 18f
private const val HEADER_HEIGHT = 70f

/** How many rows fit on one page — the only part of PDF layout worth unit-testing. */
val ROWS_PER_PAGE: Int = ((PAGE_HEIGHT - HEADER_HEIGHT - MARGIN) / LINE_HEIGHT).toInt()

/** Splits [rows] into page-sized chunks. An empty export still produces one (empty) page. */
fun paginate(rows: List<ExportRow>, rowsPerPage: Int = ROWS_PER_PAGE): List<List<ExportRow>> =
    if (rows.isEmpty()) listOf(emptyList()) else rows.chunked(rowsPerPage)

/**
 * Renders [rows] as a plain, readable multi-page PDF in the app cache and returns a `content://`
 * URI. Same data as the CSV — nothing private, nothing uploaded.
 */
fun writePdfExport(
    context: Context,
    rows: List<ExportRow>,
    fileName: String = "schism-spending.pdf",
    zone: ZoneId = ZoneId.systemDefault(),
): Uri {
    val document = PdfDocument()
    val text = Paint().apply { textSize = 11f }
    val heading = Paint().apply { textSize = 16f; isFakeBoldText = true }
    val columnHeader = Paint().apply { textSize = 11f; isFakeBoldText = true }

    paginate(rows).forEachIndexed { index, pageRows ->
        val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create()
        val page = document.startPage(info)
        val canvas = page.canvas
        canvas.drawText("Schism — spending export", MARGIN, MARGIN, heading)
        canvas.drawText("Page ${index + 1}", PAGE_WIDTH - MARGIN - 60f, MARGIN, text)
        var y = MARGIN + 28f
        canvas.drawText("Date", MARGIN, y, columnHeader)
        canvas.drawText("Merchant", MARGIN + 90f, y, columnHeader)
        canvas.drawText("Amount", MARGIN + 340f, y, columnHeader)
        canvas.drawText("Currency", MARGIN + 440f, y, columnHeader)
        y += LINE_HEIGHT
        pageRows.forEach { row ->
            canvas.drawText(row.dateString(zone), MARGIN, y, text)
            canvas.drawText(row.merchant.take(40), MARGIN + 90f, y, text)
            canvas.drawText(amountString(row.amountMinor), MARGIN + 340f, y, text)
            canvas.drawText(row.currency, MARGIN + 440f, y, text)
            y += LINE_HEIGHT
        }
        document.finishPage(page)
    }

    val file = File(exportDir(context), fileName)
    file.outputStream().use { document.writeTo(it) }
    document.close()
    return file.toShareUri(context)
}
