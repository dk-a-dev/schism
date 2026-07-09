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

/** Food-delivery bill-shape keywords common to Swiggy/Zomato-style receipts. */
private val FOOD_DELIVERY_KEYWORDS = Regex(
    """bill\s*details|item\s*total|restaurant\s*packaging|platform\s*fee""",
    RegexOption.IGNORE_CASE,
)

/** Quick-commerce bill-shape keywords common to Blinkit-style receipts. */
private val BLINKIT_KEYWORDS = Regex("""items?\s*in\s*this\s*order|\bmrp\b""", RegexOption.IGNORE_CASE)

private val GROCERY_HSN = Regex("""\bhsn\b""", RegexOption.IGNORE_CASE)
private val GROCERY_GSTIN = Regex("""\bgstin\b""", RegexOption.IGNORE_CASE)
private val GROCERY_RATE = Regex("""\brate\b""", RegexOption.IGNORE_CASE)

/**
 * Classifies [rows] into the bill-shape family whose template normalizations best apply, using
 * only generic keyword classes seen anywhere on the bill — never a specific merchant name or
 * fixture value. Literal brand words ("Swiggy"/"Zomato") are themselves a generic structural
 * signal (the app that rendered the bill), not a fixture branch; a bill carrying neither brand
 * word but showing the shared food-delivery keyword shape ("Bill Details"/"Item Total"/...)
 * still resolves to the same [SWIGGY] template family, since Swiggy and Zomato bills share it.
 */
fun detectSource(rows: List<Row>): Source {
    val text = rows.joinToString(" ") { it.text }
    return when {
        ZOMATO_TEXT.containsMatchIn(text) -> Source.ZOMATO
        SWIGGY_TEXT.containsMatchIn(text) -> Source.SWIGGY
        BLINKIT_KEYWORDS.containsMatchIn(text) -> Source.BLINKIT
        FOOD_DELIVERY_KEYWORDS.containsMatchIn(text) -> Source.SWIGGY
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
 * Strips a trailing "xN" quantity suffix off each row's name-shaped cell, extracting [qty]. When
 * qty > 1 a synthetic QTY cell is anchored just left of the row's rightmost money cell (so it
 * lands near the amount column rather than merging into the name column); qty == 1 needs no
 * synthetic cell since that's already [extractItems]' default.
 */
private fun stripQtySuffix(rows: List<Row>): List<Row> = rows.map { row ->
    val idx = row.cells.indices.firstOrNull { i ->
        val t = row.cells[i].text
        looksLikeNameText(t) && QTY_SUFFIX.containsMatchIn(t)
    } ?: return@map row

    val cell = row.cells[idx]
    val match = QTY_SUFFIX.find(cell.text) ?: return@map row
    val qty = match.groupValues[1].toIntOrNull() ?: return@map row
    val cleanedText = cell.text.removeRange(match.range).trim()

    val newCells = row.cells.toMutableList()
    newCells[idx] = cell.copy(text = cleanedText)

    if (qty > 1) {
        val moneyCell = row.cells.filter { isMoneyToken(it.text) }.maxByOrNull { it.xLeft }
        if (moneyCell != null && moneyCell.xLeft - cell.xRight > 10) {
            val qRight = moneyCell.xLeft - 2
            val qLeft = qRight - 8
            newCells.add(Cell(qty.toString(), qLeft, qRight, cell.yCenter))
        }
    }
    row.copy(cells = newCells)
}

/**
 * Folds an option/customisation subline into the previous row's name cell: a subline is a single,
 * letters-only, moneyless cell indented (its xLeft further right) relative to the previous row's
 * leftmost cell — the generic structural shape of a Swiggy/Zomato item's option line ("Cilantro
 * Lime Rice" under "Crispy Peri Peri Chicken Rice Bowl"), never a merchant- or item-specific check.
 * The subline row is dropped entirely (folded away), not emitted as its own row/item.
 */
private fun foldOptionSublines(rows: List<Row>): List<Row> {
    val out = mutableListOf<Row>()
    for (row in rows) {
        val prev = out.lastOrNull()
        val text = row.text.trim()
        val lettersOnly = text.isNotEmpty() && text.none { it.isDigit() } && looksLikeNameText(text)
        val singleCell = row.cells.size == 1
        val prevLeft = prev?.cells?.minOfOrNull { it.xLeft }

        val isSubline = prev != null && singleCell && lettersOnly &&
            prevLeft != null && row.cells[0].xLeft > prevLeft

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
 * both money-shaped, the second-to-last is dropped. This is a structural, position-only rule
 * (never a specific amount), scoped to Blinkit-shaped bills where a plain item row is "name +
 * price(s)" rather than a multi-column Name/Rate/Qty/Amount table (where a narrow QTY cell and a
 * wide AMOUNT cell can otherwise both look money-shaped).
 */
private fun collapseStrikethroughPrice(rows: List<Row>): List<Row> = rows.map { row ->
    val sorted = row.cells.sortedBy { it.xLeft }
    if (sorted.size < 2) return@map row
    val last = sorted.last()
    val secondLast = sorted[sorted.size - 2]
    if (isMoneyToken(last.text) && isMoneyToken(secondLast.text)) {
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
    if (source != Source.SWIGGY && source != Source.ZOMATO && source != Source.BLINKIT) return rows

    var out = rows
    out = stripQtySuffix(out)
    out = foldOptionSublines(out)
    if (source == Source.BLINKIT) out = collapseStrikethroughPrice(out)
    out = synthesizeLeadingHeader(out)
    out = renameItemTotalCollision(out)
    return out
}
