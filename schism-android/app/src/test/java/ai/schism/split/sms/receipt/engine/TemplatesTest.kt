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

    // ---- regression coverage for the "bare MRP misroutes to BLINKIT" fix ----

    @Test fun groceryBillWithMrpKeywordNotCorrupted() {
        fun row(vararg t: String) = Row(t.mapIndexed { i, s -> Cell(s, i * 100, i * 100 + 80, 0) })
        val rows = listOf(
            row("Item", "Qty", "Rate"),
            row("Rice 1kg", "2", "55.00"),
            row("MRP inclusive of all taxes"),
        )
        // Bare "MRP" (no "blinkit"/"items in this order") must not route this ordinary grocery
        // bill to BLINKIT.
        val source = detectSource(rows)
        assertEquals(Source.PAPER, source)

        val out = applyTemplate(source, rows)
        val items = extractItems(segment(out), detectColumns(out))
        assertEquals(1, items.size)
        assertEquals(2, items[0].qty)
        assertEquals(11000L, items[0].amountMinor) // 2 x 55.00, not corrupted
    }

    @Test fun rateAmountPairNotCollapsed() {
        val rows = listOf(
            Row(
                listOf(
                    c("Rice 1kg", 20, 400, 80),
                    c("50.00", 480, 520, 80),
                    c("150.00", 540, 610, 80),
                ),
            ),
        )
        val out = applyTemplate(Source.BLINKIT, rows)
        // rate (50.00) <= amount (150.00): this is a normal Rate|Amount pair, not a struck MRP,
        // so nothing should be dropped.
        assertEquals(3, out[0].cells.size)
        val items = extractItems(Regions(emptyList(), out, emptyList()), detectColumns(out))
        assertEquals(15000L, items[0].amountMinor)
    }

    // ---- regression coverage for the "'Bill Details' alone misroutes to SWIGGY" fix ----

    @Test fun billDetailsPaperBillNotFoldedAsSwiggy() {
        val rows = listOf(
            Row(listOf(c("Bill Details", 20, 160, 20))),
            Row(
                listOf(
                    c("Item", 20, 140, 50), c("Qty", 160, 200, 50),
                    c("Rate", 220, 300, 50), c("Amount", 320, 400, 50),
                ),
            ),
            Row(
                listOf(
                    c("Manchow Soup", 20, 140, 80), c("2", 160, 200, 80),
                    c("159.00", 220, 300, 80), c("318.00", 320, 400, 80),
                ),
            ),
            Row(listOf(c("Extra Spicy", 40, 140, 110))),
            Row(
                listOf(
                    c("Butter Roti", 20, 140, 140), c("6", 160, 200, 140),
                    c("39.00", 220, 300, 140), c("234.00", 320, 400, 140),
                ),
            ),
            Row(listOf(c("Sub Total", 20, 160, 200), c("552.00", 320, 400, 200))),
            Row(listOf(c("Grand Total", 20, 160, 240), c("552.00", 320, 400, 240))),
        )
        // "Bill Details" alone, with no item-total/fee-structure signal, is a plain POS bill —
        // not a food-delivery receipt.
        val source = detectSource(rows)
        assertEquals(Source.PAPER, source)

        val out = applyTemplate(source, rows)
        val items = extractItems(segment(out), detectColumns(out))
        assertEquals(2, items.size)
        assertEquals("Manchow Soup", items[0].name)
        assertEquals("Extra Spicy Butter Roti", items[1].name)
    }

    // ---- regression coverage for foldOptionSublines swallowing a split totals-label row ----

    @Test fun splitTotalsLabelNotFolded() {
        val rows = listOf(
            Row(listOf(c("Bill Details", 20, 160, 20))),
            Row(
                listOf(
                    c("Crispy Peri Peri Chicken Rice Bowl (Regular) x1", 20, 500, 80),
                    c("319", 560, 610, 80),
                ),
            ),
            // A totals label wrapped onto its own line, ahead of its amount — must not be
            // mistaken for an item's option/customisation subline.
            Row(listOf(c("Grand Total", 40, 200, 110))),
            Row(listOf(c("339", 560, 610, 140))),
        )
        val out = applyTemplate(Source.SWIGGY, rows)
        val itemRow = out.first { it.cells.any { c -> c.text.contains("Crispy") } }
        assertEquals(false, itemRow.text.contains("Grand Total"))
        assertEquals(4, out.size)
    }

    // ---- regression coverage for a line item being mistaken for the merchant name ----

    @Test fun merchantIsNotALineItem() {
        fun row(vararg t: String) = Row(t.mapIndexed { i, s -> Cell(s, i * 100, i * 100 + 80, 0) })
        val rows = listOf(
            row("Item", "Amount"),
            row("Fresh Bread", "45.00"),
            row("Milk 1L", "60.00"),
            row("Sub Total", "105.00"),
            row("Grand Total", "105.00"),
        )
        // No genuine merchant preamble row exists; every remaining candidate is either a would-be
        // line item (trailing money cell) or a totals label, so the merchant must fall back.
        val draft = parseBill(rows)
        assertEquals("Receipt", draft?.merchant)
    }
}
