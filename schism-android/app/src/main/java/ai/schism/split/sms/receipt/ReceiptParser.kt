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
    /** Packaging/platform/service/delivery charges (0 = none detected). */
    val feesMinor: Long = 0,
    /** Discount/offer/savings applied to the bill, as a POSITIVE magnitude that SUBTRACTS (0 = none detected). */
    val discountMinor: Long = 0,
    /** True when the engine's arithmetic constraint solver confirmed this draft's totals are internally consistent. */
    val verified: Boolean = false,
    /**
     * The bill's charge rows individually, as printed and in bill order — so a bill carrying CGST,
     * SGST, VAT and a service charge can be reviewed and corrected line by line instead of as one
     * opaque "Tax" pot. Empty when no charge row was found (or when the draft came from the LLM).
     *
     * These are the SAME money as [taxMinor]: `chargeLines.sumOf { it.amountMinor } == taxMinor`.
     * The aggregate fields above stay exactly as they were, so this is purely additive.
     */
    val chargeLines: List<ChargeLine> = emptyList(),
)

/** Which side of the bill a [ChargeLine] came from. Presentation only — the sign already carries the arithmetic. */
enum class ChargeKind { TAX, FEE, DISCOUNT, ROUNDOFF }

/**
 * One charge row exactly as the bill printed it: its [label] ("CGST 2.5%", "Service Charge 10%",
 * "Round off(-)"), its [kind], and [amountMinor] in minor units.
 *
 * **[amountMinor] is SIGNED, always in the direction it moves the bill**: a tax or fee is positive,
 * a discount is NEGATIVE, and a round-off is whatever it was printed as (either sign). So the total
 * is always `subtotal + chargeLines.sumOf { it.amountMinor }` — no per-kind sign flipping. Note this
 * differs from [ReceiptDraft.discountMinor], which is a positive magnitude that subtracts; the
 * signed form here exists precisely so that trap isn't repeated per line.
 */
data class ChargeLine(val label: String, val amountMinor: Long, val kind: ChargeKind)

/**
 * A single purchased line item on a receipt: name, quantity, its per-unit price, and its line
 * amount, all money in minor units. The invariant is `amountMinor == qty * unitPriceMinor` for a
 * cleanly-read row; [unitPriceMinor] defaults to 0 when only a lump amount is known (callers may
 * derive it as `amountMinor / qty`).
 */
data class ReceiptLineItem(
    val name: String,
    val amountMinor: Long,
    val qty: Int = 1,
    val unitPriceMinor: Long = 0,
)

/** Currency symbol/code hints → the symbol we store for display. */
private val CURRENCY_HINTS = mapOf(
    "₹" to "₹", "rs" to "₹", "inr" to "₹",
    "$" to "$", "usd" to "$",
    "€" to "€", "eur" to "€",
    "£" to "£", "gbp" to "£",
    // Listed last so a receipt that also prints a symbol keeps the symbol. "RM" is the ringgit's
    // ordinary printed form on every Malaysian receipt; without it those bills reported no currency
    // at all and rendered as bare numbers.
    "rm" to "RM", "myr" to "RM",
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

// Month-name dates: "05 MAR 2018", "5-Mar-18", "1 January 2018" and the month-first spelling
// "MAR 05, 2018". A great many POS printers date a receipt this way instead of with digit groups,
// and such a bill previously reported no date at all.
private val DATE_DAY_MONTH = Regex("""\b(\d{1,2})[-. ]\s*([A-Za-z]{3,9})\.?[-. ,]+(\d{2,4})\b""")
private val DATE_MONTH_DAY = Regex("""\b([A-Za-z]{3,9})\.?[-. ]+(\d{1,2})(?:st|nd|rd|th)?[-. ,]+(\d{2,4})\b""")

/** Month name (full, 3-letter, or "sept") → 1-12. The whole word must be a month, so an ordinary word that merely starts with "jan"/"mar" is never read as one. */
private val MONTH_INDEX: Map<String, Int> = buildMap {
    listOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december",
    ).forEachIndexed { i, name ->
        put(name, i + 1)
        put(name.take(3), i + 1)
    }
    put("sept", 9)
}

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

/** The first currency hinted at anywhere in [lines] (symbol or ISO code), or null when none is printed. */
internal fun detectCurrency(lines: List<String>): String? {
    val text = lines.joinToString(" ").lowercase()
    return CURRENCY_HINTS.entries.firstOrNull { (hint, _) ->
        // Alphabetic codes must be whole words. As a bare substring "rs" matches Burgers, Crackers
        // and hours, so a dollar bill with a burger on it reported rupees.
        if (hint.first().isLetter()) Regex("\\b${Regex.escape(hint)}\\b").containsMatchIn(text)
        else text.contains(hint)
    }?.value
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

/** A valid ISO date, or null when the (year, month, day) triple isn't a real calendar date. A 2-digit year is this century. */
private fun iso(year: Int, month: Int, day: Int): String? {
    if (month !in 1..12 || day !in 1..31) return null
    return "%04d-%02d-%02d".format(if (year < 100) year + 2000 else year, month, day)
}

/** "05 MAR 2018" / "5-Mar-18" / "MAR 05, 2018", or null when no month NAME is spelled out. */
private fun monthNameDate(line: String): String? {
    DATE_DAY_MONTH.find(line)?.let { m ->
        val month = MONTH_INDEX[m.groupValues[2].lowercase()]
        if (month != null) return iso(m.groupValues[3].toInt(), month, m.groupValues[1].toInt())
    }
    DATE_MONTH_DAY.find(line)?.let { m ->
        val month = MONTH_INDEX[m.groupValues[1].lowercase()]
        if (month != null) return iso(m.groupValues[3].toInt(), month, m.groupValues[2].toInt())
    }
    return null
}

/**
 * Visible across the module (not just this file) so the geometry-based engine's `parseBill` can
 * reuse the same date detection.
 *
 * Digit-group dates are read DAY-first (`dd/mm/yyyy`), the spelling most of the world prints. When
 * that reading is impossible — the middle group is >12, so it cannot be a month — the receipt was
 * printed MONTH-first (`mm/dd/yyyy`) and is read that way instead of reporting no date at all. A
 * date where both groups are ≤12 stays day-first: nothing on the line can resolve it, and guessing
 * the other way round would be no better founded.
 */
internal fun isoDate(line: String): String? {
    DATE_REGEX.find(line)?.let { m ->
        val g = m.groupValues
        val parsed = if (g[1].isNotEmpty()) {
            iso(g[1].toInt(), g[2].toInt(), g[3].toInt()) // yyyy-mm-dd
        } else {
            iso(g[6].toInt(), g[5].toInt(), g[4].toInt()) // dd-mm-yy(yy)
                ?: iso(g[6].toInt(), g[4].toInt(), g[5].toInt()) // …else mm-dd-yy(yy)
        }
        if (parsed != null) return parsed
    }
    return monthNameDate(line)
}
