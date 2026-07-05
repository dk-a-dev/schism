package ai.schism.split.sms.receipt

/**
 * A structured draft extracted from a receipt's OCR text, ready to become a transaction/expense.
 * Money is Long minor units. The on-device LLM produces this shape when available; this file's
 * [parseReceipt] is the offline heuristic fallback and is deliberately conservative — a missed item
 * (user adds it manually) is far better than a phone number parsed as a ₹96,772 dish.
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
    /** True when the on-device LLM produced this draft (vs the heuristic fallback). */
    val parsedByAi: Boolean = false,
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

// The grouped alternative REQUIRES a separator (+ not *): with *, a plain "2532" matched only "253"
// (max three digits), which silently corrupted totals and made real dishes fail the sanity bound.
private val AMOUNT_REGEX = Regex("""(\d{1,3}(?:[,\s]\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)""")

// A price for an ITEM must look like money: grouped digits with exactly two decimals. This is what
// keeps phone numbers, bill numbers, "Covers: 3" and "PAY:2532" out of the item list.
private val DECIMAL_AMOUNT = Regex("""\d{1,3}(?:,\d{3})*\.\d{2}""")

// Trailing small integer before the price = the quantity column ("Chkn  1  348.00").
private val TRAILING_QTY = Regex("""(?:^|\s)(\d{1,2})\s*[xX×@]?\s*$""")
private val LEADING_QTY = Regex("""^\s*(\d{1,2})\s*[xX×]\s+""")
// A trailing per-unit rate column ("Paneer Tikka  2  190.00  380.00" → strip the 190.00).
private val TRAILING_RATE = Regex("""(?:^|\s)[₹$€£]?\d{1,3}(?:,\d{3})*\.\d{2}\s*$""")

private val TOTAL_LABEL = Regex("""(?i)\b(grand\s*total|sub\s*total|subtotal|total|amount\s*(?:due|payable)|net\s*payable|balance|tax|gst|vat|change|cash|tip)\b""")

// Rows that are never purchasable items: totals/taxes, bill metadata, contact details, footers.
private val NON_ITEM = Regex(
    """(?i)\b(sub\s*total|subtotal|grand\s*total|total|amount|net|balance|tax|gst|cgst|sgst|vat|""" +
        """service|charge|round\s*off|change|cash|tip|pay|paid|invoice|bill|receipt|order|table|""" +
        """covers?|qty|items?|date|time|mobile|phone|ph|tel|contact|gstin|fssai|thank|feedback|""" +
        """visit|welcome|customer|name|mail|email|www|http|powered|address|reprint|token|kot|""" +
        """card|upi|txn|ref|no)\b""",
)

// dd/mm/yyyy, dd-mm-yy, yyyy-mm-dd, etc.
private val DATE_REGEX = Regex("""\b(\d{4})[-/](\d{1,2})[-/](\d{1,2})\b|\b(\d{1,2})[-/](\d{1,2})[-/](\d{2,4})\b""")

/**
 * Parse OCR text lines from a receipt into a [ReceiptDraft]:
 * - **merchant**: the first mostly-letters line that isn't bill metadata.
 * - **total**: the amount on a line labelled total/amount due, else the largest amount seen.
 * - **items**: lines with a real name (≥3 letters), a money-looking price (two decimals), and no
 *   metadata keywords; a trailing small integer is the quantity; a name-only line directly above a
 *   short item name is treated as the wrapped first half of the name ("Buff Oklahoma" + "Smash").
 * - **tax**: GST/tax/service rows (CGST+SGST when no combined GST row exists).
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

    val merchantIndex = clean.indexOfFirst { line ->
        line.count { it.isLetter() } >= 3 &&
            line.count { it.isLetter() } > line.count { it.isDigit() } &&
            !NON_ITEM.containsMatchIn(line)
    }
    val merchant = clean.getOrNull(merchantIndex) ?: "Receipt"

    val date = clean.firstNotNullOfOrNull { isoDate(it) }

    val lineItems = extractLineItems(clean, merchantIndex, total)
    val subtotal = clean.firstOrNull { it.contains(Regex("(?i)sub\\s*total")) }?.let { largestAmount(it) } ?: 0L
    val tax = extractTax(clean)

    return ReceiptDraft(
        merchant = merchant.take(60),
        totalMinor = total,
        currency = currency,
        date = date,
        lineItems = lineItems,
        taxMinor = tax,
        subtotalMinor = subtotal,
    )
}

private fun extractLineItems(lines: List<String>, merchantIndex: Int, totalMinor: Long): List<ReceiptLineItem> {
    val items = mutableListOf<ReceiptLineItem>()
    val consumedAsName = BooleanArray(lines.size)
    val isItemLine = BooleanArray(lines.size)

    lines.forEachIndexed { index, line ->
        if (index == merchantIndex) return@forEachIndexed
        if (NON_ITEM.containsMatchIn(line)) return@forEachIndexed
        if (isoDate(line) != null) return@forEachIndexed
        if (line.count { it.isLetter() } < 3) return@forEachIndexed

        // The price must be the LAST money-looking (two-decimal) number on the row.
        val price = DECIMAL_AMOUNT.findAll(line).lastOrNull() ?: return@forEachIndexed
        val amount = toMinor(price.value) ?: return@forEachIndexed
        // Sanity: no single dish costs more than the bill's total.
        if (amount <= 0 || amount > totalMinor) return@forEachIndexed

        var before = line.substring(0, price.range.first).trim().trimEnd('₹', '$', '€', '£').trim()
        var qty = 1
        var qtySet = false
        // Strip trailing numeric columns: rate ("... 2 190.00 [380.00]") and the qty column. Repeat
        // so "name qty rate amount" collapses to a clean name regardless of column order.
        while (true) {
            val rate = TRAILING_RATE.find(before)
            if (rate != null) {
                before = before.removeRange(rate.range).trim()
                continue
            }
            val q = TRAILING_QTY.find(before)
            if (q != null) {
                if (!qtySet) {
                    qty = q.groupValues[1].toIntOrNull()?.coerceIn(1, 99) ?: 1
                    qtySet = true
                }
                before = before.removeRange(q.range).trim()
                continue
            }
            break
        }
        LEADING_QTY.find(before)?.let { m ->
            if (!qtySet) qty = m.groupValues[1].toIntOrNull()?.coerceIn(1, 99) ?: qty
            before = before.removeRange(m.range).trim()
        }
        var name = before.trimEnd(':', '-', '·', '.', '*').trim()
        if (name.count { it.isLetter() } < 3) return@forEachIndexed

        // Wrapped names: a short item name whose previous row is letters-only ("Buff Oklahoma" ↵ "Smash").
        if (name.length < 15) {
            val prev = index - 1
            val prevLine = lines.getOrNull(prev)
            if (prevLine != null && prev != merchantIndex && !consumedAsName[prev] && !isItemLine[prev] &&
                prevLine.none { it.isDigit() } && prevLine.count { it.isLetter() } >= 3 &&
                prevLine.length <= 30 && !NON_ITEM.containsMatchIn(prevLine)
            ) {
                name = "${prevLine.trim()} $name"
                consumedAsName[prev] = true
            }
        }

        isItemLine[index] = true
        items.add(ReceiptLineItem(name = name.take(60), amountMinor = amount, qty = qty))
    }
    return items
}

/** Overall tax: a combined GST/tax/service row if present, else CGST + SGST summed. */
private fun extractTax(lines: List<String>): Long {
    val combined = lines.filter {
        it.contains(Regex("(?i)\\b(gst|tax|vat|service)\\b")) && !it.contains(Regex("(?i)\\b(cgst|sgst)\\b"))
    }.mapNotNull { line -> DECIMAL_AMOUNT.findAll(line).lastOrNull()?.let { toMinor(it.value) } }
    if (combined.isNotEmpty()) return combined.max()
    return lines.filter { it.contains(Regex("(?i)\\b(cgst|sgst)\\b")) }
        .mapNotNull { line -> DECIMAL_AMOUNT.findAll(line).lastOrNull()?.let { toMinor(it.value) } }
        .sum()
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
