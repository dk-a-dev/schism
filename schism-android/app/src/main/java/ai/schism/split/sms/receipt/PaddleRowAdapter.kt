package ai.schism.split.sms.receipt

import ai.schism.split.sms.receipt.engine.Cell
import ai.schism.split.sms.receipt.engine.Row
import ai.schism.split.sms.receipt.engine.groupIntoRows

/** Geometry retained from one PaddleOCR text detection after recognition. */
internal data class DetectedLine(
    val text: String,
    val left: Int,
    val right: Int,
    val top: Int,
    val bottom: Int,
)

/** Converts Paddle's quadrilateral text detections into the receipt parser's visual rows. */
internal fun detectedLinesToRows(detections: List<DetectedLine>): List<Row> {
    val valid = detections.mapNotNull { detection ->
        val text = detection.text.trim()
        if (text.isEmpty()) return@mapNotNull null

        val left = minOf(detection.left, detection.right)
        val right = maxOf(detection.left, detection.right)
        val top = minOf(detection.top, detection.bottom)
        val bottom = maxOf(detection.top, detection.bottom)
        detection.copy(text = text, left = left, right = right, top = top, bottom = bottom)
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
