package ai.schism.split.sms.receipt.engine

/**
 * Generic keyword classes for a line-item table's column header (Item/Qty/Rate/Amount and synonyms).
 * Word-anchored: without the `\b`s an ordinary count line — "10 items (15 Qty)" — matched on "item"
 * inside "items", and the whole preamble above it was then cut into the item list as the first
 * item's wrapped name. Deliberately does NOT include "total" (which the column-ROLE keywords do), so
 * that a "Total Qty: 13 | Sub Total | 1249.00" totals line can never read as a table header.
 */
private val HEADER_KEYWORDS =
    Regex("""\b(item|description|particular|qty|quantity|rate|price|mrp|amount|amt)\b""", RegexOption.IGNORE_CASE)

/** Generic keyword classes for a totals/fee label row (Sub Total, Grand Total, Amount, Paid, Qty: ...). */
private val TOTALS_LABEL = Regex("sub ?total|total|grand total|amount|bill amount|paid|qty\\s*:", RegexOption.IGNORE_CASE)

/** A date-shaped token (e.g. "9/7/26", "09-07-2026"): digit groups separated by '/' or '-' — never a bill amount. */
private val DATE_LIKE = Regex("\\d{1,4}\\s*[/-]\\s*\\d{1,4}\\s*[/-]\\s*\\d{1,4}")

data class Regions(val header: List<Row>, val items: List<Row>, val totals: List<Row>)

/**
 * True when [row] looks like a line-item table's column header: it has more than one cell, and a
 * STRICT majority of its cells are header-keyword labels rather than data values. Requiring 2+ cells
 * keeps single-cell label rows (e.g. a totals row like "Qty: 5") from being mistaken for a header;
 * requiring more than half — not merely half — keeps a two-cell row with one keyword and one value
 * ("10 items (15 Qty) | ...") out. A genuine header row is all labels, so the stricter bar costs it
 * nothing even when OCR garbles one of them.
 */
private fun isColumnHeaderRow(row: Row): Boolean {
    if (row.cells.size < 2) return false
    return row.cells.count { HEADER_KEYWORDS.containsMatchIn(it.text) } * 2 > row.cells.size
}

/** True when [text] (trimmed) is numeric-shaped — a money amount or a plausible small quantity — rather than a name/label. */
private fun isNumericCellText(text: String): Boolean {
    val t = text.trim()
    return t.isEmpty() || isMoneyToken(t) || isSmallInt(t)
}

/** The cells in [row] that aren't numeric-shaped — its item/label cells, left to right. */
private fun labelCells(row: Row): List<Cell> = row.cells.sortedBy { it.xLeft }.filter { !isNumericCellText(it.text) }

/**
 * True when [row] is a genuine totals/fee *label* row rather than a priced item row whose name
 * happens to contain a totals keyword (e.g. "Total Wrap ... 150.00").
 *
 * Real totals rows are almost always short — a bare label, optionally with one or two adjoining
 * amounts (e.g. "Sub Total | 1249.00", or a single "Total Qty: 13" cell) — so a row with at most 2
 * cells is checked against the keyword regex directly. A longer, multi-cell row (the shape of an
 * item line: name + qty + rate + amount) is only treated as a totals boundary when the totals
 * keyword occupies its item/label cell *entirely*, not merely appears inside a longer item name —
 * so "Total Wrap | 1 | 150.00 | 150.00" stays an item row while "Grand Total | 18% | 1381.00" is
 * still recognised as totals.
 *
 * EVERY label cell is checked, not just the leftmost: a totals line routinely carries two labels
 * ("Total Qty: 13 | Sub Total | 1249.00"), and requiring the *first* of them to be a whole totals
 * label would push the row — and the whole totals block after it — into the item list. An item row
 * has exactly one label cell (its name), so this can't turn a dish into a totals boundary.
 */
private fun isTotalsLabelRow(row: Row): Boolean {
    if (row.cells.size <= 2) return TOTALS_LABEL.containsMatchIn(row.text)
    return labelCells(row).any { TOTALS_LABEL.matches(it.text.trim().trimEnd(':').trim()) }
}

/** True when [cell]'s text is a money amount written with a decimal fraction (e.g. "159.00") — the clearest sign of a priced item line. */
private fun isDecimalMoneyCell(cell: Cell): Boolean {
    val t = cell.text.trim()
    return t.contains('.') && isMoneyToken(t)
}

/**
 * True when [cell]'s text is a bill amount plausible enough to anchor an item row: money-shaped,
 * not a date-like token (dates are never bill amounts), and not a bare small quantity
 * ([isSmallInt]) — more likely a covers/table/qty number than a price.
 */
private fun isPlausibleAmountCell(cell: Cell): Boolean {
    val t = cell.text.trim()
    if (DATE_LIKE.containsMatchIn(t)) return false
    return isMoneyToken(t) && !isSmallInt(t)
}

/**
 * True when [row] looks like a priced line-item row, for the headerless first-item fallback: it
 * has at least 2 cells, its RIGHTMOST cell is money-shaped, and it carries either a decimal-bearing
 * money token (the normal case) or a plausible (non-date, non-small-int) amount alongside a
 * name/label cell. This keeps a preamble date row ("9/7/26" → cleaned "9726") or a table/covers
 * number ("Table: 4") from being mistaken for the first item row.
 *
 * The rightmost-cell requirement is what separates a priced row from a preamble row that merely
 * contains a long number: a line amount is the last thing printed on an item line, always, whereas
 * "Check #: | 622967 | Pax(s): 04" carries its reference number mid-row. Without it the items
 * region opened several lines early and the whole preamble was folded into the first item's name.
 */
private fun looksLikeItemRow(row: Row): Boolean {
    if (row.cells.size < 2) return false
    if (row.cells.maxByOrNull { it.xLeft }?.let { isMoneyToken(it.text) } != true) return false
    if (row.cells.any { isDecimalMoneyCell(it) }) return true
    val hasAmount = row.cells.any { isPlausibleAmountCell(it) }
    val hasNameCell = row.cells.any { !isNumericCellText(it.text) }
    return hasAmount && hasNameCell
}

/**
 * True when [row] is a wrapped item-name fragment: one letters-bearing cell and nothing priced —
 * the top half of an item name OCR split across two lines. A metadata line (date, tax id, phone,
 * boilerplate) is excluded, so a bill's preamble is never mistaken for one.
 */
private fun isWrappedNameFragment(row: Row): Boolean {
    val text = row.text.trim()
    return row.cells.size == 1 && text.count { it.isLetter() } >= 2 && !isMetadataRow(text)
}

/**
 * Where the items region starts given [moneyRowIdx], the first priced row: at that row, extended
 * upward over the wrapped-name fragments belonging to its item, so a headerless bill whose first
 * item's name wrapped ABOVE its price keeps that name instead of cutting it into the header region.
 * Row 0 is never absorbed — the top line of a bill is its merchant name, which reads as a fragment
 * too but is not one.
 */
private fun itemsStartFrom(rows: List<Row>, moneyRowIdx: Int): Int {
    var i = moneyRowIdx
    while (i > 1 && isWrappedNameFragment(rows[i - 1])) i--
    return i
}

/**
 * Splits [rows] into a leading `header` region, a line-`items` region, and a trailing `totals`
 * region (subtotal/tax/fee/grand-total lines and anything after).
 *
 * The totals region starts at the first row — excluding any column-header row — that is a genuine
 * totals *label* row ([isTotalsLabelRow]); everything from there to the end is `totals`. Within the
 * rows before that cut, the items region starts right after a detected column-header row, or — if
 * no header row is found — at the first row that looks like a priced item line
 * ([looksLikeItemRow]), backed up over that item's wrapped-name fragments ([itemsStartFrom]);
 * everything before that is `header`.
 */
fun segment(rows: List<Row>): Regions {
    val totalsStart = rows.indexOfFirst { !isColumnHeaderRow(it) && isTotalsLabelRow(it) }
        .let { if (it < 0) rows.size else it }

    val rowsBeforeTotals = rows.subList(0, totalsStart)
    val headerRowIdx = rowsBeforeTotals.indexOfFirst { isColumnHeaderRow(it) }
    val moneyRowIdx = rowsBeforeTotals.indexOfFirst { looksLikeItemRow(it) }

    val itemsStart = when {
        headerRowIdx >= 0 -> headerRowIdx + 1
        moneyRowIdx >= 0 -> itemsStartFrom(rowsBeforeTotals, moneyRowIdx)
        else -> totalsStart
    }.coerceIn(0, totalsStart)

    return Regions(
        header = rows.subList(0, itemsStart),
        items = rows.subList(itemsStart, totalsStart),
        totals = rows.subList(totalsStart, rows.size),
    )
}
