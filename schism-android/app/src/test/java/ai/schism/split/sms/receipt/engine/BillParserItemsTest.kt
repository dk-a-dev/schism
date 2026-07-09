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

    @Test fun skipsZeroAndNegativeAmountRows() {
        // A real priced item alongside a discount row (negative amount) and a stray zero-amount
        // row: only the real item should ever become a line item.
        val rows = listOf(
            Row(listOf(c("Description", 20, 160, 40), c("Amount", 430, 520, 40))),
            Row(listOf(c("PLAIN PAPAD", 20, 200, 80), c("80.00", 430, 500, 80))),
            Row(listOf(c("Discount", 20, 200, 120), c("-50.00", 430, 500, 120))),
            Row(listOf(c("Service Note", 20, 200, 160), c("0.00", 430, 500, 160))),
        )
        val items = extractItems(segment(rows), detectColumns(rows))
        assertEquals(listOf("PLAIN PAPAD"), items.map { it.name })
        assertEquals(listOf(8000L), items.map { it.amountMinor })
    }

    @Test fun qtyCandidateBoundedBySmallInt() {
        // QTY cell holds "1000" — a decimal-less 4-digit number (e.g. a total that lost its
        // decimal point) that arithmetically satisfies rate(1.00) * 1000 == amount(1000.00), but
        // 1000 is not a plausible quantity. It must never be accepted as qty — neither via the
        // naive per-column reading nor via the remap search — so the row falls back to trusting
        // the AMOUNT cell with qty defaulting to 1.
        val columns = listOf(
            Column(0, 100, ColRole.ITEM),
            Column(101, 200, ColRole.QTY),
            Column(201, 300, ColRole.RATE),
            Column(301, 400, ColRole.AMOUNT),
        )
        val row = Row(
            listOf(
                c("Mystery Item", 10, 90, 80),
                c("1000", 120, 160, 80),
                c("1.00", 220, 260, 80),
                c("1000.00", 320, 380, 80),
            )
        )
        val regions = Regions(header = emptyList(), items = listOf(row), totals = emptyList())
        val items = extractItems(regions, columns)
        assertEquals(1, items.size)
        assertEquals(1, items[0].qty)
        assertEquals(100000L, items[0].amountMinor)
    }

    @Test fun finalFallbackWhenNoPermutationReconciles() {
        // No {rate,qty,amount} assignment of "7.5" / "40.00" / "1000.00" satisfies the
        // rate*qty≈amount invariant (and "7.5" isn't even a valid qty candidate). The documented
        // fallback kicks in: trust the AMOUNT-labelled cell verbatim, defaulting qty to 1.
        val columns = listOf(
            Column(0, 100, ColRole.ITEM),
            Column(101, 200, ColRole.QTY),
            Column(201, 300, ColRole.RATE),
            Column(301, 400, ColRole.AMOUNT),
        )
        val row = Row(
            listOf(
                c("Odd Row", 10, 90, 80),
                c("7.5", 120, 160, 80),
                c("40.00", 220, 260, 80),
                c("1000.00", 320, 380, 80),
            )
        )
        val regions = Regions(header = emptyList(), items = listOf(row), totals = emptyList())
        val items = extractItems(regions, columns)
        assertEquals(1, items.size)
        assertEquals(1, items[0].qty)
        assertEquals(100000L, items[0].amountMinor)
    }

    @Test fun wrappedNameSurvivesInterveningGarbleRow() {
        // A wrapped item-name continuation ("Chicken"), then a garbled row whose AMOUNT-column
        // cell doesn't even parse as money ("N/A") — that row must be skipped WITHOUT discarding
        // the pending name prefix, so it still attaches to the next real priced row ("Tikka").
        val columns = listOf(
            Column(0, 100, ColRole.ITEM),
            Column(301, 400, ColRole.AMOUNT),
        )
        val rows = listOf(
            Row(listOf(c("Chicken", 10, 90, 40))),
            Row(listOf(c("N/A", 320, 380, 80))),
            Row(listOf(c("Tikka", 10, 90, 120), c("250.00", 320, 380, 120))),
        )
        val regions = Regions(header = emptyList(), items = rows, totals = emptyList())
        val items = extractItems(regions, columns)
        assertEquals(listOf("Chicken Tikka"), items.map { it.name })
        assertEquals(listOf(25000L), items.map { it.amountMinor })
    }

    @Test fun wrappedNameBelowPriceAttachesToPrecedingAnchor() {
        // Thermal layout: the numeric triple is on the FIRST line (no inline name) and the item name
        // wraps DOWNWARD across the following moneyless rows. Those following fragments must attach to
        // the just-emitted anchor above them, not the (non-existent) next item.
        val columns = listOf(
            Column(0, 100, ColRole.ITEM),
            Column(101, 200, ColRole.QTY),
            Column(201, 300, ColRole.RATE),
            Column(301, 400, ColRole.AMOUNT),
        )
        val rows = listOf(
            Row(listOf(c("2", 120, 160, 40), c("60.00", 220, 260, 40), c("120.00", 320, 380, 40))),
            Row(listOf(c("Masala", 10, 90, 80))),
            Row(listOf(c("Dosa", 10, 90, 120))),
        )
        val regions = Regions(header = emptyList(), items = rows, totals = emptyList())
        val items = extractItems(regions, columns)
        assertEquals(listOf("Masala Dosa"), items.map { it.name })
        assertEquals(listOf(2), items.map { it.qty })
        assertEquals(listOf(12000L), items.map { it.amountMinor })
        assertEquals(listOf(6000L), items.map { it.unitPriceMinor })
    }

    @Test fun qtyGreaterThanOneWithClosePriceAndAmountLocksAmountByInvariant() {
        // Price and Amount so close their columns merged into a single AMOUNT band (no RATE column).
        // With qty=3 and both money cells (149.00 price, 447.00 amount) present, the (rate, amount)
        // pair satisfying qty×rate=amount must be chosen — NOT the leftmost (price) cell.
        val columns = listOf(
            Column(0, 800, ColRole.ITEM),
            Column(820, 875, ColRole.QTY),
            Column(878, 960, ColRole.AMOUNT), // merged price+amount band, no RATE
        )
        val row = Row(
            listOf(
                c("Jumbo King Roll", 140, 520, 80),
                c("3", 833, 867, 80),
                c("149.00", 890, 924, 80),
                c("447.00", 926, 960, 80),
            ),
        )
        val regions = Regions(header = emptyList(), items = listOf(row), totals = emptyList())
        val items = extractItems(regions, columns)
        assertEquals(1, items.size)
        assertEquals(3, items[0].qty)
        assertEquals(44700L, items[0].amountMinor)
        assertEquals(14900L, items[0].unitPriceMinor)
    }
}
