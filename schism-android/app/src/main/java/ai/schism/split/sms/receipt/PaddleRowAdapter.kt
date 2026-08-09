package ai.schism.split.sms.receipt

import ai.schism.split.ocr.api.OcrLine
import ai.schism.split.sms.receipt.engine.Cell
import ai.schism.split.sms.receipt.engine.Row
import ai.schism.split.sms.receipt.engine.groupIntoRows

/** Converts neutral quadrilateral OCR detections into the receipt parser's visual rows. */
internal fun ocrLinesToRows(lines: List<OcrLine>): List<Row> {
    val valid = lines.mapNotNull { line ->
        val text = line.text.trim()
        if (text.isEmpty()) return@mapNotNull null
        if (line.points.size != 4 || line.points.any { !it.x.isFinite() || !it.y.isFinite() }) {
            return@mapNotNull null
        }
        LineBounds(
            text = text,
            left = line.points.minOf { it.x }.toInt(),
            right = line.points.maxOf { it.x }.toInt(),
            top = line.points.minOf { it.y }.toInt(),
            bottom = line.points.maxOf { it.y }.toInt(),
        )
    }
    if (valid.isEmpty()) return emptyList()

    val heights = valid.map { (it.bottom - it.top).coerceAtLeast(1) }.sorted()
    val medianHeight = heights[heights.size / 2]
    val cells = valid.map {
        Cell(
            text = it.text,
            xLeft = it.left,
            xRight = it.right,
            yCenter = (it.top + it.bottom) / 2,
        )
    }
    return groupIntoRows(cells, medianHeight)
}

private data class LineBounds(
    val text: String,
    val left: Int,
    val right: Int,
    val top: Int,
    val bottom: Int,
)
