package ai.schism.split.sms.receipt

import ai.schism.split.ocr.api.OcrLine
import ai.schism.split.ocr.api.OcrPoint
import ai.schism.split.sms.receipt.engine.Cell
import ai.schism.split.sms.receipt.engine.Row
import ai.schism.split.sms.receipt.engine.groupIntoRows
import kotlin.math.abs
import kotlin.math.hypot

/** Converts neutral quadrilateral OCR detections into the receipt parser's visual rows. */
internal fun ocrLinesToRows(lines: List<OcrLine>): List<Row> {
    val valid = lines.mapNotNull { line ->
        val text = line.text.trim()
        if (text.isEmpty()) return@mapNotNull null
        if (line.points.size != 4 || line.points.any { !it.x.isFinite() || !it.y.isFinite() }) {
            return@mapNotNull null
        }
        text to line.points
    }
    if (valid.isEmpty()) return emptyList()

    // A hand-held photo of a bill is never perfectly upright, and rows are grouped by y alone: on a
    // page tilted by a few degrees the amount at the right edge of a bill sits a whole line lower
    // than the item name at its left edge, so the visual rows shear and every name/qty/amount
    // pairing slips by one. Each detection quad carries its own text-line angle, so the page tilt is
    // the median of those; grouping on the tilt-corrected y (y − slope·x) removes the shear.
    val slope = skewSlope(valid.map { it.second })
    val deskewed = valid.map { (text, points) ->
        val ys = points.map { it.y - slope * it.x }
        Triple(text, points, ys.min() to ys.max())
    }

    val heights = deskewed.map { (_, _, span) -> (span.second - span.first).toInt().coerceAtLeast(1) }.sorted()
    val medianHeight = heights[heights.size / 2]
    val cells = deskewed.map { (text, points, span) ->
        Cell(
            text = text,
            xLeft = points.minOf { it.x }.toInt(),
            xRight = points.maxOf { it.x }.toInt(),
            yCenter = ((span.first + span.second) / 2f).toInt(),
        )
    }
    return groupIntoRows(cells, medianHeight)
}

/**
 * The photo's text-line tilt as a `dy/dx` slope: the median long-edge slope of the detection quads.
 *
 * Only elongated, more-horizontal-than-vertical boxes are measured — a one-or-two character box
 * (a bare qty "1") is too short for its corners to pin down an angle, and its near-square quad
 * would contribute noise. Zero when nothing qualifies, which leaves grouping exactly as it was.
 *
 * ponytail: one global tilt for the whole page. A creased or curled bill bends line by line; a
 * per-band fit would be the upgrade if that shows up in the wild.
 */
private fun skewSlope(quads: List<List<OcrPoint>>): Float {
    val slopes = quads.mapNotNull { points ->
        val edges = points.indices.map { i -> points[i] to points[(i + 1) % points.size] }
        val lengths = edges.map { (a, b) -> hypot(b.x - a.x, b.y - a.y) }
        val longIndex = lengths.indices.maxByOrNull { lengths[it] } ?: return@mapNotNull null
        val (a, b) = edges[longIndex]
        val dx = b.x - a.x
        val dy = b.y - a.y
        val shortest = lengths.min()
        if (lengths[longIndex] < 3f * shortest || abs(dx) <= abs(dy)) null else dy / dx
    }.sorted()
    return if (slopes.isEmpty()) 0f else slopes[slopes.size / 2]
}
