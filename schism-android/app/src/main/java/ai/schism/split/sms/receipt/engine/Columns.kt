package ai.schism.split.sms.receipt.engine

/** The semantic role a detected column plays in a line-item table. */
enum class ColRole { ITEM, QTY, RATE, AMOUNT, OTHER }

/** A horizontal band on the page, spanning [xLeft, xRight], assigned a role. */
data class Column(val xLeft: Int, val xRight: Int, val role: ColRole)

// Word-boundary anchored so e.g. RATE_KEYWORDS doesn't match "Corporate", or ITEM_KEYWORDS
// match a plural like "Particulars" (which is a legitimate near-miss, not a keyword hit).
private val ITEM_KEYWORDS = Regex("\\b(item|description|particular)\\b", RegexOption.IGNORE_CASE)
private val QTY_KEYWORDS = Regex("\\b(qty|quantity)\\b", RegexOption.IGNORE_CASE)
private val RATE_KEYWORDS = Regex("\\b(rate|price|mrp)\\b", RegexOption.IGNORE_CASE)
private val AMOUNT_KEYWORDS = Regex("\\b(amount|amt|total)\\b", RegexOption.IGNORE_CASE)

private fun keywordRole(text: String): ColRole? {
    val t = text.trim()
    return when {
        ITEM_KEYWORDS.containsMatchIn(t) -> ColRole.ITEM
        QTY_KEYWORDS.containsMatchIn(t) -> ColRole.QTY
        RATE_KEYWORDS.containsMatchIn(t) -> ColRole.RATE
        AMOUNT_KEYWORDS.containsMatchIn(t) -> ColRole.AMOUNT
        else -> null
    }
}

/** True when the cell's text is mostly alphabetic (an item/description-like label). */
private fun isMostlyLetters(text: String): Boolean {
    val letters = text.count { it.isLetter() }
    val digits = text.count { it.isDigit() }
    return letters > 0 && letters >= digits
}

/** True when the cell's text parses as a plain number (int or decimal), ignoring stray punctuation. */
private fun isNumeric(text: String): Boolean {
    val cleaned = text.trim().replace(",", "")
    return cleaned.isNotEmpty() && cleaned.matches(Regex("-?\\d+(\\.\\d+)?"))
}

/**
 * A cluster of cells whose xCenters fall close together — the raw building block for a [Column]
 * before roles and neighbour-padded bounds are assigned.
 */
private class Cluster {
    val cells = mutableListOf<Cell>()
    val centers get() = cells.map { it.xCenter }
    val minCenter get() = centers.min()
    val maxCenter get() = centers.max()
}

/**
 * Clusters every cell's xCenter into columns (gap-based: a new column starts wherever the gap
 * between consecutive sorted centers exceeds ~4% of the page width), then assigns each column a
 * [ColRole] — first via header keyword match, falling back to structural heuristics when no
 * header row is found. Cells outside every explicitly-roled column resolve to OTHER via [cellIn].
 */
fun detectColumns(rows: List<Row>): List<Column> {
    val allCells = rows.flatMap { it.cells }
    if (allCells.isEmpty()) return emptyList()

    val maxX = allCells.maxOf { it.xRight }
    val gapThreshold = (maxX / 25.0).coerceAtLeast(1.0)

    val sortedCells = allCells.sortedBy { it.xCenter }
    val clusters = mutableListOf<Cluster>()
    for (cell in sortedCells) {
        val cur = clusters.lastOrNull()
        if (cur != null && (cell.xCenter - cur.maxCenter) <= gapThreshold) {
            cur.cells.add(cell)
        } else {
            clusters.add(Cluster().also { it.cells.add(cell) })
        }
    }

    // Bounds: each column spans its own members, padded outward to the midpoint between it and
    // its neighbours (so cellIn's inclusive range covers every cell that belongs to it).
    val bounds = clusters.mapIndexed { i, cluster ->
        val prevMax = clusters.getOrNull(i - 1)?.maxCenter
        val nextMin = clusters.getOrNull(i + 1)?.minCenter
        val left = if (prevMax != null) (prevMax + cluster.minCenter) / 2 else cluster.cells.minOf { it.xLeft }
        val right = if (nextMin != null) (cluster.maxCenter + nextMin) / 2 else cluster.cells.maxOf { it.xRight }
        left to right
    }

    val roles = arrayOfNulls<ColRole>(clusters.size)

    // 1. Header match: find the first row whose cells mostly match a header keyword class, and
    // assign roles to the columns containing its keyword-bearing cells.
    val headerRow = rows.firstOrNull { row ->
        row.cells.isNotEmpty() && row.cells.count { keywordRole(it.text) != null } * 2 >= row.cells.size
    }
    if (headerRow != null) {
        for (cell in headerRow.cells) {
            val role = keywordRole(cell.text) ?: continue
            val idx = clusters.indexOfFirst { cluster -> cluster.cells.any { it === cell } }
            if (idx >= 0) roles[idx] = role
        }
    }

    // 2. Structural fallback for any column still unassigned — either because no header row was
    // found at all, or because its header cell (e.g. a bare "Name") didn't match a keyword class.
    // Classification uses only non-header rows so header labels' own letters don't skew it.
    //
    // A real header row can also go undetected by keywordRole entirely (e.g. "Particulars" /
    // "Nos" / "Value" — near-misses that don't hit the class threshold). Left in dataRows, its
    // label cells corrupt the majority-vote tallies below (e.g. a non-numeric "Nos" breaks the
    // strict "all cells are small ints" QTY check). Detect that case structurally: the first row
    // is a label row, not a data row, when none of its cells look numeric — a genuine item row
    // always carries at least one numeric cell (qty, rate or amount), so this can't misfire on a
    // legit item.
    val firstRow = rows.firstOrNull()
    val structuralHeaderRow = firstRow?.takeIf {
        it !== headerRow && it.cells.isNotEmpty() && it.cells.none { cell -> isNumeric(cell.text) }
    }
    val dataRows = rows.filter { it !== headerRow && it !== structuralHeaderRow }
    val dataRowCells = dataRows.flatMap { it.cells }
    val dataCells = clusters.map { cluster -> cluster.cells.filter { cell -> dataRowCells.any { it === cell } } }

    fun unassigned() = clusters.indices.filter { roles[it] == null }

    // Leftmost unassigned column with mostly-letters cells → ITEM.
    if (roles.none { it == ColRole.ITEM }) {
        val itemIdx = unassigned().firstOrNull { i ->
            val cells = dataCells[i]
            cells.isNotEmpty() && cells.count { isMostlyLetters(it.text) } * 2 >= cells.size
        }
        if (itemIdx != null) roles[itemIdx] = ColRole.ITEM
    }

    // Rightmost unassigned column with mostly-numeric cells → AMOUNT.
    if (roles.none { it == ColRole.AMOUNT }) {
        val amountIdx = unassigned().lastOrNull { i ->
            val cells = dataCells[i]
            cells.isNotEmpty() && cells.count { isNumeric(it.text) } * 2 >= cells.size
        }
        if (amountIdx != null) roles[amountIdx] = ColRole.AMOUNT
    }

    // Among remaining unassigned numeric columns: a narrow one whose cells are all small
    // integers → QTY; a remaining decimal-bearing numeric column → RATE.
    val remainingNumericIdx = unassigned().filter { i ->
        dataCells[i].isNotEmpty() && dataCells[i].count { isNumeric(it.text) } * 2 >= dataCells[i].size
    }
    // Column content width (not the neighbour-padded Column bounds): the actual span of a
    // column's own cell text, used to tell a QTY column from a whole-rupee RATE column below.
    fun contentWidth(i: Int): Int {
        val cells = dataCells[i]
        return if (cells.isEmpty()) 0 else cells.maxOf { it.xRight } - cells.minOf { it.xLeft }
    }
    if (roles.none { it == ColRole.QTY }) {
        // A whole-rupee RATE (e.g. "50") is also a small int, so a headerless RATE-before-QTY
        // layout can offer more than one small-int candidate. Quantities are 1–2 chars while
        // rates/amounts run wider, so among small-int candidates the narrowest one is QTY — not
        // simply the leftmost, which would mislabel a leading whole-number RATE as QTY. Ties
        // (including the common single-candidate case) resolve to the leftmost, as before.
        val smallIntIdx = remainingNumericIdx.filter { i -> dataCells[i].all { isSmallInt(it.text) } }
        val qtyIdx = smallIntIdx.minByOrNull { i -> contentWidth(i) }
        if (qtyIdx != null) roles[qtyIdx] = ColRole.QTY
    }
    if (roles.none { it == ColRole.RATE }) {
        val rateIdx = remainingNumericIdx.firstOrNull { i -> roles[i] == null }
        if (rateIdx != null) roles[rateIdx] = ColRole.RATE
    }

    return clusters.indices.map { i ->
        val (l, r) = bounds[i]
        Column(l, r, roles[i] ?: ColRole.OTHER)
    }
}

/** The cell in this row whose xCenter falls within [col]'s bounds, if any. */
fun Row.cellIn(col: Column): Cell? = cells.firstOrNull { it.xCenter in col.xLeft..col.xRight }
