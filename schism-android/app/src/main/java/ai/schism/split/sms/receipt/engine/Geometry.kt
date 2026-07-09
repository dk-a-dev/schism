package ai.schism.split.sms.receipt.engine

data class Cell(val text: String, val xLeft: Int, val xRight: Int, val yCenter: Int) {
    val xCenter: Int get() = (xLeft + xRight) / 2
}

data class Row(val cells: List<Cell>) {
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
