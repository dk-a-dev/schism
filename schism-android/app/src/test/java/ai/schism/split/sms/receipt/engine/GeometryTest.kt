package ai.schism.split.sms.receipt.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class GeometryTest {
    private fun cell(text: String, xL: Int, xR: Int, y: Int) = Cell(text, xL, xR, y)

    @Test fun groupsCellsOnSameVisualRowAndSortsLeftToRight() {
        // Two rows; cells arrive out of order and out of column order.
        val cells = listOf(
            cell("318.00", 300, 360, 100), cell("Manchow Soup", 20, 160, 104), cell("2", 200, 215, 102),
            cell("Paneer", 20, 120, 160), cell("299.00", 300, 360, 158),
        )
        val rows = groupIntoRows(cells, lineHeight = 30)
        assertEquals(2, rows.size)
        assertEquals("Manchow Soup 2 318.00", rows[0].text)
        assertEquals("Paneer 299.00", rows[1].text)
        assertEquals(listOf(20, 200, 300), rows[0].cells.map { it.xLeft })
    }

    @Test fun separateRowsWhenVerticalGapExceedsThreshold() {
        val cells = listOf(cell("A", 0, 10, 100), cell("B", 0, 10, 140))
        assertEquals(2, groupIntoRows(cells, lineHeight = 30).size)
    }
}
