package ai.schism.split.sms.receipt.engine

import org.junit.Assert.*
import org.junit.Test

class RegionsTest {
    @Test fun parseMinorAcceptsMoneyRejectsPhone() {
        assertEquals(253200L, parseMinor("2532"))
        assertEquals(129900L, parseMinor("1,299.00"))
        assertEquals(4000L, parseMinor("₹40.00"))
        assertNull(parseMinor("9555713188"))   // phone: too many integer digits, no decimal
        assertNull(parseMinor("abc"))
    }

    @Test fun segmentSplitsItemsAtTotals() {
        fun row(vararg t: String) = Row(t.mapIndexed { i, s -> Cell(s, i * 100, i * 100 + 80, 0) })
        val rows = listOf(
            row("VRAJ RESTAURANT"),
            row("Item", "Qty", "Price", "Amount"),
            row("Manchow Soup", "2", "159.00", "318.00"),
            row("Butter Roti", "6", "39.00", "234.00"),
            row("Sub Total", "1249.00"),
            row("Grand Total", "1249.00"),
        )
        val r = segment(rows)
        assertEquals(2, r.items.size)
        assertEquals("Manchow Soup", r.items.first().cells.first().text)
        assertTrue(r.totals.any { it.text.contains("Grand Total") })
    }
}
