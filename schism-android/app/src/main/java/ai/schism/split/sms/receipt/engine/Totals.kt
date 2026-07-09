package ai.schism.split.sms.receipt.engine

import kotlin.math.abs

/** Generic keyword classes for a totals-region row: label pattern → the financial role it plays. */
private val SUBTOTAL_RE = Regex("""sub\s*total""", RegexOption.IGNORE_CASE)
private val GRAND_TOTAL_RE = Regex(
    """grand\s*total|bill\s*amount|bill\s*total|amount\s*payable|\bpaid\b|rounded\s*total|\bnet\b""",
    RegexOption.IGNORE_CASE,
)
// A bare "total" is the most common grand-total label; it's only treated as the grand total AFTER
// SUBTOTAL_RE has had first refusal in the `when` below, so "Sub Total" never lands here.
private val BARE_TOTAL_RE = Regex("""\btotal\b""", RegexOption.IGNORE_CASE)
// Round-off/rounding-adjustment rows ("Round Off -0.40", "Round Amount", "Rounding") are a distinct
// class from a "Rounded Total" (which is a grand-total variant, matched by GRAND_TOTAL_RE above) —
// this is the small delta applied to reach that rounded figure, not the figure itself.
private val ROUND_RE = Regex("""round\s*off|round\s*amount|\brounding\b""", RegexOption.IGNORE_CASE)
private val DISCOUNT_RE = Regex("""discount|\boff\b|saved""", RegexOption.IGNORE_CASE)
private val FEES_RE = Regex("""packaging|platform|service|delivery|charge|\btip\b|gratuity""", RegexOption.IGNORE_CASE)
private val FREE_RE = Regex("""\bfree\b""", RegexOption.IGNORE_CASE)

private val CGST_RE = Regex("""\bcgst\b""", RegexOption.IGNORE_CASE)
private val SGST_RE = Regex("""\bsgst\b""", RegexOption.IGNORE_CASE)
private val IGST_RE = Regex("""\bigst\b""", RegexOption.IGNORE_CASE)
private val GST_RE = Regex("""\bgst\b""", RegexOption.IGNORE_CASE)
private val VAT_RE = Regex("""\bvat\b""", RegexOption.IGNORE_CASE)
private val TAX_RE = Regex("""\btax\b""", RegexOption.IGNORE_CASE)

/** A bare money-shaped token, used to pull an amount out of a row's joined text as a fallback. */
private val MONEY_TOKEN = Regex("""[₹$€£]?\s*-?\d[\d,]*(?:\.\d{1,2})?""")

/**
 * A generic-keyword breakdown of a bill's totals region, all amounts in Long minor units
 * (paise/cents). [discount] is always a positive magnitude (a bill printing "Discount -50.00"
 * still yields `discount = 50`) — [reconcile] subtracts it. [roundoff] is signed: a negative
 * value shaves the bill down, a positive one adds to it, exactly as printed.
 */
data class Totals(
    val subtotal: Long?,
    val tax: Long = 0,
    val fees: Long = 0,
    val discount: Long = 0,
    val grandTotal: Long? = null,
    val roundoff: Long = 0,
    /** The printed "Total Qty" count, when present — a cross-check on the number of item units read. */
    val totalQty: Int? = null,
)

/** A "Total Qty: N" / "Total Quantity N" line: a units count, never a money amount. */
private val TOTAL_QTY_RE = Regex("""\b(qty|quantity)\b""", RegexOption.IGNORE_CASE)
private val INT_TOKEN = Regex("""\d+""")

/** Which GST family a tax-labelled row belongs to — used to sum CGST+SGST unless a combined GST row wins. */
private enum class TaxBucket { CGST, SGST, IGST, GST, VAT, GENERIC }

/** True when [text], once currency symbols/commas/whitespace are stripped, is left with only a plain signed decimal — an amount, not a label (so "9%" and "CGST 9%" are rejected, "108.00" is accepted). */
private fun isPlainAmountText(text: String): Boolean {
    val stripped = text.trim().replace(Regex("""[₹$€£,\s]"""), "")
    return stripped.isNotEmpty() && stripped.matches(Regex("""-?\d+(\.\d{1,2})?"""))
}

/**
 * The row's amount: the rightmost of its cells that is itself plainly amount-shaped (never a label
 * cell that merely contains digits, e.g. "CGST 9%"), falling back to the last money-shaped token
 * found in the row's joined text when cells aren't cleanly split. A "FREE"/struck-through row (its
 * text contains the word "free") is always 0, regardless of any OCR'd amount.
 */
private fun rowAmount(row: Row): Long {
    if (FREE_RE.containsMatchIn(row.text)) return 0L
    val sorted = row.cells.sortedBy { it.xLeft }
    sorted.filter { isPlainAmountText(it.text) }.lastOrNull()?.let { cell ->
        parseMinor(cell.text)?.let { return it }
    }
    val fallback = MONEY_TOKEN.findAll(row.text).map { it.value }.lastOrNull()
    return fallback?.let { parseMinor(it) } ?: 0L
}

/** The row's label — its non-amount cells joined — used for keyword classification; falls back to the full row text if every cell looked like an amount. */
private fun rowLabel(row: Row): String {
    val sorted = row.cells.sortedBy { it.xLeft }
    val label = sorted.filterNot { isPlainAmountText(it.text) }.joinToString(" ") { it.text.trim() }
    return label.ifBlank { row.text }
}

/** Which tax family (if any) [label] names — most-specific keyword first, so "CGST" isn't mistaken for the combined "GST" bucket. */
private fun taxBucketOf(label: String): TaxBucket? = when {
    CGST_RE.containsMatchIn(label) -> TaxBucket.CGST
    SGST_RE.containsMatchIn(label) -> TaxBucket.SGST
    IGST_RE.containsMatchIn(label) -> TaxBucket.IGST
    GST_RE.containsMatchIn(label) -> TaxBucket.GST
    VAT_RE.containsMatchIn(label) -> TaxBucket.VAT
    TAX_RE.containsMatchIn(label) -> TaxBucket.GENERIC
    else -> null
}

/**
 * Classifies each row of [regions]' totals region by generic keyword class — subtotal / tax /
 * fees / discount / round-off / grand-total — and folds same-class rows into a single [Totals]
 * breakdown.
 *
 * Tax rows sum CGST+SGST(+IGST/VAT/generic-tax) unless a standalone combined "GST" row is present,
 * in which case it replaces the CGST/SGST split (IGST/VAT/generic-tax rows are distinct line items
 * and still add on top). Fee rows (packaging/platform/service/delivery/charge) are summed across
 * however many such rows appear. Discount rows (discount/off/saved) are summed as a positive
 * magnitude regardless of how the row's own sign was printed — "Discount -50.00" and "Discount
 * 50.00" both contribute 50, since a discount always reduces the bill. Round-off rows (round
 * off/round amount/rounding) are summed with their printed sign preserved, since a rounding
 * adjustment can go either way. The grand total is the largest amount among grand-total-labelled
 * rows, since a bill may show both a pre-round and a rounded figure; a grand-total-labelled row
 * (checked first) wins over any of the other classes a label might otherwise also match — e.g.
 * "Amount Payable after Discount" is the grand total, not a discount. A "FREE"/struck-through row
 * always contributes 0.
 *
 * This is keyword-and-arithmetic only: no merchant name, fixture value, or specific amount is ever
 * inspected, so the same classification applies to any bill shape.
 */
fun readTotals(regions: Regions): Totals {
    var subtotal: Long? = null
    var fees = 0L
    var discount = 0L
    var roundoff = 0L
    var grandTotal: Long? = null
    var totalQty: Int? = null
    val taxByBucket = mutableMapOf<TaxBucket, Long>()

    for (row in regions.totals) {
        val label = rowLabel(row)
        val amount = rowAmount(row)
        val bucket = taxBucketOf(label)

        when {
            // A "Total Qty: N" line is a units count, not money — captured first so its "Total"
            // keyword never leaks into the grand-total classification below.
            TOTAL_QTY_RE.containsMatchIn(label) ->
                totalQty = INT_TOKEN.find(row.text)?.value?.toIntOrNull() ?: totalQty
            // Sub-total gets first refusal so a bare "Total" (below) never captures "Sub Total".
            SUBTOTAL_RE.containsMatchIn(label) -> if (subtotal == null) subtotal = amount
            ROUND_RE.containsMatchIn(label) -> roundoff += amount
            GRAND_TOTAL_RE.containsMatchIn(label) -> grandTotal = maxOf(grandTotal ?: Long.MIN_VALUE, amount)
            DISCOUNT_RE.containsMatchIn(label) -> discount += abs(amount)
            bucket != null -> taxByBucket[bucket] = (taxByBucket[bucket] ?: 0L) + amount
            FEES_RE.containsMatchIn(label) -> fees += amount
            // A plain "Total" line (not sub-total, not a tax/fee/discount) is the grand total.
            BARE_TOTAL_RE.containsMatchIn(label) -> grandTotal = maxOf(grandTotal ?: Long.MIN_VALUE, amount)
        }
    }

    val tax = if (taxByBucket.containsKey(TaxBucket.GST)) {
        (taxByBucket[TaxBucket.GST] ?: 0L) + (taxByBucket[TaxBucket.IGST] ?: 0L) +
            (taxByBucket[TaxBucket.VAT] ?: 0L) + (taxByBucket[TaxBucket.GENERIC] ?: 0L)
    } else {
        taxByBucket.values.sum()
    }

    return Totals(
        subtotal = subtotal,
        tax = tax,
        fees = fees,
        discount = discount,
        grandTotal = grandTotal,
        roundoff = roundoff,
        totalQty = totalQty,
    )
}
