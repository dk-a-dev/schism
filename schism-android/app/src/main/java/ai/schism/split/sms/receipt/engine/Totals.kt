package ai.schism.split.sms.receipt.engine

import ai.schism.split.sms.receipt.ChargeKind
import ai.schism.split.sms.receipt.ChargeLine
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
// "\bfees?\b" is the generic case: a fee line names its own kind ("Packing Fee", "Convenience
// Fee") and the enumerated words below only cover the kinds we happened to have seen. "serv" and
// "chg" are prefix/abbreviation forms, not whole words, because thermal printers abbreviate to fit
// the paper width ("Serv. Chg @ 10.00%") — a whole-word "service|charge" missed that line entirely
// and the bill then failed to reconcile by exactly the service charge.
private val FEES_RE = Regex("""packaging|platform|\bserv|delivery|charge|\bchg\b|\bfees?\b|\btip\b|gratuity""", RegexOption.IGNORE_CASE)
private val FREE_RE = Regex("""\bfree\b""", RegexOption.IGNORE_CASE)

// Tax codes are delimited by LETTERS, not by word boundaries: a POS routinely fuses the rate onto
// the code ("CGST2.5 2.5%", "CGST@ 9.00"), where `\bcgst\b` finds no boundary between "CGST" and
// "2" and the whole tax line goes unclassified. Requiring only that no letter abuts the code keeps
// "GSTIN" from matching GST while accepting every rate-suffixed spelling.
private val CGST_RE = Regex("""(?<![a-z])cgst(?![a-z])""", RegexOption.IGNORE_CASE)
private val SGST_RE = Regex("""(?<![a-z])sgst(?![a-z])""", RegexOption.IGNORE_CASE)
private val IGST_RE = Regex("""(?<![a-z])igst(?![a-z])""", RegexOption.IGNORE_CASE)
private val GST_RE = Regex("""(?<![a-z])gst(?![a-z])""", RegexOption.IGNORE_CASE)
private val VAT_RE = Regex("""(?<![a-z])vat(?![a-z])""", RegexOption.IGNORE_CASE)
// A compensation "cess" is a distinct levy that rides ON TOP of the GST it accompanies, so it maps
// to the GENERIC tax bucket (which always adds, even when a combined "GST" row is present).
private val CESS_RE = Regex("""\bcess\b""", RegexOption.IGNORE_CASE)
private val TAX_RE = Regex("""\btax\b""", RegexOption.IGNORE_CASE)

/**
 * A minus sign carried by the LABEL rather than the amount: "Round off(-) 0.24", "Discount (-)".
 * Indian POS printers put the sign next to the label and print the magnitude in the amount column,
 * so the printed 0.24 is really −0.24.
 */
private val LABEL_NEGATIVE = Regex("""\(\s*-\s*\)|-\s*$""")

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
    /**
     * Every charge row as printed — label, SIGNED amount, bucket — in bill order. The aggregates
     * above are exactly this list folded up: `lines.sumOf { it.amountMinor } == tax + fees -
     * discount + roundoff`, the charge pot. See [ChargeLine] for the sign convention.
     */
    val lines: List<ChargeLine> = emptyList(),
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
    CESS_RE.containsMatchIn(label) -> TaxBucket.GENERIC
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
 * adjustment can go either way (a "(-)" printed beside the LABEL counts as that sign).
 *
 * The grand total is the LAST grand-total-labelled row, not the largest: a bill that prints two of
 * them prints the figure actually payable last ("Bill Total 9473.90" then "Bill Total (rounded)
 * 9474.00"; "Total Amount 1142.24" then "Net Amount 1142.00"), and rounding goes both ways, so
 * "largest" got the second of those backwards. A grand-total-labelled row (checked first) wins over
 * any of the other classes a label might otherwise also match — e.g.
 * "Amount Payable after Discount" is the grand total, not a discount. A "FREE"/struck-through row
 * always contributes 0.
 *
 * This is keyword-and-arithmetic only: no merchant name, fixture value, or specific amount is ever
 * inspected, so the same classification applies to any bill shape.
 */
fun readTotals(regions: Regions): Totals {
    var subtotal: Long? = null
    var grandTotal: Long? = null
    var totalQty: Int? = null
    val taxByBucket = mutableMapOf<TaxBucket, Long>()
    // Charge rows in printed order, each tagged with its tax bucket (null for fee/discount/round-off)
    // so the CGST/SGST rows a combined "GST" row supersedes can be dropped from both at once.
    val charges = mutableListOf<Pair<TaxBucket?, ChargeLine>>()

    for (row in regions.totals) {
        val label = rowLabel(row).trim()
        val amount = rowAmount(row)
        val bucket = taxBucketOf(label)

        when {
            // A "Total Qty: N" line is a units count, not money — captured first so its "Total"
            // keyword never leaks into the grand-total classification below.
            TOTAL_QTY_RE.containsMatchIn(label) ->
                totalQty = INT_TOKEN.find(row.text)?.value?.toIntOrNull() ?: totalQty
            // Sub-total gets first refusal so a bare "Total" (below) never captures "Sub Total".
            SUBTOTAL_RE.containsMatchIn(label) -> if (subtotal == null) subtotal = amount
            ROUND_RE.containsMatchIn(label) -> {
                val signed = if (LABEL_NEGATIVE.containsMatchIn(label)) -abs(amount) else amount
                charges += null to ChargeLine(label, signed, ChargeKind.ROUNDOFF)
            }
            GRAND_TOTAL_RE.containsMatchIn(label) -> grandTotal = amount
            DISCOUNT_RE.containsMatchIn(label) -> charges += null to ChargeLine(label, -abs(amount), ChargeKind.DISCOUNT)
            bucket != null -> {
                taxByBucket[bucket] = (taxByBucket[bucket] ?: 0L) + amount
                charges += bucket to ChargeLine(label, amount, ChargeKind.TAX)
            }
            FEES_RE.containsMatchIn(label) -> charges += null to ChargeLine(label, amount, ChargeKind.FEE)
            // A plain "Total" line (not sub-total, not a tax/fee/discount) is the grand total.
            BARE_TOTAL_RE.containsMatchIn(label) -> grandTotal = amount
        }
    }

    // A combined "GST" row supersedes the CGST/SGST split it summarises (IGST/VAT/generic-tax rows
    // are distinct levies and still add on top) — dropped from the line detail too, so the detail
    // always folds back up to the aggregate.
    val gstSupersedes = taxByBucket.containsKey(TaxBucket.GST)
    val lines = charges
        .filterNot { (b, _) -> gstSupersedes && (b == TaxBucket.CGST || b == TaxBucket.SGST) }
        .map { it.second }

    return Totals(
        subtotal = subtotal,
        tax = lines.filter { it.kind == ChargeKind.TAX }.sumOf { it.amountMinor },
        fees = lines.filter { it.kind == ChargeKind.FEE }.sumOf { it.amountMinor },
        discount = -lines.filter { it.kind == ChargeKind.DISCOUNT }.sumOf { it.amountMinor },
        grandTotal = grandTotal,
        roundoff = lines.filter { it.kind == ChargeKind.ROUNDOFF }.sumOf { it.amountMinor },
        totalQty = totalQty,
        lines = lines,
    )
}
