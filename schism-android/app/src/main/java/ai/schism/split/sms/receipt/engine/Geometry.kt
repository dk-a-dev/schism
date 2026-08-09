package ai.schism.split.sms.receipt.engine

data class Cell(val text: String, val xLeft: Int, val xRight: Int, val yCenter: Int) {
    val xCenter: Int get() = (xLeft + xRight) / 2
}

/**
 * A visual row of the bill. [qty] is the quantity a normalization read out of the row's own TEXT
 * ("5 x Hakka Noodles", "1 @ 699/ea", "Paneer Wrap x1") rather than off a page column — the two
 * live side by side because a quantity written inline shares the item name's x-span, so no amount of
 * column detection can separate it, while a quantity printed in its own column has no text to read.
 * [extractItems] prefers this when set.
 */
data class Row(val cells: List<Cell>, val qty: Int? = null) {
    /** Left-to-right joined text of this visual row. */
    val text: String get() = cells.sortedBy { it.xLeft }.joinToString(" ") { it.text.trim() }.trim()
}

/** Group cells into visual rows: same row when yCenters fall within 0.6*lineHeight; sort each row L→R, rows top→bottom. */
fun groupIntoRows(cells: List<Cell>, lineHeight: Int): List<Row> {
    if (cells.isEmpty()) return emptyList()
    val threshold = (lineHeight * 0.6).toInt().coerceAtLeast(1)
    val sorted = cells.sortedBy { it.yCenter }
    val rows = mutableListOf<MutableList<Cell>>()
    for (c in sorted) {
        val cur = rows.lastOrNull()
        val center = cur?.let { r -> r.sumOf { it.yCenter } / r.size }
        if (cur != null && center != null && kotlin.math.abs(c.yCenter - center) <= threshold) cur.add(c)
        else rows.add(mutableListOf(c))
    }
    return rows.map { Row(it.sortedBy { c -> c.xLeft }) }
}
