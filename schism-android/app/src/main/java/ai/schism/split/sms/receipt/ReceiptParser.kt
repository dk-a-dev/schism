package ai.schism.split.sms.receipt

/**
 * A structured draft extracted from a receipt's OCR text, ready to become a transaction/expense.
 * Money is Long minor units. This is the on-device "structure the OCR text" step: for v1 it uses
 * heuristics (no LLM) so it stays fully offline and unit-testable; an on-device LLM can refine it
 * later without changing this shape.
 */
data class ReceiptDraft(
    val merchant: String,
    val totalMinor: Long,
    val currency: String,
    val date: String?, // ISO yyyy-MM-dd when found
    val lineItems: List<ReceiptLineItem> = emptyList(),
    /** Taxes/charges (GST etc.) to distribute across diners in proportion to what they ordered. */
    val taxMinor: Long = 0,
    /** Sum of the line items before tax (0 = derive from items). */
    val subtotalMinor: Long = 0,
)

/** A single purchased line item on a receipt: name, quantity, and its line amount in minor units. */
data class ReceiptLineItem(
    val name: String,
    val amountMinor: Long,
    val qty: Int = 1,
)

/** Currency symbol/code hints → the symbol we store for display. */
private val CURRENCY_HINTS = mapOf(
    "₹" to "₹", "rs" to "₹", "inr" to "₹",
    "$" to "$", "usd" to "$",
    "€" to "€", "eur" to "€",
    "£" to "£", "gbp" to "£",
)

private val AMOUNT_REGEX = Regex("""(\d{1,3}(?:[,\s]\d{3})*(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)""")
// An amount at the very end of a line (optionally prefixed by a currency symbol), used to split an
// item line into "name" + "price".
private val TRAILING_AMOUNT = Regex("""[₹$€£]?\s*(\d{1,3}(?:[,\s]\d{3})*(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)\s*$""")
private val TOTAL_LABEL = Regex("""(?i)\b(grand\s*total|sub\s*total|subtotal|total|amount\s*(?:due|payable)|net\s*payable|balance|tax|gst|vat|change|cash|tip)\b""")
// dd/mm/yyyy, dd-mm-yy, yyyy-mm-dd, etc.
private val DATE_REGEX = Regex("""\b(\d{4})[-/](\d{1,2})[-/](\d{1,2})\b|\b(\d{1,2})[-/](\d{1,2})[-/](\d{2,4})\b""")

/**
 * Parse OCR text lines from a receipt into a [ReceiptDraft]:
 * - **merchant**: the first meaningful line (the header/brand), skipping lines that are just numbers.
 * - **total**: the amount on a line labelled total/amount due, else the largest amount seen.
 * - **date**: the first recognizable date, normalized to ISO yyyy-MM-dd.
 * - **currency**: inferred from a symbol/code seen in the text (defaults to ₹).
 * Returns null when no amount could be found at all.
 */
fun parseReceipt(lines: List<String>): ReceiptDraft? {
    val clean = lines.map { it.trim() }.filter { it.isNotEmpty() }
    if (clean.isEmpty()) return null

    val currency = detectCurrency(clean) ?: "₹"

    // Prefer the largest amount on any "total"-labelled line (so Grand Total beats Sub Total); else
    // fall back to the largest amount anywhere on the receipt.
    val labelledTotal = clean.filter { TOTAL_LABEL.containsMatchIn(it) }
        .mapNotNull { largestAmount(it) }
        .maxOrNull()
    val total = labelledTotal ?: clean.mapNotNull { largestAmount(it) }.maxOrNull() ?: return null

    val merchant = clean.firstOrNull { line ->
        line.length >= 2 && line.any { it.isLetter() } && !TOTAL_LABEL.containsMatchIn(line)
    } ?: "Receipt"

    val date = clean.firstNotNullOfOrNull { isoDate(it) }

    val lineItems = extractLineItems(clean, merchant)

    return ReceiptDraft(
        merchant = merchant.take(60),
        totalMinor = total,
        currency = currency,
        date = date,
        lineItems = lineItems,
    )
}

/**
 * Item lines are those that contain some letters (a name) AND end with a monetary amount, excluding
 * lines that are totals/subtotals/tax/etc. (the [TOTAL_LABEL] rows) and the merchant header itself.
 * The item name is the line with its trailing amount stripped off.
 */
private fun extractLineItems(lines: List<String>, merchant: String): List<ReceiptLineItem> =
    lines.mapNotNull { line ->
        if (TOTAL_LABEL.containsMatchIn(line)) return@mapNotNull null
        if (line == merchant) return@mapNotNull null
        if (!line.any { it.isLetter() }) return@mapNotNull null
        if (isoDate(line) != null) return@mapNotNull null
        val trailing = TRAILING_AMOUNT.find(line) ?: return@mapNotNull null
        val amount = toMinor(trailing.groupValues[1]) ?: return@mapNotNull null
        val name = line.removeRange(trailing.range).trim().trimEnd(':', '-', '·').trim()
        if (name.isEmpty() || name.none { it.isLetter() }) return@mapNotNull null
        ReceiptLineItem(name = name.take(60), amountMinor = amount)
    }

private fun detectCurrency(lines: List<String>): String? {
    val text = lines.joinToString(" ").lowercase()
    return CURRENCY_HINTS.entries.firstOrNull { text.contains(it.key) }?.value
}

/** Largest monetary amount on a line, as minor units, or null if none. */
private fun largestAmount(line: String): Long? =
    AMOUNT_REGEX.findAll(line)
        .mapNotNull { toMinor(it.value) }
        .maxOrNull()

private fun toMinor(raw: String): Long? {
    val normalized = raw.replace(",", "").replace(" ", "")
    val value = normalized.toDoubleOrNull() ?: return null
    if (value <= 0.0) return null
    return Math.round(value * 100)
}

private fun isoDate(line: String): String? {
    val m = DATE_REGEX.find(line) ?: return null
    return if (m.groupValues[1].isNotEmpty()) {
        // yyyy-mm-dd
        val y = m.groupValues[1]; val mo = m.groupValues[2].padStart(2, '0'); val d = m.groupValues[3].padStart(2, '0')
        if (mo.toInt() in 1..12 && d.toInt() in 1..31) "$y-$mo-$d" else null
    } else {
        // dd-mm-yy(yy)
        val d = m.groupValues[4].padStart(2, '0'); val mo = m.groupValues[5].padStart(2, '0'); var y = m.groupValues[6]
        if (y.length == 2) y = "20$y"
        if (mo.toInt() in 1..12 && d.toInt() in 1..31) "$y-$mo-$d" else null
    }
}
