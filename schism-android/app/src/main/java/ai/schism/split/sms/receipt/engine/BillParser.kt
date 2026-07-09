package ai.schism.split.sms.receipt.engine

import ai.schism.split.sms.receipt.ReceiptLineItem
import kotlin.math.abs
import kotlin.math.ceil

/** The 6 ways to assign 3 raw cell texts (by index) to the (qty, rate, amount) roles. */
private val QTY_RATE_AMOUNT_PERMUTATIONS: List<Triple<Int, Int, Int>> = listOf(
    Triple(0, 1, 2), Triple(0, 2, 1),
    Triple(1, 0, 2), Triple(1, 2, 0),
    Triple(2, 0, 1), Triple(2, 1, 0),
)

/** True when [text] has at least 2 letters — enough to be a plausible item name, not a stray symbol. */
private fun looksLikeName(text: String): Boolean = text.count { it.isLetter() } >= 2

/** Parses a QTY cell's raw text as a plain positive integer count (never minor units). */
private fun parseQty(raw: String): Int? = raw.trim().replace(",", "").toIntOrNull()?.takeIf { it > 0 }

/** Tolerance (minor units) for the rate*qty≈amount invariant: 1% of the amount, floored at 200. */
private fun tolerance(amountMinor: Long): Long = maxOf(ceil(amountMinor * 0.01).toLong(), 200L)

/** True when `rate*qty` lands within [tolerance] minor units of `amount`. */
private fun invariantHolds(rateMinor: Long, qty: Int, amountMinor: Long): Boolean =
    abs(rateMinor * qty - amountMinor) <= tolerance(amountMinor)

/**
 * Resolves a single items-row's (qty, amountMinor) pair from its raw QTY/RATE/AMOUNT cell text —
 * read by column role, never by position.
 *
 * When all three cells are present, the reading is verified against the `rate*qty≈amount`
 * invariant. A real receipt's arithmetic always holds; if it doesn't, the row's column/role
 * assignment for *this row* is untrustworthy (e.g. an OCR/column-detection slip put the amount
 * where the qty was expected). In that case the row's three raw texts are re-mapped across the
 * {qty, rate, amount} roles, trying every assignment and keeping the one whose arithmetic checks
 * out — preferring the smallest integer qty among the valid candidates, since a genuine quantity
 * is small while a swapped-in rate/amount is not. This is a per-row, arithmetic-only correction:
 * it never looks at what merchant, item name, or specific amount is involved.
 *
 * Returns null when no amount can be derived at all (no AMOUNT cell and no RATE to multiply).
 */
private fun resolveQtyAndAmount(qtyRaw: String?, rateRaw: String?, amountRaw: String?): Pair<Int, Long>? {
    val qtyFromCell = qtyRaw?.let { parseQty(it) }
    val rateMinor = rateRaw?.let { parseMinor(it) }
    val amountMinor = amountRaw?.let { parseMinor(it) }

    if (qtyRaw != null && rateRaw != null && amountRaw != null) {
        if (qtyFromCell != null && rateMinor != null && amountMinor != null &&
            invariantHolds(rateMinor, qtyFromCell, amountMinor)
        ) {
            return qtyFromCell to amountMinor
        }

        // The per-column reading is either arithmetically inconsistent or one of its cells doesn't
        // even parse in its expected shape (e.g. a decimal amount landed in the qty slot) — either
        // way this row's role/position assignment is untrustworthy. Re-map its three raw texts
        // across {qty, rate, amount}, trying every assignment, and keep the one whose arithmetic
        // checks out, preferring the smallest integer qty among the valid candidates.
        val texts = listOf(qtyRaw, rateRaw, amountRaw)
        val remapped = QTY_RATE_AMOUNT_PERMUTATIONS.mapNotNull { (qi, ri, ai) ->
            val q = parseQty(texts[qi]) ?: return@mapNotNull null
            val r = parseMinor(texts[ri]) ?: return@mapNotNull null
            val a = parseMinor(texts[ai]) ?: return@mapNotNull null
            if (invariantHolds(r, q, a)) q to a else null
        }.minByOrNull { it.first }
        if (remapped != null) return remapped

        // No arithmetic-consistent remap exists — fall back to whatever can be salvaged from the
        // AMOUNT-labelled cell, or RATE×QTY if even that doesn't parse as money.
        if (amountMinor != null) return (qtyFromCell ?: 1) to amountMinor
        if (rateMinor != null) {
            val qty = qtyFromCell ?: 1
            return qty to rateMinor * qty
        }
        return null
    }

    if (amountMinor != null) return (qtyFromCell ?: 1) to amountMinor
    if (rateMinor != null) {
        val qty = qtyFromCell ?: 1
        return qty to rateMinor * qty
    }
    return null
}

/**
 * Reads line items out of [regions]' `items` rows using [columns]' role assignments (ITEM / QTY /
 * RATE / AMOUNT) — never raw cell position or index — so a layout with columns in a different order
 * (e.g. Rate before Qty) is read correctly. Per row: `name` is the ITEM cell, folding in a preceding
 * letters-only wrapped-name row; `amount` is the AMOUNT cell, or RATE×QTY when there's no AMOUNT
 * column; `qty` is the QTY cell, defaulting to 1. See [resolveQtyAndAmount] for the arithmetic
 * disambiguation that self-corrects a mislabeled rate/qty/amount reading.
 *
 * Rows with no derivable name (2+ letters) or no derivable amount are skipped.
 */
fun extractItems(regions: Regions, columns: List<Column>): List<ReceiptLineItem> {
    val itemCol = columns.firstOrNull { it.role == ColRole.ITEM }
    val qtyCol = columns.firstOrNull { it.role == ColRole.QTY }
    val rateCol = columns.firstOrNull { it.role == ColRole.RATE }
    val amountCol = columns.firstOrNull { it.role == ColRole.AMOUNT }

    val items = mutableListOf<ReceiptLineItem>()
    var wrappedNamePrefix: String? = null

    for (row in regions.items) {
        val nameCell = itemCol?.let { row.cellIn(it) }
        val qtyCell = qtyCol?.let { row.cellIn(it) }
        val rateCell = rateCol?.let { row.cellIn(it) }
        val amountCell = amountCol?.let { row.cellIn(it) }

        val nameText = nameCell?.text?.trim().orEmpty()
        val hasNumericCell = qtyCell != null || rateCell != null || amountCell != null

        if (!hasNumericCell) {
            // A letters-only row carrying no priced data at all is a wrapped item name's
            // continuation line, not an item of its own — fold it into the next row's name.
            if (looksLikeName(nameText)) {
                wrappedNamePrefix = listOfNotNull(wrappedNamePrefix, nameText).joinToString(" ")
            }
            continue
        }

        val fullName = listOfNotNull(wrappedNamePrefix, nameText.ifBlank { null }).joinToString(" ").trim()
        wrappedNamePrefix = null
        if (!looksLikeName(fullName)) continue

        val (qty, amountMinor) = resolveQtyAndAmount(qtyCell?.text, rateCell?.text, amountCell?.text) ?: continue
        items.add(ReceiptLineItem(name = fullName, amountMinor = amountMinor, qty = qty))
    }
    return items
}
