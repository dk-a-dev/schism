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
private val LEADING_SERIAL = Regex("""^\d{1,3}[.)]?\s+""")

/**
 * The item name for a row is every cell whose center is LEFT of the numeric (qty/rate/amount)
 * columns, joined left-to-right — NOT a single cell picked from the ITEM column. Wide item names
 * don't share a center with the narrow "Item"/"Description" header, so x-center clustering can split
 * the name region into several columns; reading "everything left of the numbers" is robust to that.
 * A leading serial-number token ("1", "12.") is dropped.
 */
private fun nameLeftOfNumbers(row: Row, numericLeft: Int): String =
    row.cells.filter { it.xCenter < numericLeft }
        .sortedBy { it.xLeft }
        .joinToString(" ") { it.text.trim() }
        .trim()
        .replaceFirst(LEADING_SERIAL, "")

fun extractItems(regions: Regions, columns: List<Column>): List<ReceiptLineItem> {
    val qtyCol = columns.firstOrNull { it.role == ColRole.QTY }
    val rateCol = columns.firstOrNull { it.role == ColRole.RATE }
    val amountCol = columns.firstOrNull { it.role == ColRole.AMOUNT }
    val numericLeft = listOfNotNull(qtyCol, rateCol, amountCol).minOfOrNull { it.xLeft } ?: Int.MAX_VALUE

    val items = mutableListOf<ReceiptLineItem>()
    var wrappedNamePrefix: String? = null

    for (row in regions.items) {
        val qtyCell = qtyCol?.let { row.cellIn(it) }
        val rateCell = rateCol?.let { row.cellIn(it) }
        val amountCell = amountCol?.let { row.cellIn(it) }

        val nameText = nameLeftOfNumbers(row, numericLeft)
        val hasNumericCell = qtyCell != null || rateCell != null || amountCell != null

        if (!hasNumericCell) {
            // A letters-only row carrying no priced data at all is a wrapped item name's
            // continuation line, not an item of its own — fold it into the next row's name.
            if (looksLikeName(nameText)) {
                wrappedNamePrefix = listOfNotNull(wrappedNamePrefix, nameText).joinToString(" ")
            }
            continue
        }

        // Don't clear wrappedNamePrefix until this row is confirmed to actually consume it (a real
        // item gets emitted below) — a stray/garbled row between a wrapped name and its priced row
        // must not discard the pending name fragment before it can attach to the next real item.
        val fullName = listOfNotNull(wrappedNamePrefix, nameText.ifBlank { null }).joinToString(" ").trim()
        if (!looksLikeName(fullName)) continue
        val (qty, amountMinor) = resolveQtyAndAmount(qtyCell?.text, rateCell?.text, amountCell?.text) ?: continue
        // A discount/void/zero row (e.g. "Discount -50.00", or a stray "0.00") resolves to a
        // non-positive amount — never a real purchased item, so skip it before adding.
        if (amountMinor <= 0) continue

        wrappedNamePrefix = null
        items.add(ReceiptLineItem(name = fullName, amountMinor = amountMinor, qty = qty))
    }
    return items
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
