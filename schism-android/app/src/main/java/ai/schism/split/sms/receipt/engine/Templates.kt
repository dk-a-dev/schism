package ai.schism.split.sms.receipt.engine

/**
 * The bill "shape" a per-source template normalizes towards, before the generic
 * geometry/column/region/solver pipeline runs. Never a specific merchant — a structural family of
 * bill layouts (e.g. any Swiggy-style food-delivery receipt, any Blinkit-style quick-commerce
 * receipt) that share the same generic quirks.
 */
enum class Source { PAPER, GROCERY, SWIGGY, ZOMATO, BLINKIT, GENERIC }

// ---- source detection: generic keyword classes, never a specific merchant/fixture value ----

private val ZOMATO_TEXT = Regex("""\bzomato\b""", RegexOption.IGNORE_CASE)
private val SWIGGY_TEXT = Regex("""\bswiggy\b""", RegexOption.IGNORE_CASE)

/**
 * Food-delivery bill-shape keywords common to Swiggy/Zomato-style receipts. A plain POS bill can
 * carry "Bill Details" on its own (e.g. a generic paper receipt's section title), so that phrase
 * alone is not a reliable food-delivery signal. Requiring the items-subtotal label ("Item Total")
 * together with the delivery-fee structure that only a food-delivery bill has (packaging/platform/
 * delivery fee line) narrows this to the genuine shared shape.
 */
private val FOOD_DELIVERY_ITEM_TOTAL = Regex("""item\s*total""", RegexOption.IGNORE_CASE)
private val FOOD_DELIVERY_FEE_STRUCTURE = Regex(
    """restaurant\s*packaging|packaging\s*charges?|platform\s*fee|delivery\s*(fee|charges?)""",
    RegexOption.IGNORE_CASE,
)

/**
 * Quick-commerce bill-shape keywords common to Blinkit-style receipts. Bare "MRP" is deliberately
 * NOT included here: it appears on nearly every Indian retail/grocery bill (any GROCERY-shaped
 * Rate/Amount table routinely prints "MRP" in its header), so keying off it alone would misroute
 * ordinary bills into this template. A genuine quick-commerce signal — the app's own name, or its
 * "N items in this order" preamble phrasing — is required instead.
 */
private val BLINKIT_KEYWORDS = Regex("""\bblinkit\b|items?\s*in\s*this\s*order""", RegexOption.IGNORE_CASE)

private val GROCERY_HSN = Regex("""\bhsn\b""", RegexOption.IGNORE_CASE)
private val GROCERY_GSTIN = Regex("""\bgstin\b""", RegexOption.IGNORE_CASE)
private val GROCERY_RATE = Regex("""\brate\b""", RegexOption.IGNORE_CASE)

/**
 * Classifies [rows] into the bill-shape family whose template normalizations best apply, using
 * only generic keyword classes seen anywhere on the bill — never a specific merchant name or
 * fixture value. Literal brand words ("Swiggy"/"Zomato") are themselves a generic structural
 * signal (the app that rendered the bill), not a fixture branch; a bill carrying neither brand
 * word but showing the shared food-delivery keyword shape ("Item Total" plus a packaging/
 * platform/delivery fee line) still resolves to the same [SWIGGY] template family, since Swiggy
 * and Zomato bills share it.
 */
fun detectSource(rows: List<Row>): Source {
    val text = rows.joinToString(" ") { it.text }
    return when {
        ZOMATO_TEXT.containsMatchIn(text) -> Source.ZOMATO
        SWIGGY_TEXT.containsMatchIn(text) -> Source.SWIGGY
        BLINKIT_KEYWORDS.containsMatchIn(text) -> Source.BLINKIT
        FOOD_DELIVERY_ITEM_TOTAL.containsMatchIn(text) && FOOD_DELIVERY_FEE_STRUCTURE.containsMatchIn(text) ->
            Source.SWIGGY
        GROCERY_HSN.containsMatchIn(text) || (GROCERY_GSTIN.containsMatchIn(text) && GROCERY_RATE.containsMatchIn(text)) ->
            Source.GROCERY
        else -> Source.PAPER
    }
}

// ---- normalizations, applied before column detection ----

/** True when [text] has at least 2 letters — a plausible name fragment, not a stray symbol. */
private fun looksLikeNameText(text: String): Boolean = text.count { it.isLetter() } >= 2

/** Trailing "xN" quantity suffix on an item name (e.g. "... (Regular) x1", "Butter Chicken x2"). */
private val QTY_SUFFIX = Regex("""(?i)\s+x\s*(\d{1,2})\s*$""")

/**
 * Leading quantity prefix on a printed line: `5 x Hakka Noodles`, `2 × Veg Biryani`, `1 @ 699/ea`.
 * The separator is required (`x`/`×`/`@`), so a name whose first token is a bare digit ("7 Up",
 * "500ml Water") is untouched, and something must follow it, so a lone "5 x" isn't consumed.
 */
private val QTY_PREFIX = Regex("""^\s*(\d{1,3})\s*[x×@]\s*(?=\S)""", RegexOption.IGNORE_CASE)

/** Rewrites [cell] to hold only [keep], moving its left edge right in proportion to what was cut. */
private fun Cell.trimmedTo(keep: String): Cell {
    val cut = text.length - keep.length
    val perChar = if (text.isEmpty()) 0 else (xRight - xLeft) / text.length
    return copy(text = keep, xLeft = xLeft + cut * perChar)
}

/**
 * Lifts a quantity written INLINE in a row's own text into [Row.qty], leaving the cell holding only
 * what remains: `5 x Hakka Noodles` → qty 5 + "Hakka Noodles", `1 @ 699/ea` → qty 1 + the rate
 * "699/ea", `Paneer Wrap x1` → qty 1 + "Paneer Wrap".
 *
 * This is deliberately NOT a synthetic QTY cell placed somewhere plausible on the page. An inline
 * quantity shares the item name's x-span by construction, so it can never be separated into its own
 * column: on a bill whose names are printed full-width, every xCenter from the qty through the
 * name to the rate falls inside one gap-clustered blob, and column detection finds no QTY at all.
 * Reading it off the text and carrying it on the row sidesteps the geometry entirely — and it
 * applies to EVERY bill family, not just the delivery apps the suffix form was first seen on.
 *
 * The prefix form is only read off the row's leftmost cell (a quantity prefix starts the line by
 * definition); the suffix form off the first name-shaped cell, as before.
 */
private fun liftInlineQty(rows: List<Row>): List<Row> = rows.map { row ->
    val leftmost = row.cells.minByOrNull { it.xLeft }
    val prefix = leftmost?.let { QTY_PREFIX.find(it.text) }
    if (prefix != null && leftmost != null) {
        val qty = prefix.groupValues[1].toIntOrNull()
        if (qty != null) {
            val kept = leftmost.text.removeRange(prefix.range)
            return@map row.copy(
                cells = row.cells.map { if (it === leftmost) it.trimmedTo(kept) else it },
                qty = qty,
            )
        }
    }

    val idx = row.cells.indices.firstOrNull { i ->
        val t = row.cells[i].text
        looksLikeNameText(t) && QTY_SUFFIX.containsMatchIn(t)
    } ?: return@map row
    val cell = row.cells[idx]
    val match = QTY_SUFFIX.find(cell.text) ?: return@map row
    val qty = match.groupValues[1].toIntOrNull() ?: return@map row
    row.copy(
        cells = row.cells.toMutableList().also { it[idx] = cell.copy(text = cell.text.removeRange(match.range).trim()) },
        qty = qty,
    )
}

/**
 * Totals/tax/fee keyword class: when a would-be subline's text contains one of these, it's a
 * genuine (mis-split) totals-label row rather than an item's option/customisation line, so
 * [foldOptionSublines] must not fold it away.
 */
private val TOTALS_OR_FEE_KEYWORD = Regex(
    """total|tax|gst|cgst|sgst|amount|subtotal|discount|charge|payable""",
    RegexOption.IGNORE_CASE,
)

/**
 * Folds an option/customisation subline into the previous row's name cell: a subline is a single,
 * letters-only, moneyless cell indented (its xLeft further right) relative to the previous row's
 * leftmost cell — the generic structural shape of a Swiggy/Zomato item's option line ("Cilantro
 * Lime Rice" under "Crispy Peri Peri Chicken Rice Bowl"), never a merchant- or item-specific check.
 * The subline row is dropped entirely (folded away), not emitted as its own row/item.
 *
 * Two additional guards keep this from misfiring on a totals row that happens to have been split
 * across two lines: (a) the previous row must be a genuine priced item — i.e. carry at least one
 * money cell of its own — since a totals *label* line (e.g. "Grand" wrapped onto its own line
 * before "Total | 552.00") never has a preceding priced row to fold into by coincidence the way an
 * item's option subline does; and (b) the subline's own text must not contain a totals/tax/fee
 * keyword ([TOTALS_OR_FEE_KEYWORD]), since a genuine item-option word ("Cilantro Lime Rice") never
 * does, while a split totals label ("Grand Total", "CGST", ...) always does.
 */
private fun foldOptionSublines(rows: List<Row>): List<Row> {
    val out = mutableListOf<Row>()
    for (row in rows) {
        val prev = out.lastOrNull()
        val text = row.text.trim()
        val lettersOnly = text.isNotEmpty() && text.none { it.isDigit() } && looksLikeNameText(text)
        val singleCell = row.cells.size == 1
        val prevLeft = prev?.cells?.minOfOrNull { it.xLeft }
        val prevHasMoney = prev?.cells?.any { isMoneyToken(it.text) } == true
        val sublineIsTotalsLike = TOTALS_OR_FEE_KEYWORD.containsMatchIn(text)

        val isSubline = prev != null && singleCell && lettersOnly &&
            prevLeft != null && row.cells[0].xLeft > prevLeft &&
            prevHasMoney && !sublineIsTotalsLike

        if (isSubline) {
            val prevRow = out[out.lastIndex]
            val nameIdx = prevRow.cells.indices.minByOrNull { prevRow.cells[it].xLeft }!!
            val nameCell = prevRow.cells[nameIdx]
            val merged = nameCell.copy(text = "${nameCell.text.trim()} $text".trim())
            val newCells = prevRow.cells.toMutableList().also { it[nameIdx] = merged }
            out[out.lastIndex] = prevRow.copy(cells = newCells)
        } else {
            out.add(row)
        }
    }
    return out
}

/**
 * Collapses a two-money-token item cell — Blinkit's strikethrough MRP immediately followed by the
 * paid price — down to just the LAST (paid) token: when a row's last two cells (by position) are
 * both money-shaped, the second-to-last is dropped, but ONLY when it's arithmetically consistent
 * with actually being a struck MRP: a struck MRP is always strictly greater than the paid price
 * that follows it (`parseMinor(secondLast) > parseMinor(last)`), whereas a normal Rate|Amount pair
 * always has `rate <= amount`. Gating on that inequality (rather than position alone) keeps this
 * rule from firing on an ordinary Name/Rate/Qty/Amount table row whose last two cells both happen
 * to be money-shaped (e.g. a plain "Rate | Amount" pair, or a small qty token that is never
 * greater than the paid amount) — never a specific amount or merchant, just the universal fact
 * that a discount always strikes a *higher* price than what's actually charged.
 */
private fun collapseStrikethroughPrice(rows: List<Row>): List<Row> = rows.map { row ->
    val sorted = row.cells.sortedBy { it.xLeft }
    if (sorted.size < 2) return@map row
    val last = sorted.last()
    val secondLast = sorted[sorted.size - 2]
    val lastMinor = parseMinor(last.text)
    val secondLastMinor = parseMinor(secondLast.text)
    if (lastMinor != null && secondLastMinor != null && secondLastMinor > lastMinor) {
        row.copy(cells = sorted.filterNot { it === secondLast })
    } else {
        row
    }
}

/** A lone section-title row preceding the item list (Swiggy/Zomato's "Bill Details", Blinkit's "N items in this order") — carries no priced data of its own. */
private val SECTION_MARKER = Regex("""bill\s*details|items?\s*in\s*this\s*order""", RegexOption.IGNORE_CASE)

/**
 * Replaces a leading section-title marker row with a genuine column-header row (`Item` / `Amount`
 * keyword cells), positioned using the very next row's own name/amount cell bounds. Swiggy/Zomato/
 * Blinkit-style bills reliably open with such a marker instead of an explicit "Item | Amount"
 * header, which otherwise leaves the generic engine's column/region detectors headerless (and, for
 * short whole-rupee bills, unable to find any other structural anchor). Synthesizing the header
 * from the real next row's cell bounds — not fixed/guessed coordinates — keeps this generic: it
 * applies to any bill of this shape, regardless of the specific item name or amount involved.
 */
private fun synthesizeLeadingHeader(rows: List<Row>): List<Row> {
    val markerIdx = rows.indexOfFirst { row ->
        row.cells.size <= 2 && SECTION_MARKER.containsMatchIn(row.text) &&
            row.cells.none { isMoneyToken(it.text) }
    }
    if (markerIdx < 0) return rows

    val nextRow = rows.getOrNull(markerIdx + 1) ?: return rows
    val nameCell = nextRow.cells.minByOrNull { it.xLeft } ?: return rows
    val moneyCell = nextRow.cells.filter { isMoneyToken(it.text) }.maxByOrNull { it.xLeft } ?: return rows
    if (nameCell === moneyCell) return rows

    val y = rows[markerIdx].cells.firstOrNull()?.yCenter ?: nameCell.yCenter
    val header = Row(
        listOf(
            Cell("Item", nameCell.xLeft, nameCell.xRight, y),
            Cell("Amount", moneyCell.xLeft, moneyCell.xRight, y),
        ),
    )
    return rows.toMutableList().also { it[markerIdx] = header }
}

/**
 * Renames an "Item Total" totals label (the food-delivery bill's subtotal-of-items line) to "Sub
 * Total" — semantically the same bucket ([readTotals]' subtotal classification still matches
 * "Sub Total") but without the literal word "Item", which otherwise collides with the generic
 * column-header keyword class and makes this genuine totals row misdetected as a line-item table
 * header. "Item Total" is itself a generic keyword phrase shared by delivery-app bills broadly,
 * not any one merchant's fixture text.
 */
private val ITEM_TOTAL_LABEL = Regex("""(?i)\bitem\s*total\b""")

private fun renameItemTotalCollision(rows: List<Row>): List<Row> = rows.map { row ->
    val idx = row.cells.indices.firstOrNull { ITEM_TOTAL_LABEL.containsMatchIn(row.cells[it].text) }
        ?: return@map row
    val cell = row.cells[idx]
    val renamed = cell.copy(text = ITEM_TOTAL_LABEL.replace(cell.text, "Sub Total"))
    val newCells = row.cells.toMutableList().also { it[idx] = renamed }
    row.copy(cells = newCells)
}

/**
 * Normalizes [rows] into the shape the generic geometry/column/region/solver pipeline expects,
 * per [source]'s bill family. All normalizations are structural pattern packs — keyword classes,
 * two-adjacent-money-tokens, a trailing `xN` suffix, an indented letters-only subline — applicable
 * to any bill of that shape; none branches on a specific merchant name, amount, or fixture string.
 */
fun applyTemplate(source: Source, rows: List<Row>): List<Row> {
    // An inline quantity ("5 x Item", "Item x2", "1 @ 699/ea") is a universal printing convention,
    // not a delivery-app quirk, so this one runs for every bill family.
    var out = liftInlineQty(rows)
    if (source != Source.SWIGGY && source != Source.ZOMATO && source != Source.BLINKIT) return out

    out = foldOptionSublines(out)
    if (source == Source.BLINKIT) out = collapseStrikethroughPrice(out)
    out = synthesizeLeadingHeader(out)
    out = renameItemTotalCollision(out)
    return out
}
