package ai.schism.split.sms.receipt.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class TemplatesTest {
    private fun c(t: String, xL: Int, xR: Int, y: Int) = Cell(t, xL, xR, y)

    @Test fun swiggyOptionSublineFoldsIntoItemName() {
        val rows = listOf(
            Row(listOf(c("Bill Details", 20, 160, 20))),
            Row(listOf(c("Crispy Peri Peri Chicken Rice Bowl (Regular) x1", 20, 500, 80), c("319", 560, 610, 80))),
            Row(listOf(c("Cilantro Lime Rice", 40, 260, 110))),
            Row(listOf(c("Item Total", 20, 160, 160), c("657", 560, 610, 160))),
        )
        val out = applyTemplate(Source.SWIGGY, rows)
        // the option row is folded; the item keeps qty 1 and amount 319
        val items = extractItems(segment(out), detectColumns(out))
        assertEquals(1, items.size)
        assertEquals(31900L, items[0].amountMinor)
        assertEquals(1, items[0].qty)
    }

    @Test fun blinkitStrikethroughTakesPaidPrice() {
        val rows = listOf(
            Row(listOf(c("Uncle Chipps Spicy Treat Potato Chips - Pack of 2", 20, 400, 80),
                       c("96", 480, 520, 80), c("72", 540, 580, 80))),
        )
        val out = applyTemplate(Source.BLINKIT, rows)
        val items = extractItems(Regions(emptyList(), out, emptyList()), detectColumns(out))
        assertEquals(7200L, items[0].amountMinor) // paid 72, not MRP 96
    }

    @Test fun parseBillAssemblesPaperReceipt() {
        fun row(vararg t: String) = Row(t.mapIndexed { i, s -> Cell(s, i * 100, i * 100 + 80, 0) })
        val rows = listOf(
            row("VRAJ RESTAURANT"),
            row("Item", "Qty", "Price", "Amount"),
            row("Manchow Soup", "2", "159.00", "318.00"),
            row("Butter Roti", "6", "39.00", "234.00"),
            row("Sub Total", "552.00"),
            row("Grand Total", "552.00"),
        )
        val draft = parseBill(rows)
        assertEquals("VRAJ RESTAURANT", draft?.merchant)
        assertEquals(55200L, draft?.totalMinor)
        assertEquals(2, draft?.lineItems?.size)
        assertEquals(true, draft?.verified)
    }

    @Test fun parseBillAssemblesSwiggyReceipt() {
        val rows = listOf(
            Row(listOf(c("Bill Details", 20, 160, 20))),
            Row(listOf(c("Crispy Peri Peri Chicken Rice Bowl (Regular) x1", 20, 500, 80), c("319", 560, 610, 80))),
            Row(listOf(c("Cilantro Lime Rice", 40, 260, 110))),
            Row(listOf(c("Item Total", 20, 160, 160), c("319", 560, 610, 160))),
            Row(listOf(c("Restaurant Packaging Charges", 20, 300, 200), c("20", 560, 610, 200))),
            Row(listOf(c("Grand Total", 20, 160, 240), c("339", 560, 610, 240))),
        )
        val draft = parseBill(rows)
        assertEquals(1, draft?.lineItems?.size)
        assertEquals(31900L, draft?.lineItems?.get(0)?.amountMinor)
        assertEquals(33900L, draft?.totalMinor)
        assertEquals(2000L, draft?.feesMinor)
    }

    @Test fun parseBillReturnsNullWhenNothingUsable() {
        val rows = listOf(Row(listOf(c("random unrelated noise", 20, 160, 20))))
        assertEquals(null, parseBill(rows))
    }
}
