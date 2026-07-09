package ai.schism.split.sms.receipt.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class BillParserItemsTest {
    private fun c(t: String, xL: Int, xR: Int, y: Int) = Cell(t, xL, xR, y)

    @Test fun nameRateQtyAmount_readsColumnsNotPositions() {
        // Anandha Bhavan: Name | Rate | Qty | Amount → ROAST 50 x3 = 150 (NOT qty 150, amount 50)
        val rows = listOf(
            Row(listOf(c("Name", 20, 80, 40), c("Rate", 200, 250, 40), c("Qty", 320, 360, 40), c("Amount", 430, 500, 40))),
            Row(listOf(c("ROAST", 20, 120, 80), c("50.00", 200, 260, 80), c("3", 330, 345, 80), c("150.00", 430, 500, 80))),
            Row(listOf(c("TEA", 20, 90, 120), c("25.00", 200, 260, 120), c("1", 330, 345, 120), c("25.00", 430, 500, 120))),
        )
        val cols = detectColumns(rows)
        val regions = segment(rows)
        val items = extractItems(regions, cols)
        assertEquals(listOf("ROAST", "TEA"), items.map { it.name })
        assertEquals(listOf(3, 1), items.map { it.qty })
        assertEquals(listOf(15000L, 2500L), items.map { it.amountMinor })
    }

    @Test fun descriptionQtyAmount_noRateColumn() {
        // GRT Bhopal: Description | Qty | Amount → Plain Papad x2 = 80
        val rows = listOf(
            Row(listOf(c("Description", 20, 160, 40), c("Qty", 300, 340, 40), c("Amount", 430, 520, 40))),
            Row(listOf(c("PLAIN PAPAD", 20, 200, 80), c("2", 310, 325, 80), c("80.00", 430, 500, 80))),
        )
        val items = extractItems(segment(rows), detectColumns(rows))
        assertEquals("PLAIN PAPAD", items[0].name)
        assertEquals(2, items[0].qty)
        assertEquals(8000L, items[0].amountMinor)
    }
}
