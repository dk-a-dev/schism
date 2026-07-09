package ai.schism.split.sms.receipt.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ColumnsTest {
    private fun c(t: String, xL: Int, xR: Int, y: Int) = Cell(t, xL, xR, y)

    /** Anandha Bhavan style: Name | Rate | Qty | Amount (RATE before QTY). */
    @Test fun assignsRolesFromHeaderRateQtyAmount() {
        val rows = listOf(
            Row(listOf(c("Name", 20, 80, 40), c("Rate", 200, 250, 40), c("Qty", 320, 360, 40), c("Amount", 420, 500, 40))),
            Row(listOf(c("ROAST", 20, 120, 80), c("50.00", 200, 260, 80), c("3", 330, 345, 80), c("150.00", 420, 490, 80))),
        )
        val cols = detectColumns(rows)
        val byRole = cols.associateBy { it.role }
        val roast = rows[1]
        assertEquals("ROAST", roast.cellIn(byRole.getValue(ColRole.ITEM))!!.text)
        assertEquals("50.00", roast.cellIn(byRole.getValue(ColRole.RATE))!!.text)
        assertEquals("3", roast.cellIn(byRole.getValue(ColRole.QTY))!!.text)
        assertEquals("150.00", roast.cellIn(byRole.getValue(ColRole.AMOUNT))!!.text)
    }

    /** Vraj style header: No. Item Qty. Price Amount → item + qty + rate + amount. */
    @Test fun assignsRolesFromHeaderItemQtyPriceAmount() {
        val rows = listOf(
            Row(listOf(c("Item", 40, 120, 40), c("Qty", 260, 300, 40), c("Price", 340, 400, 40), c("Amount", 460, 540, 40))),
            Row(listOf(c("Butter Roti", 40, 200, 80), c("6", 270, 285, 80), c("39.00", 340, 400, 80), c("234.00", 460, 530, 80))),
        )
        val byRole = detectColumns(rows).associateBy { it.role }
        val r = rows[1]
        assertEquals("6", r.cellIn(byRole.getValue(ColRole.QTY))!!.text)
        assertEquals("39.00", r.cellIn(byRole.getValue(ColRole.RATE))!!.text)
        assertEquals("234.00", r.cellIn(byRole.getValue(ColRole.AMOUNT))!!.text)
    }

    /**
     * Headerless layout, ITEM | RATE | QTY | AMOUNT, where RATE is a whole rupee amount ("50")
     * that — like QTY's "1" — passes isSmallInt. The structural fallback must not pick the
     * leftmost small-int column (RATE) as QTY: the narrower column (QTY's "1") wins.
     */
    @Test fun headerlessWholeNumberRateDoesNotSwapQtyRate() {
        val rows = listOf(
            Row(
                listOf(
                    c("GHEE PONGAL", 20, 160, 80),
                    c("50", 200, 260, 80),
                    c("1", 320, 340, 80),
                    c("50.00", 420, 490, 80),
                ),
            ),
        )
        val byRole = detectColumns(rows).associateBy { it.role }
        val r = rows[0]
        assertEquals("1", r.cellIn(byRole.getValue(ColRole.QTY))!!.text)
        assertEquals("50", r.cellIn(byRole.getValue(ColRole.RATE))!!.text)
    }

    /**
     * A first row of plain labels ("Particulars"/"Nos"/"Value") that misses the header keyword
     * threshold must still be excluded from the structural fallback's data rows — otherwise "Nos"
     * (non-numeric) breaks the QTY column's "all cells are small ints" check and that column ends
     * up unassigned (OTHER) instead of QTY.
     */
    @Test fun unrecognizedHeaderRowExcludedFromFallback() {
        val rows = listOf(
            Row(listOf(c("Particulars", 20, 150, 40), c("Nos", 300, 340, 40), c("Value", 420, 490, 40))),
            Row(listOf(c("IDLI", 20, 100, 80), c("2", 310, 330, 80), c("40.00", 420, 480, 80))),
            Row(listOf(c("DOSA", 20, 110, 120), c("1", 312, 328, 120), c("60.00", 420, 480, 120))),
        )
        val byRole = detectColumns(rows).associateBy { it.role }
        val idli = rows[1]
        val dosa = rows[2]
        assertEquals("IDLI", idli.cellIn(byRole.getValue(ColRole.ITEM))!!.text)
        assertEquals("2", idli.cellIn(byRole.getValue(ColRole.QTY))!!.text)
        assertEquals("40.00", idli.cellIn(byRole.getValue(ColRole.AMOUNT))!!.text)
        assertEquals("DOSA", dosa.cellIn(byRole.getValue(ColRole.ITEM))!!.text)
        assertEquals("1", dosa.cellIn(byRole.getValue(ColRole.QTY))!!.text)
        assertEquals("60.00", dosa.cellIn(byRole.getValue(ColRole.AMOUNT))!!.text)
    }
}
