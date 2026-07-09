package ai.schism.split.sms.receipt.engine

import ai.schism.split.sms.receipt.ReceiptDraft
import ai.schism.split.sms.receipt.ReceiptLineItem
import ai.schism.split.sms.receipt.isoDate
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

/**
 * Parses a QTY cell's raw text as a plain positive integer count (never minor units) — but only
 * when it's a plausible quantity ([isSmallInt]: a bare 1-999 integer). Bounding this to small ints
 * keeps a decimal-less large number (e.g. a total that's missing its decimal point) from being
 * accepted as a "qty" candidate — both here, for the row's naive per-column reading, and in
 * [resolveQtyAndAmount]'s remap search, which calls this same function per permutation slot.
 */
private fun parseQty(raw: String): Int? = raw.trim().takeIf { isSmallInt(it) }?.toIntOrNull()

/** Trailing unit-of-count words a qty cell may carry ("3 Nos", "2 pcs"). */
private val QTY_UNIT_SUFFIXES = listOf("nos", "no", "pcs", "pc", "qty")

/**
 * Parses the text of a cell KNOWN to sit in the QTY column into an integer count. Because the column
 * role already vouches that this cell is a quantity (not a rate/amount), it can be normalized more
 * aggressively than the strict [parseQty] used by the arithmetic remap: a leading `x`/`×` multiplier
 * (`x3`, `×3`), a trailing unit word (`3 Nos`, `2 pcs`), and a grocery all-zero fraction (`3.000` → 3)
 * are all stripped. A genuinely fractional weight (`1.5`) returns null — an Int count can't represent
 * it, so the caller falls back to qty 1 and reads the line amount directly.
 *
 * This normalization is deliberately NOT folded into [parseQty]: the remap search trial-fits every
 * cell into the qty slot, and there a money rate like `40.00` must stay a non-qty (returning 40 would
 * let a rate masquerade as a quantity).
 */
private fun parseQtyCell(raw: String): Int? {
    var s = raw.trim().lowercase()
    s = s.removePrefix("x").removePrefix("×").trim()
    QTY_UNIT_SUFFIXES.firstOrNull { s.endsWith(it) }?.let { s = s.dropLast(it.length).trim() }
    val dot = s.indexOf('.')
    if (dot >= 0) {
        val frac = s.substring(dot + 1)
        s = if (frac.isNotEmpty() && frac.all { it == '0' }) s.substring(0, dot) else return null
    }
    return parseQty(s)
}

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
    val qtyFromCell = qtyRaw?.let { parseQtyCell(it) }
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
        // Known residual limitation: this invariant is arithmetic-only, so when the true rate and
        // qty are both small ints whose product is commutative (e.g. rate=2,qty=3,amount=6 vs.
        // rate=3,qty=2,amount=6) every valid-looking permutation reconciles and "smallest integer =
        // qty" is just a tiebreak guess — it can pick the swapped (wrong) reading. Accepted as an
        // inherent limit of disambiguating by arithmetic alone, with no row-specific special-casing.
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
private val PURE_SERIAL = Regex("""^\d{1,3}[.)]?$""")

/**
 * The item name for a row is every cell EXCEPT the qty cell and the money cells sitting in the
 * rate/amount band — joined left-to-right — NOT a single cell picked from the ITEM column. Wide item
 * names don't share a center with the narrow "Item"/"Description" header, so x-center clustering can
 * split the name region into several columns; keeping "everything that isn't a qty/rate/amount cell"
 * is robust to that AND to a qty-first layout (`Qty | Item | Rate | Amount`), where a purely
 * positional "left of the numbers" rule would wrongly drop the whole name (it sits right of the
 * leftmost, qty, column).
 *
 * A leading serial-number cell ("1", "12.") is dropped — but ONLY when it is its OWN standalone cell
 * with real name cells following it, never by regex-stripping a leading digit off a joined string
 * (that would eat the "7" of a name like "7 Up" or "500ml Water" whose first token is a digit).
 */
private fun nameOfRow(row: Row, qtyCell: Cell?, moneyLeftBound: Int): String {
    val kept = row.cells
        .filter { cell -> cell !== qtyCell && !(cell.xCenter >= moneyLeftBound && isMoneyToken(cell.text)) }
        .sortedBy { it.xLeft }
    val nameCells = if (kept.size > 1 && PURE_SERIAL.matches(kept.first().text.trim())) kept.drop(1) else kept
    return nameCells.joinToString(" ") { it.text.trim() }.trim()
}

/**
 * Resolves a single items-row's (qty, amountMinor, unitPriceMinor).
 *
 * Fix #2: when the row carries at least two money cells in the rate/amount band AND a known qty,
 * the (rate, amount) pair is chosen as the one satisfying `qty*rate=amount` ([invariantHolds])
 * rather than by trusting a column's leftmost cell — so the true line amount is recovered even when
 * the price and amount columns stayed merged (defect (d)) and `cellIn` would otherwise return the
 * price. The rightmost satisfying amount is preferred. Falls back to the column-role reading
 * ([resolveQtyAndAmount]) when no invariant-consistent pair exists (or qty is unknown).
 */
private fun resolveRow(
    row: Row,
    qtyCell: Cell?,
    rateCell: Cell?,
    amountCell: Cell?,
    moneyLeftBound: Int,
): Triple<Int, Long, Long>? {
    val qtyFromCell = qtyCell?.text?.let { parseQtyCell(it) }
    val moneyCells = row.cells
        .filter { it.xCenter >= moneyLeftBound && isMoneyToken(it.text) }
        .sortedBy { it.xCenter }

    if (qtyFromCell != null && moneyCells.size >= 2) {
        for (j in moneyCells.indices.reversed()) {
            val a = parseMinor(moneyCells[j].text) ?: continue
            for (i in j - 1 downTo 0) {
                val r = parseMinor(moneyCells[i].text) ?: continue
                if (invariantHolds(r, qtyFromCell, a)) return Triple(qtyFromCell, a, r)
            }
        }
    }

    val (qty, amountMinor) = resolveQtyAndAmount(qtyCell?.text, rateCell?.text, amountCell?.text) ?: return null
    val unit = if (qty > 0) amountMinor / qty else amountMinor
    return Triple(qty, amountMinor, unit)
}

/** An anchor row (bears the numeric triple) plus the wrap fragments attached above/below it. */
private class Anchor(
    val y: Int,
    val qty: Int,
    val amountMinor: Long,
    val unitPriceMinor: Long,
    val inlineName: String,
) {
    val prefix = mutableListOf<String>()
    val suffix = mutableListOf<String>()
    val fullName: String
        get() = (prefix + listOf(inlineName) + suffix).filter { it.isNotBlank() }.joinToString(" ").trim()
}

/** The vertical center of a visual row (its cells share a y-band). */
private fun Row.yCenter(): Int = if (cells.isEmpty()) 0 else cells.sumOf { it.yCenter } / cells.size

fun extractItems(regions: Regions, columns: List<Column>): List<ReceiptLineItem> {
    val qtyCol = columns.firstOrNull { it.role == ColRole.QTY }
    val rateCol = columns.firstOrNull { it.role == ColRole.RATE }
    val amountCol = columns.firstOrNull { it.role == ColRole.AMOUNT }
    // Money band = the rate/amount columns; a qty cell (which may itself be money-shaped) sits to
    // the left of it and is excluded, so only genuine rate/amount cells feed the invariant. The
    // item name is everything that isn't the qty cell or a money cell in this band.
    val moneyLeftBound = listOfNotNull(rateCol, amountCol).minOfOrNull { it.xLeft } ?: Int.MAX_VALUE

    // Pass 1: split rows into priced anchors and letters-only wrapped-name fragments. A priced row
    // that can't be resolved (e.g. an "N/A" in the amount column) is neither — it's dropped without
    // consuming any fragment, so a wrapped name still bridges it to the next real priced row.
    val anchors = mutableListOf<Anchor>()
    val fragments = mutableListOf<Pair<Int, String>>() // (yCenter, text)

    for (row in regions.items) {
        val qtyCell = qtyCol?.let { row.cellIn(it) }
        val rateCell = rateCol?.let { row.cellIn(it) }
        val amountCell = amountCol?.let { row.cellIn(it) }
        val nameText = nameOfRow(row, qtyCell, moneyLeftBound)
        val hasNumericCell = qtyCell != null || rateCell != null || amountCell != null

        if (!hasNumericCell) {
            if (looksLikeName(nameText)) fragments.add(row.yCenter() to nameText)
            continue
        }
        val (qty, amountMinor, unitPriceMinor) = resolveRow(row, qtyCell, rateCell, amountCell, moneyLeftBound)
            ?: continue
        // A discount/void/zero row resolves to a non-positive amount — never a purchased item.
        if (amountMinor <= 0) continue
        anchors.add(Anchor(row.yCenter(), qty, amountMinor, unitPriceMinor, nameText))
    }

    // Pass 2 (Fix #4): attach each wrapped-name fragment to its vertically NEAREST anchor — so a
    // name that wrapped ABOVE its price (prefix) and one that wrapped BELOW it (the thermal layout,
    // suffix) are both grouped correctly. A tie (a fragment exactly between two anchors) resolves to
    // the FOLLOWING anchor, treating the fragment as the lead-in of the next item's name. Fragments
    // are visited top-to-bottom so multi-line names keep their reading order.
    for ((fy, text) in fragments) {
        val anchor = anchors.minWithOrNull(
            compareBy<Anchor> { abs(it.y - fy) }.thenByDescending { it.y },
        ) ?: continue
        if (fy <= anchor.y) anchor.prefix.add(text) else anchor.suffix.add(text)
    }

    // A resolved anchor with no plausible name (2+ letters) — even after folding in wrap fragments —
    // is bill noise, not a purchased item.
    return anchors.filter { looksLikeName(it.fullName) }
        .map { ReceiptLineItem(name = it.fullName, amountMinor = it.amountMinor, qty = it.qty, unitPriceMinor = it.unitPriceMinor) }
}

/**
 * Generic bill-metadata keyword classes (tax IDs, order/invoice references, contact/address
 * lines, boilerplate) — never a merchant name — used to skip non-merchant rows when hunting for
 * the merchant name line in [parseBill].
 */
private val METADATA_KEYWORDS = Regex(
    """(?i)\b(gstin|fssai|invoice|order\s*id|table|covers?|date|time|phone|mobile|tel|contact|""" +
        """bill\s*details|item\s*total|sub\s*total|grand\s*total|amount|paid|qty|thank|welcome|""" +
        """address|www|http|token|kot)\b""",
)

/** True when [text] is bill metadata (a date, or a generic metadata-keyword line) rather than a plausible merchant name. */
private fun isMetadataRow(text: String): Boolean = isoDate(text) != null || METADATA_KEYWORDS.containsMatchIn(text)

/**
 * Assembles the full deterministic bill-reading engine into a [ReceiptDraft]: per-source template
 * normalization ([applyTemplate]) runs first, then the generic geometry pipeline — column
 * detection ([detectColumns]), region segmentation ([segment]), item extraction ([extractItems]),
 * totals reading ([readTotals]) — and finally the arithmetic constraint solver ([reconcile]) ties
 * items and totals together into one internally-consistent draft.
 *
 * The merchant name is the first letters-dominant row that isn't bill metadata (a date, tax ID,
 * order reference, or other boilerplate keyword line); the date is the first [isoDate]-shaped row
 * text found anywhere on the bill.
 *
 * Returns `null` only when the bill yields neither a single line item nor a detected grand total —
 * i.e. there's nothing usable to build a draft from at all.
 */
fun parseBill(rows: List<Row>): ReceiptDraft? {
    if (rows.isEmpty()) return null

    val norm = applyTemplate(detectSource(rows), rows)

    val merchant = norm.firstNotNullOfOrNull { row ->
        val text = row.text.trim()
        // A row with a trailing money cell is a would-be line item (or a totals line), not a
        // merchant-name preamble line — a real merchant header never prices anything on itself.
        val hasTrailingMoneyCell = row.cells.lastOrNull()?.let { isMoneyToken(it.text) } == true
        text.takeIf {
            it.isNotEmpty() && !hasTrailingMoneyCell &&
                it.count { ch -> ch.isLetter() } >= 3 &&
                it.count { ch -> ch.isLetter() } > it.count { ch -> ch.isDigit() } &&
                !isMetadataRow(it)
        }
    }?.take(60) ?: "Receipt"

    val date = norm.firstNotNullOfOrNull { isoDate(it.text) }

    val columns = detectColumns(norm)
    val regions = segment(norm)
    val items = extractItems(regions, columns)
    val totals = readTotals(regions)

    if (items.isEmpty() && totals.grandTotal == null) return null

    val v = reconcile(items, totals)
    return ReceiptDraft(
        merchant = merchant,
        totalMinor = v.grandTotal,
        currency = "₹",
        date = date,
        lineItems = v.items,
        taxMinor = v.tax + v.fees - v.discount + v.roundoff,
        subtotalMinor = v.subtotal,
        feesMinor = v.fees,
        discountMinor = v.discount,
        verified = v.verified,
        parsedByAi = false,
    )
}
