package ai.schism.split.sms.receipt.engine

import ai.schism.split.sms.receipt.ReceiptLineItem
import org.junit.Assert.*
import org.junit.Test

class SolverTest {
    private val anandha = listOf(
        ReceiptLineItem("Ghee Pongal", 5000, 1), ReceiptLineItem("Vadai", 6000, 3),
        ReceiptLineItem("Roast", 15000, 3), ReceiptLineItem("Poori Masal", 10000, 2),
        ReceiptLineItem("Tea", 2500, 1),
    ) // sum 38500

    @Test fun reconcileVerifiesWhenItemsMatchSubtotalAndTotal() {
        val totals = Totals(subtotal = 38500, tax = 1926 /*9.63+9.63*/, fees = 0, discount = 26 /*round -0.26*/, grandTotal = 40400)
        val v = reconcile(anandha, totals)
        assertTrue(v.verified)
        assertEquals(40400, v.grandTotal)
        assertEquals(1900, v.tax + v.fees - v.discount) // charge pot ≈ 1926-26 = 1900
    }

    @Test fun groceryMultiSlabGstSumsAllTaxRows() {
        // CGST2.5+SGST2.5+CGST9+SGST9 = 4.70+4.70+49.50+49.50 = 108.40
        val totals = Totals(subtotal = 73800, tax = 10840, fees = 0, discount = 40, grandTotal = 84600)
        val items = listOf(ReceiptLineItem("Basmati Rice", 13600, 2), ReceiptLineItem("Whole Wheat Bread", 5200, 1),
            ReceiptLineItem("Soft Drink", 33000, 3), ReceiptLineItem("Face Wash", 22000, 1)) // 73800
        val v = reconcile(items, totals)
        assertTrue(v.verified)
        assertEquals(84600, v.grandTotal)
    }

    // --- reconcile: additional arithmetic-only coverage (no specific merchant/fixture assumed) ---

    @Test fun reconcileFailsWhenItemsDoNotMatchSubtotal() {
        val items = listOf(ReceiptLineItem("Widget", 1000, 1))
        val totals = Totals(subtotal = 5000, tax = 0, fees = 0, discount = 0, grandTotal = 5000)
        val v = reconcile(items, totals)
        assertFalse(v.verified)
    }

    @Test fun reconcileFailsWhenGrandTotalArithmeticIsOff() {
        val items = listOf(ReceiptLineItem("Widget", 5000, 1))
        val totals = Totals(subtotal = 5000, tax = 100, fees = 0, discount = 0, grandTotal = 9999)
        val v = reconcile(items, totals)
        assertFalse(v.verified)
    }

    @Test fun reconcileDerivesMissingSubtotalFromItemSum() {
        val items = listOf(ReceiptLineItem("Widget", 3000, 1), ReceiptLineItem("Gadget", 2000, 1))
        val totals = Totals(subtotal = null, tax = 500, fees = 0, discount = 0, grandTotal = 5500)
        val v = reconcile(items, totals)
        assertTrue(v.verified)
        assertEquals(5000, v.subtotal)
    }

    @Test fun reconcileDerivesMissingGrandTotalFromSubtotalTaxFeesDiscount() {
        val items = listOf(ReceiptLineItem("Widget", 3000, 1))
        val totals = Totals(subtotal = 3000, tax = 300, fees = 100, discount = 50, grandTotal = null)
        val v = reconcile(items, totals)
        assertTrue(v.verified)
        assertEquals(3350, v.grandTotal)
    }

    @Test fun reconcileToleratesSmallRounding() {
        val items = listOf(ReceiptLineItem("Widget", 10000, 1))
        // itemSum 10000 vs subtotal 10001 (1 minor unit off) — within max(1%, 200) tolerance.
        val totals = Totals(subtotal = 10001, tax = 0, fees = 0, discount = 0, grandTotal = 10001)
        val v = reconcile(items, totals)
        assertTrue(v.verified)
    }

    // --- readTotals: generic keyword classification, deliberately using a different merchant/shape
    // than the fixtures above (a plain retail bill) to guard against value/merchant overfitting. ---

    private fun cell(text: String, xLeft: Int) = Cell(text, xLeft, xLeft + 80, 0)
    private fun row(vararg pairs: Pair<String, Int>) = Row(pairs.map { (t, x) -> cell(t, x) })
    private fun regionsOf(totalsRows: List<Row>) = Regions(header = emptyList(), items = emptyList(), totals = totalsRows)

    @Test fun readTotalsClassifiesSubtotalTaxFeesDiscountAndGrandTotal() {
        val totals = regionsOf(
            listOf(
                row("Sub Total" to 0, "500.00" to 200),
                row("CGST 9%" to 0, "45.00" to 200),
                row("SGST 9%" to 0, "45.00" to 200),
                row("Delivery Charge" to 0, "20.00" to 200),
                row("Discount" to 0, "10.00" to 200),
                row("Grand Total" to 0, "600.00" to 200),
            ),
        )
        val t = readTotals(totals)
        assertEquals(50000L, t.subtotal)
        assertEquals(9000L, t.tax) // 45.00 + 45.00
        assertEquals(2000L, t.fees)
        assertEquals(1000L, t.discount)
        assertEquals(60000L, t.grandTotal)
    }

    @Test fun readTotalsCombinedGstRowWinsOverCgstSgstSplit() {
        val totals = regionsOf(
            listOf(
                row("Sub Total" to 0, "1000.00" to 200),
                row("CGST" to 0, "45.00" to 200),
                row("SGST" to 0, "45.00" to 200),
                row("GST" to 0, "90.00" to 200), // combined row should win, not 45+45+90
                row("Bill Amount" to 0, "1090.00" to 200),
            ),
        )
        val t = readTotals(totals)
        assertEquals(9000L, t.tax) // combined GST row (90.00) only, not 45+45+90
        assertEquals(109000L, t.grandTotal)
    }

    @Test fun readTotalsFreeFeeRowContributesZero() {
        val totals = regionsOf(
            listOf(
                row("Sub Total" to 0, "300.00" to 200),
                row("Packaging Charge" to 0, "FREE" to 200),
                row("Amount Payable" to 0, "300.00" to 200),
            ),
        )
        val t = readTotals(totals)
        assertEquals(0L, t.fees)
        assertEquals(30000L, t.grandTotal)
    }

    @Test fun readTotalsGrandTotalPicksLargestAmongLabelledRows() {
        // A bill can show a pre-round "Bill Amount" alongside a post-round "Rounded Total" — both
        // are grand-total-labelled, and the largest one is the one to trust.
        val totals = regionsOf(
            listOf(
                row("Sub Total" to 0, "999.60" to 200),
                row("Bill Amount" to 0, "999.60" to 200),
                row("Rounded Total" to 0, "1000.00" to 200),
            ),
        )
        val t = readTotals(totals)
        assertEquals(100000L, t.grandTotal)
    }

    // --- readTotals × reconcile: round-off sign, discount sign, and no-anchor regression coverage
    // (all built from Rows so the label/sign parsing itself is exercised, not just Totals values). ---

    @Test fun roundOffNegativeRowStillVerifies() {
        // A negative "Round Off" row must be applied with its real (negative) sign, not folded into
        // discount (which would double-subtract it) or dropped. Chosen so a wrong sign puts the
        // grand-total invariant outside tolerance (max(1%, ₹2)), making this red pre-fix.
        val items = listOf(ReceiptLineItem("Widget", 10000, 1)) // ₹100.00
        val totalsRegion = regionsOf(
            listOf(
                row("Sub Total" to 0, "100.00" to 200),
                row("Tax" to 0, "10.00" to 200),
                row("Round Off" to 0, "-1.50" to 200),
                row("Grand Total" to 0, "108.50" to 200), // 100 + 10 - 1.50
            ),
        )
        val totals = readTotals(totalsRegion)
        val v = reconcile(items, totals)
        assertTrue(v.verified)
        assertEquals(10850L, v.grandTotal)
    }

    @Test fun explicitMinusDiscountReducesTotal() {
        // "Discount -50.00" must reduce the total (a discount is always a reduction), not add to it
        // just because the row happened to print a leading minus.
        val items = listOf(ReceiptLineItem("Widget", 100000, 1)) // ₹1000.00
        val totalsRegion = regionsOf(
            listOf(
                row("Sub Total" to 0, "1000.00" to 200),
                row("Discount" to 0, "-50.00" to 200),
                row("Grand Total" to 0, "950.00" to 200),
            ),
        )
        val totals = readTotals(totalsRegion)
        val v = reconcile(items, totals)
        assertTrue(v.verified)
        assertEquals(95000L, v.grandTotal)
        assertEquals(-5000L, v.tax + v.fees - v.discount + v.roundoff) // charge pot: -₹50
    }

    @Test fun noAnchorsIsNotVerified() {
        // No subtotal row and no grand-total row anywhere in the totals region: there is no
        // independently-read anchor to check the arithmetic against, so this must not verify even
        // though every derived value trivially agrees with itself.
        val items = listOf(ReceiptLineItem("Widget", 10000, 1))
        val totalsRegion = regionsOf(listOf(row("CGST" to 0, "50.00" to 200)))
        val totals = readTotals(totalsRegion)
        val v = reconcile(items, totals)
        assertFalse(v.verified)
    }

    // --- Fix #3: reconcile as CORRECTOR (single-outlier rescale, qty shortfall, %-token totals) ---

    @Test fun reconcileRescalesSingleDroppedDecimalOutlier() {
        // OCR dropped the decimal on one line: a "99.00" (9900 minor) item was read as "9900" whole
        // rupees → 990000 minor, 100× too large. Dividing exactly that one item by 100 makes the item
        // sum match the known subtotal, so the corrector rescales it and the bill then verifies.
        val items = listOf(
            ReceiptLineItem("Egg Roll", amountMinor = 14900, qty = 1, unitPriceMinor = 14900),
            ReceiptLineItem("Parotta", amountMinor = 990000, qty = 1, unitPriceMinor = 990000),
        )
        val totals = Totals(subtotal = 24800, tax = 0, fees = 0, discount = 0, grandTotal = 24800)
        val v = reconcile(items, totals)
        assertEquals(9900L, v.items[1].amountMinor)
        assertEquals(9900L, v.items[1].unitPriceMinor)
        assertEquals(24800L, v.items.sumOf { it.amountMinor })
        assertTrue("verified becomes true after the dropped-decimal correction", v.verified)
    }

    @Test fun reconcileDoesNotRescaleAnAlreadyConsistentBill() {
        // Items already sum to subtotal — the corrector must leave every amount untouched.
        val totals = Totals(subtotal = 38500, tax = 0, fees = 0, discount = 0, grandTotal = 38500)
        val v = reconcile(anandha, totals)
        assertEquals(anandha.map { it.amountMinor }, v.items.map { it.amountMinor })
        assertTrue(v.verified)
    }

    @Test fun reconcileFlagsQtyShortfallAgainstTotalQty() {
        // The two items sum exactly to subtotal and the grand-total arithmetic holds, but the printed
        // "Total Qty" is 3 while the items account for only 2 units — a row was missed. The qty
        // cross-check must refuse to mark this bill verified.
        val items = listOf(ReceiptLineItem("A", 5000, 1), ReceiptLineItem("B", 5000, 1))
        val totals = Totals(subtotal = 10000, tax = 0, fees = 0, discount = 0, grandTotal = 10000, totalQty = 3)
        val v = reconcile(items, totals)
        assertFalse("undercounted units (Σqty=2 vs Total Qty=3) must not verify", v.verified)
    }

    @Test fun readTotalsCapturesGstAmountDespitePercentToken() {
        // "SGST 2.5% 23.60": the "2.5%" percentage token must be ignored and the 23.60 amount read.
        val totals = regionsOf(
            listOf(
                row("Sub Total" to 0, "944.00" to 400),
                row("SGST" to 0, "2.5%" to 200, "23.60" to 400),
                row("CGST" to 0, "2.5%" to 200, "23.60" to 400),
                row("Grand Total" to 0, "991.00" to 400),
            ),
        )
        val t = readTotals(totals)
        assertEquals(4720L, t.tax) // 23.60 + 23.60, not the 2.5% tokens
        assertEquals(94400L, t.subtotal)
    }

    @Test fun readTotalsParsesTotalQtyAsUnitsNotMoney() {
        val totals = regionsOf(
            listOf(
                row("Total Qty:" to 0, "10" to 200),
                row("Sub Total" to 0, "944.00" to 200),
                row("Grand Total" to 0, "944.00" to 200),
            ),
        )
        val t = readTotals(totals)
        assertEquals(10, t.totalQty)
        assertEquals(94400L, t.grandTotal) // the "10" must not leak in as a money total
    }
}
