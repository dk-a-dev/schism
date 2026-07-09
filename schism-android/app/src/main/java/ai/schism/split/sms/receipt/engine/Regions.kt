package ai.schism.split.sms.receipt.engine

/** Generic keyword classes for a line-item table's column header (Item/Qty/Rate/Amount and synonyms). */
private val HEADER_KEYWORDS = Regex("item|description|particular|qty|quantity|rate|price|mrp|amount|amt", RegexOption.IGNORE_CASE)

/** Generic keyword classes for a totals/fee label row (Sub Total, Grand Total, Amount, Paid, Qty: ...). */
private val TOTALS_LABEL = Regex("sub ?total|total|grand total|amount|bill amount|paid|qty\\s*:", RegexOption.IGNORE_CASE)

data class Regions(val header: List<Row>, val items: List<Row>, val totals: List<Row>)

/**
 * True when [row] looks like a line-item table's column header: it has more than one cell, and at
 * least half of its cells are header-keyword labels rather than data values. Requiring 2+ cells
 * keeps single-cell label rows (e.g. a totals row like "Qty: 5") from being mistaken for a header.
 */
private fun isColumnHeaderRow(row: Row): Boolean {
    if (row.cells.size < 2) return false
    val keywordCells = row.cells.count { HEADER_KEYWORDS.containsMatchIn(it.text) }
    return keywordCells * 2 >= row.cells.size
}

/** True when [row]'s joined text carries a totals/fee label keyword. */
private fun isTotalsLabelRow(row: Row): Boolean = TOTALS_LABEL.containsMatchIn(row.text)

/**
 * Splits [rows] into a leading `header` region, a line-`items` region, and a trailing `totals`
 * region (subtotal/tax/fee/grand-total lines and anything after).
 *
 * The totals region starts at the first row — excluding any column-header row — whose text carries
 * a totals-label keyword; everything from there to the end is `totals`. Within the rows before that
 * cut, the items region starts right after a detected column-header row, or — if no header row is
 * found — at the first row bearing a money-shaped token ([isMoneyToken]); everything before that is
 * `header`.
 */
fun segment(rows: List<Row>): Regions {
    val totalsStart = rows.indexOfFirst { !isColumnHeaderRow(it) && isTotalsLabelRow(it) }
        .let { if (it < 0) rows.size else it }

    val rowsBeforeTotals = rows.subList(0, totalsStart)
    val headerRowIdx = rowsBeforeTotals.indexOfFirst { isColumnHeaderRow(it) }
    val moneyRowIdx = rowsBeforeTotals.indexOfFirst { row -> row.cells.any { isMoneyToken(it.text) } }

    val itemsStart = when {
        headerRowIdx >= 0 -> headerRowIdx + 1
        moneyRowIdx >= 0 -> moneyRowIdx
        else -> totalsStart
    }.coerceIn(0, totalsStart)

    return Regions(
        header = rows.subList(0, itemsStart),
        items = rows.subList(itemsStart, totalsStart),
        totals = rows.subList(totalsStart, rows.size),
    )
}
