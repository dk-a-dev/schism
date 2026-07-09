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

    @Test fun itemNamedWithTotalIsNotABoundary() {
        fun row(vararg t: String) = Row(t.mapIndexed { i, s -> Cell(s, i * 100, i * 100 + 80, 0) })
        val rows = listOf(
            row("VRAJ RESTAURANT"),
            row("Item", "Qty", "Price", "Amount"),
            row("Total Wrap", "1", "150.00", "150.00"),
            row("Butter Roti", "6", "39.00", "234.00"),
            row("Sub Total", "384.00"),
            row("Grand Total", "384.00"),
        )
        val r = segment(rows)
        assertEquals(2, r.items.size)
        assertTrue(r.items.any { it.cells.first().text == "Total Wrap" })
        assertTrue(r.totals.any { it.text.contains("Sub Total") })
        assertTrue(r.totals.any { it.text.contains("Grand Total") })
    }

    @Test fun headerlessDatePreambleNotTreatedAsItem() {
        fun row(vararg t: String) = Row(t.mapIndexed { i, s -> Cell(s, i * 100, i * 100 + 80, 0) })
        val rows = listOf(
            row("VRAJ RESTAURANT"),
            row("Date:", "9/7/26"),
            row("Manchow Soup", "159.00"),
            row("Butter Roti", "39.00"),
            row("Sub Total", "198.00"),
        )
        val r = segment(rows)
        assertTrue(r.header.any { it.text.contains("9/7/26") })
        assertTrue(r.items.none { it.text.contains("9/7/26") })
        assertEquals("Manchow Soup", r.items.first().cells.first().text)
        assertEquals(2, r.items.size)
    }
}
