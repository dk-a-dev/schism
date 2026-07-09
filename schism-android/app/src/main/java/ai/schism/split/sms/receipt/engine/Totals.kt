package ai.schism.split.sms.receipt.engine

/** Generic keyword classes for a totals-region row: label pattern → the financial role it plays. */
private val SUBTOTAL_RE = Regex("""sub\s*total""", RegexOption.IGNORE_CASE)
private val GRAND_TOTAL_RE = Regex(
    """grand\s*total|bill\s*amount|bill\s*total|amount\s*payable|\bpaid\b|rounded\s*total|\bnet\b""",
    RegexOption.IGNORE_CASE,
)
private val DISCOUNT_RE = Regex("""discount|\boff\b|saved""", RegexOption.IGNORE_CASE)
private val FEES_RE = Regex("""packaging|platform|service|delivery|charge""", RegexOption.IGNORE_CASE)
private val FREE_RE = Regex("""\bfree\b""", RegexOption.IGNORE_CASE)

private val CGST_RE = Regex("""\bcgst\b""", RegexOption.IGNORE_CASE)
private val SGST_RE = Regex("""\bsgst\b""", RegexOption.IGNORE_CASE)
private val IGST_RE = Regex("""\bigst\b""", RegexOption.IGNORE_CASE)
private val GST_RE = Regex("""\bgst\b""", RegexOption.IGNORE_CASE)
private val VAT_RE = Regex("""\bvat\b""", RegexOption.IGNORE_CASE)
private val TAX_RE = Regex("""\btax\b""", RegexOption.IGNORE_CASE)

/** A bare money-shaped token, used to pull an amount out of a row's joined text as a fallback. */
private val MONEY_TOKEN = Regex("""[₹$€£]?\s*-?\d[\d,]*(?:\.\d{1,2})?""")

/** A generic-keyword breakdown of a bill's totals region, all amounts in Long minor units (paise/cents). */
data class Totals(
    val subtotal: Long?,
    val tax: Long = 0,
    val fees: Long = 0,
    val discount: Long = 0,
    val grandTotal: Long? = null,
)

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
 * fees / discount / grand-total — and folds same-class rows into a single [Totals] breakdown.
 *
 * Tax rows sum CGST+SGST(+IGST/VAT/generic-tax) unless a standalone combined "GST" row is present,
 * in which case it replaces the CGST/SGST split (IGST/VAT/generic-tax rows are distinct line items
 * and still add on top). Fee rows (packaging/platform/service/delivery/charge) and discount rows
 * (discount/off/saved) are summed across however many such rows appear. The grand total is the
 * largest amount among grand-total-labelled rows, since a bill may show both a pre-round and a
 * rounded figure. A "FREE"/struck-through row always contributes 0.
 *
 * This is keyword-and-arithmetic only: no merchant name, fixture value, or specific amount is ever
 * inspected, so the same classification applies to any bill shape.
 */
fun readTotals(regions: Regions): Totals {
    var subtotal: Long? = null
    var fees = 0L
    var discount = 0L
    var grandTotal: Long? = null
    val taxByBucket = mutableMapOf<TaxBucket, Long>()

    for (row in regions.totals) {
        val label = rowLabel(row)
        val amount = rowAmount(row)
        val bucket = taxBucketOf(label)

        when {
            DISCOUNT_RE.containsMatchIn(label) -> discount += amount
            GRAND_TOTAL_RE.containsMatchIn(label) -> grandTotal = maxOf(grandTotal ?: Long.MIN_VALUE, amount)
            SUBTOTAL_RE.containsMatchIn(label) -> if (subtotal == null) subtotal = amount
            bucket != null -> taxByBucket[bucket] = (taxByBucket[bucket] ?: 0L) + amount
            FEES_RE.containsMatchIn(label) -> fees += amount
        }
    }

    val tax = if (taxByBucket.containsKey(TaxBucket.GST)) {
        (taxByBucket[TaxBucket.GST] ?: 0L) + (taxByBucket[TaxBucket.IGST] ?: 0L) +
            (taxByBucket[TaxBucket.VAT] ?: 0L) + (taxByBucket[TaxBucket.GENERIC] ?: 0L)
    } else {
        taxByBucket.values.sum()
    }

    return Totals(subtotal = subtotal, tax = tax, fees = fees, discount = discount, grandTotal = grandTotal)
}
