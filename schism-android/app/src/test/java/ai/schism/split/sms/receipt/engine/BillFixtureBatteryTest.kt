package ai.schism.split.sms.receipt.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BRUTAL, DIVERSE fixture battery proving the deterministic bill engine GENERALISES (no overfitting).
 * Each fixture is a compact `text|xLeft|xRight|yCenter` cell dump (blank line = new visual row),
 * encoded as ML Kit would emit it, run through [parseBill]. See the battery spec
 * (docs/superpowers/specs/2026-07-10-bill-fixture-battery.md) for the diversity matrix.
 *
 * These fixtures test BEHAVIOUR on many bill shapes — the deliverable is the general engine, never
 * these specific numbers. No fixture value or sample string is ever referenced by the engine code.
 */
class BillFixtureBatteryTest {

    /** Parse a `text|xLeft|xRight|yCenter` dump (blank line separates rows) into engine [Row]s. */
    private fun rowsOf(dump: String): List<Row> =
        dump.trimIndent().split("\n\n").map { block ->
            Row(
                block.lines().filter { it.isNotBlank() }.map { line ->
                    val (t, xl, xr, y) = line.split("|")
                    Cell(t, xl.trim().toInt(), xr.trim().toInt(), y.trim().toInt())
                },
            )
        }

    // ======================================================================================
    // Group A — Column layouts
    // ======================================================================================

    /**
     * 5-col `No. | Item | Qty | Rate | Amount` with a single GST line. Exercises: leading serial
     * column (must not be read as qty), explicit rate column, single-tax totals.
     */
    @Test fun fiveCol_serialItemQtyRateAmount_singleGst() {
        val draft = parseBill(
            rowsOf(
                """
                ANNAPURNA MESS|60|360|20

                No.Item|20|180|90
                Qty|300|340|90
                Rate|380|440|90
                Amount|480|560|90

                1|20|35|140
                Veg Biryani|60|240|140
                2|310|330|140
                180.00|380|440|140
                360.00|480|560|140

                2|20|35|180
                Paneer Butter Masala|60|300|180
                1|310|330|180
                240.00|380|440|180
                240.00|480|560|180

                3|20|35|220
                Tandoori Roti|60|250|220
                4|310|330|220
                30.00|380|440|220
                120.00|480|560|220

                Sub Total|300|455|280
                720.00|480|560|280

                GST 5%|60|300|320
                36.00|480|560|320

                Grand Total|60|300|360
                756.00|480|560|360
                """,
            ),
        )!!

        assertEquals(listOf("Veg Biryani", "Paneer Butter Masala", "Tandoori Roti"), draft.lineItems.map { it.name })
        assertEquals(listOf(2, 1, 4), draft.lineItems.map { it.qty })
        assertEquals(listOf(18000L, 24000L, 3000L), draft.lineItems.map { it.unitPriceMinor })
        assertEquals(listOf(36000L, 24000L, 12000L), draft.lineItems.map { it.amountMinor })
        assertEquals(72000L, draft.subtotalMinor)
        assertEquals(3600L, draft.taxMinor)
        assertEquals(75600L, draft.totalMinor)
        assertTrue(draft.verified)
    }

    /**
     * 4-col `Item | Qty | Rate | Amount` with a split SGST + CGST tax pair and a positive round-off.
     */
    @Test fun fourCol_itemQtyRateAmount_sgstCgstRoundoff() {
        val draft = parseBill(
            rowsOf(
                """
                SPICE GARDEN|60|360|20

                Item|60|140|90
                Qty|300|340|90
                Rate|380|440|90
                Amount|480|560|90

                Chicken Curry|60|260|140
                2|310|330|140
                160.00|380|440|140
                320.00|480|560|140

                Jeera Rice|60|230|180
                1|310|330|180
                90.00|380|440|180
                90.00|480|560|180

                Sub Total|300|455|240
                410.00|480|560|240

                SGST 2.5%|60|300|280
                10.25|480|560|280

                CGST 2.5%|60|300|320
                10.25|480|560|320

                Round Off|60|300|360
                0.30|480|560|360

                Grand Total|60|300|400
                430.80|480|560|400
                """,
            ),
        )!!

        assertEquals(listOf("Chicken Curry", "Jeera Rice"), draft.lineItems.map { it.name })
        assertEquals(listOf(2, 1), draft.lineItems.map { it.qty })
        assertEquals(listOf(32000L, 9000L), draft.lineItems.map { it.amountMinor })
        assertEquals(41000L, draft.subtotalMinor)
        assertEquals(43080L, draft.totalMinor)
        assertTrue(draft.verified)
        // charge pot = SGST 10.25 + CGST 10.25 + round-off +0.30 = 20.80
        assertEquals(2080L, draft.taxMinor)
    }

    /**
     * `Item | Amount` (2-col, no qty column). Qty defaults to 1; unit price == amount. Uses a bare
     * "Total" grand-total label.
     */
    @Test fun twoCol_itemAmount_qtyDefaultsOne() {
        val draft = parseBill(
            rowsOf(
                """
                QUICK BITES|60|360|20

                Item|60|140|90
                Amount|480|560|90

                Samosa|60|200|140
                40.00|480|560|140

                Masala Chai|60|230|180
                20.00|480|560|180

                Total|60|300|240
                60.00|480|560|240
                """,
            ),
        )!!

        assertEquals(listOf("Samosa", "Masala Chai"), draft.lineItems.map { it.name })
        assertEquals(listOf(1, 1), draft.lineItems.map { it.qty })
        assertEquals(listOf(4000L, 2000L), draft.lineItems.map { it.amountMinor })
        assertEquals(4000L, draft.lineItems[0].unitPriceMinor)
        assertEquals(6000L, draft.totalMinor)
        assertTrue(draft.verified)
    }

    /**
     * Qty-FIRST layout `Qty | Item | Rate | Amount` — the item name sits to the RIGHT of the leftmost
     * (qty) column, so a purely positional "name is left of the numbers" reading would drop it. The
     * name-by-exclusion rule must still recover it.
     */
    @Test fun qtyFirst_qtyItemRateAmount() {
        val draft = parseBill(
            rowsOf(
                """
                SARAVANA TIFFIN|60|360|20

                Qty|20|60|90
                Item|100|200|90
                Rate|380|440|90
                Amount|480|560|90

                2|25|45|140
                Idli|100|180|140
                40.00|380|440|140
                80.00|480|560|140

                3|25|45|180
                Medu Vada|100|240|180
                30.00|380|440|180
                90.00|480|560|180

                Sub Total|300|455|240
                170.00|480|560|240

                Total|60|300|280
                170.00|480|560|280
                """,
            ),
        )!!

        assertEquals(listOf("Idli", "Medu Vada"), draft.lineItems.map { it.name })
        assertEquals(listOf(2, 3), draft.lineItems.map { it.qty })
        assertEquals(listOf(4000L, 3000L), draft.lineItems.map { it.unitPriceMinor })
        assertEquals(listOf(8000L, 9000L), draft.lineItems.map { it.amountMinor })
        assertEquals(17000L, draft.subtotalMinor)
        assertEquals(17000L, draft.totalMinor)
        assertTrue(draft.verified)
    }

    /**
     * Rate/Amount SWAPPED order: the Amount money column is printed LEFT of the Rate column (labelled
     * headers keep their meaning). The engine reads by column role and the qty×rate=amount invariant,
     * so the true line amount (80.00) is recovered even though it sits left of the rate.
     */
    @Test fun rateAmountSwappedOrder_amountLeftOfRate() {
        val draft = parseBill(
            rowsOf(
                """
                DOSA CORNER|60|360|20

                Item|60|140|90
                Qty|300|340|90
                Amount|380|440|90
                Rate|480|540|90

                Plain Dosa|60|230|140
                2|310|330|140
                80.00|380|440|140
                40.00|480|540|140

                Onion Uttapam|60|260|180
                1|310|330|180
                70.00|380|440|180
                70.00|480|540|180

                Sub Total|300|455|240
                150.00|380|460|240

                Total|60|300|280
                150.00|380|460|280
                """,
            ),
        )!!

        assertEquals(listOf("Plain Dosa", "Onion Uttapam"), draft.lineItems.map { it.name })
        assertEquals(listOf(2, 1), draft.lineItems.map { it.qty })
        assertEquals(listOf(4000L, 7000L), draft.lineItems.map { it.unitPriceMinor })
        assertEquals(listOf(8000L, 7000L), draft.lineItems.map { it.amountMinor })
        assertEquals(15000L, draft.subtotalMinor)
        assertTrue(draft.verified)
    }

    /**
     * `Item | Qty | Amount` (no rate column). The unit price is not independently printed, so it can
     * only be DERIVED as amount/qty — asserted as such, not as an independently-read value.
     */
    @Test fun itemQtyAmount_noRateColumn() {
        val draft = parseBill(
            rowsOf(
                """
                CHAAT BHANDAR|60|360|20

                Item|60|140|90
                Qty|300|340|90
                Amount|480|560|90

                Pani Puri|60|220|140
                2|310|330|140
                60.00|480|560|140

                Bhel Puri|60|230|180
                1|310|330|180
                50.00|480|560|180

                Sub Total|300|455|240
                110.00|480|560|240

                Grand Total|60|300|280
                110.00|480|560|280
                """,
            ),
        )!!

        assertEquals(listOf("Pani Puri", "Bhel Puri"), draft.lineItems.map { it.name })
        assertEquals(listOf(2, 1), draft.lineItems.map { it.qty })
        assertEquals(listOf(6000L, 5000L), draft.lineItems.map { it.amountMinor })
        // No rate column: unit price is DERIVED (amount/qty), not read off the bill.
        assertEquals(listOf(3000L, 5000L), draft.lineItems.map { it.unitPriceMinor })
        assertEquals(11000L, draft.subtotalMinor)
        assertTrue(draft.verified)
    }

    // ======================================================================================
    // Group B — Fused / abbreviated / absent headers   +   Group C — colliding money columns
    // ======================================================================================

    /**
     * Fused `RateAmount` header (no gap → one OCR token matching no \b-anchored keyword) over two
     * money columns printed so close the clusterer merges them. Interleaves equal rate==amount rows
     * (qty 1) with qty>1 rows in the SAME bill, plus a Total Qty cross-check. The engine must split
     * the fused money band and pick the right amount per row via qty×rate=amount.
     */
    @Test fun fusedRateAmountHeader_equalAndMultiQtyInterleaved() {
        val draft = parseBill(
            rowsOf(
                """
                CORNER CAFE|140|420|20

                Item|140|300|90
                Qty|833|867|90
                RateAmount|890|960|90

                Masala Tea|140|300|140
                1|833|867|140
                20.00|890|924|140
                20.00|926|960|140

                Veg Sandwich|140|320|180
                3|833|867|180
                60.00|890|924|180
                180.00|926|960|180

                Cold Coffee|140|300|220
                1|833|867|220
                90.00|890|924|220
                90.00|926|960|220

                Masala Fries|140|320|260
                2|833|867|260
                70.00|890|924|260
                140.00|926|960|260

                Total Qty: 7|140|360|320

                Sub Total|140|300|360
                430.00|860|960|360

                Grand Total|140|340|400
                430.00|860|960|400
                """,
            ),
        )!!

        assertEquals(listOf("Masala Tea", "Veg Sandwich", "Cold Coffee", "Masala Fries"), draft.lineItems.map { it.name })
        assertEquals(listOf(1, 3, 1, 2), draft.lineItems.map { it.qty })
        assertEquals(listOf(2000L, 6000L, 9000L, 7000L), draft.lineItems.map { it.unitPriceMinor })
        assertEquals(listOf(2000L, 18000L, 9000L, 14000L), draft.lineItems.map { it.amountMinor })
        assertEquals(43000L, draft.subtotalMinor)
        assertEquals(43000L, draft.totalMinor)
        assertEquals(7, draft.lineItems.sumOf { it.qty })
        assertTrue(draft.verified)
    }

    /**
     * Abbreviated ALL-CAPS headers `QTY | RATE | AMT` (no fusion, distinct columns). Exercises the
     * `amt` amount-keyword abbreviation and all-caps header matching.
     */
    @Test fun abbreviatedAllCapsHeaders_qtyRateAmt() {
        val draft = parseBill(
            rowsOf(
                """
                HIGHWAY DHABA|60|360|20

                ITEM|60|140|90
                QTY|300|340|90
                RATE|380|440|90
                AMT|480|560|90

                Dal Fry|60|220|140
                2|310|330|140
                110.00|380|440|140
                220.00|480|560|140

                Butter Naan|60|250|180
                3|310|330|180
                40.00|380|440|180
                120.00|480|560|180

                Sub Total|300|455|240
                340.00|480|560|240

                Grand Total|60|300|280
                340.00|480|560|280
                """,
            ),
        )!!

        assertEquals(listOf("Dal Fry", "Butter Naan"), draft.lineItems.map { it.name })
        assertEquals(listOf(2, 3), draft.lineItems.map { it.qty })
        assertEquals(listOf(22000L, 12000L), draft.lineItems.map { it.amountMinor })
        assertEquals(34000L, draft.subtotalMinor)
        assertTrue(draft.verified)
    }

    /**
     * NO header row at all — purely structural column detection. `Item | Qty | Rate | Amount` shape
     * must be inferred from the data cells alone (leftmost letters → ITEM, narrow small-int → QTY,
     * rightmost numeric → AMOUNT, remaining numeric → RATE).
     */
    @Test fun noHeaderRow_structuralColumnsOnly() {
        val draft = parseBill(
            rowsOf(
                """
                ROADSIDE EATS|60|360|20

                Veg Fried Rice|60|260|140
                2|310|330|140
                120.00|380|440|140
                240.00|480|560|140

                Gobi Manchurian|60|280|180
                1|310|330|180
                150.00|380|440|180
                150.00|480|560|180

                Sub Total|300|455|240
                390.00|480|560|240

                Grand Total|60|300|280
                390.00|480|560|280
                """,
            ),
        )!!

        assertEquals(listOf("Veg Fried Rice", "Gobi Manchurian"), draft.lineItems.map { it.name })
        assertEquals(listOf(2, 1), draft.lineItems.map { it.qty })
        assertEquals(listOf(24000L, 15000L), draft.lineItems.map { it.amountMinor })
        assertEquals(listOf(12000L, 15000L), draft.lineItems.map { it.unitPriceMinor })
        assertEquals(39000L, draft.subtotalMinor)
        assertTrue(draft.verified)
    }

    // ======================================================================================
    // Group D — Multi-line item names
    // ======================================================================================

    /**
     * Names wrap UPWARD: the numeric triple sits on the LAST line of each item and the name fragments
     * are the moneyless rows ABOVE it (attached as a prefix to the anchor below them).
     */
    @Test fun multiLineNames_wrapUpward_numericOnLastLine() {
        val draft = parseBill(
            rowsOf(
                """
                WOK EXPRESS|60|360|20

                Item|60|140|60
                Qty|300|340|60
                Rate|380|440|60
                Amount|480|560|60

                Chicken|60|180|100
                Hakka Noodles|60|280|130
                1|310|330|160
                160.00|380|440|160
                160.00|480|560|160

                Schezwan|60|200|320
                Fried Rice|60|260|350
                2|310|330|380
                130.00|380|440|380
                260.00|480|560|380

                Sub Total|300|455|440
                420.00|480|560|440

                Grand Total|60|300|480
                420.00|480|560|480
                """,
            ),
        )!!

        assertEquals(listOf("Chicken Hakka Noodles", "Schezwan Fried Rice"), draft.lineItems.map { it.name })
        assertEquals(listOf(1, 2), draft.lineItems.map { it.qty })
        assertEquals(listOf(16000L, 26000L), draft.lineItems.map { it.amountMinor })
        assertEquals(42000L, draft.subtotalMinor)
        assertTrue(draft.verified)
    }

    /**
     * A 4-line item name wrapping DOWNWARD (thermal): the numeric triple + first name word on the
     * first line, three name fragments on the following moneyless rows (attached as a suffix),
     * bounded by the next item's anchor.
     */
    @Test fun multiLineNames_wrapDownwardFourLines() {
        val draft = parseBill(
            rowsOf(
                """
                TANDOOR HOUSE|60|360|20

                Item|60|140|60
                Qty|300|340|60
                Rate|380|440|60
                Amount|480|560|60

                Royal|60|160|100
                1|310|330|100
                320.00|380|440|100
                320.00|480|560|100

                Butter|60|160|130

                Chicken|60|180|160

                Masala|60|170|190

                Garlic Naan|60|230|350
                3|310|330|350
                45.00|380|440|350
                135.00|480|560|350

                Sub Total|300|455|420
                455.00|480|560|420

                Grand Total|60|300|460
                455.00|480|560|460
                """,
            ),
        )!!

        assertEquals(listOf("Royal Butter Chicken Masala", "Garlic Naan"), draft.lineItems.map { it.name })
        assertEquals(listOf(1, 3), draft.lineItems.map { it.qty })
        assertEquals(listOf(32000L, 13500L), draft.lineItems.map { it.amountMinor })
        assertEquals(45500L, draft.subtotalMinor)
        assertTrue(draft.verified)
    }

    /**
     * Item names CONTAINING digits ("7 Up", "500ml Water", "... 2pc") must not be read as a qty or a
     * price — they sit left of the money band and stay part of the name.
     */
    @Test fun namesContainingDigits_notReadAsQtyOrPrice() {
        val draft = parseBill(
            rowsOf(
                """
                SNACK SHACK|60|360|20

                Item|60|140|90
                Qty|300|340|90
                Rate|380|440|90
                Amount|480|560|90

                7 Up|60|130|140
                2|310|330|140
                40.00|380|440|140
                80.00|480|560|140

                500ml Water|60|220|180
                1|310|330|180
                20.00|380|440|180
                20.00|480|560|180

                Paneer Roll 2pc|60|280|220
                1|310|330|220
                90.00|380|440|220
                90.00|480|560|220

                Sub Total|300|455|280
                190.00|480|560|280

                Grand Total|60|300|320
                190.00|480|560|320
                """,
            ),
        )!!

        assertEquals(listOf("7 Up", "500ml Water", "Paneer Roll 2pc"), draft.lineItems.map { it.name })
        assertEquals(listOf(2, 1, 1), draft.lineItems.map { it.qty })
        assertEquals(listOf(8000L, 2000L, 9000L), draft.lineItems.map { it.amountMinor })
        assertEquals(19000L, draft.subtotalMinor)
        assertTrue(draft.verified)
    }

    /**
     * Item names containing `&`, `/`, `-`, `(`, `)` and `%` must survive intact — in particular a `%`
     * inside a name must NOT make the cell read as a percentage/tax token.
     */
    @Test fun namesWithSpecialCharacters() {
        val draft = parseBill(
            rowsOf(
                """
                FUSION BITES|60|360|20

                Item|60|140|90
                Qty|300|340|90
                Rate|380|440|90
                Amount|480|560|90

                Egg & Cheese|60|240|140
                1|310|330|140
                120.00|380|440|140
                120.00|480|560|140

                Tea/Coffee Combo|60|280|180
                2|310|330|180
                50.00|380|440|180
                100.00|480|560|180

                Roll (Half-Plate)|60|300|220
                1|310|330|220
                80.00|380|440|220
                80.00|480|560|220

                Combo 50% Extra|60|280|260
                1|310|330|260
                60.00|380|440|260
                60.00|480|560|260

                Sub Total|300|455|320
                360.00|480|560|320

                Grand Total|60|300|360
                360.00|480|560|360
                """,
            ),
        )!!

        assertEquals(
            listOf("Egg & Cheese", "Tea/Coffee Combo", "Roll (Half-Plate)", "Combo 50% Extra"),
            draft.lineItems.map { it.name },
        )
        assertEquals(listOf(1, 2, 1, 1), draft.lineItems.map { it.qty })
        assertEquals(listOf(12000L, 10000L, 8000L, 6000L), draft.lineItems.map { it.amountMinor })
        assertEquals(36000L, draft.subtotalMinor)
        assertTrue(draft.verified)
    }

    // ======================================================================================
    // Group E — Number / decimal styles
    // ======================================================================================

    /**
     * ONE dropped decimal: OCR read a faint "99.00" thermal decimal as whole rupees "9900" (100× too
     * large). The solver-corrector must rescale exactly that line so the bill reconciles to a known
     * subtotal.
     */
    @Test fun droppedDecimal_singleOutlier_rescaledByCorrector() {
        val draft = parseBill(
            rowsOf(
                """
                THELA CHAAT|60|360|20

                Item|60|140|90
                Amount|480|560|90

                Veg Roll|60|200|140
                149.00|480|560|140

                Cheese Roll|60|230|180
                9900|480|560|180

                Lassi|60|180|220
                55.00|480|560|220

                Sub Total|300|455|280
                303.00|480|560|280

                Grand Total|60|300|320
                303.00|480|560|320
                """,
            ),
        )!!

        assertEquals(listOf("Veg Roll", "Cheese Roll", "Lassi"), draft.lineItems.map { it.name })
        assertEquals(listOf(14900L, 9900L, 5500L), draft.lineItems.map { it.amountMinor })
        assertEquals(9900L, draft.lineItems[1].unitPriceMinor)
        assertEquals(30300L, draft.subtotalMinor)
        assertEquals(30300L, draft.totalMinor)
        assertTrue(draft.verified)
    }

    /**
     * TWO dropped decimals in one bill (99.00→"9900" and 55.00→"5500"). The corrector must rescale
     * BOTH offending lines (largest-first, guarded so a legit line is never divided) to reconcile.
     */
    @Test fun droppedDecimal_twoOutliers_rescaledByCorrector() {
        val draft = parseBill(
            rowsOf(
                """
                STREET GRILL|60|360|20

                Item|60|140|90
                Amount|480|560|90

                Egg Roll|60|200|140
                149.00|480|560|140

                Paneer Roll|60|230|180
                9900|480|560|180

                Melon Juice|60|230|220
                5500|480|560|220

                Sub Total|300|455|280
                303.00|480|560|280

                Grand Total|60|300|320
                303.00|480|560|320
                """,
            ),
        )!!

        assertEquals(listOf("Egg Roll", "Paneer Roll", "Melon Juice"), draft.lineItems.map { it.name })
        assertEquals(listOf(14900L, 9900L, 5500L), draft.lineItems.map { it.amountMinor })
        assertEquals(30300L, draft.subtotalMinor)
        assertTrue(draft.verified)
    }

    /**
     * Mixed number styles in one bill: comma-thousands `1,234.00`, a large catering line `12500.00`,
     * bare `₹149` and the Indian `149/-` flat notation, and a `0.00` free item (which must be dropped,
     * not listed).
     */
    @Test fun mixedNumberStyles_commaLargeBareSlashAndFreeItem() {
        val draft = parseBill(
            rowsOf(
                """
                PARTY CATERERS|60|360|20

                Item|60|140|90
                Amount|480|560|90

                Catering Platter|60|280|140
                1,234.00|470|560|140

                Bulk Order Box|60|280|180
                12500.00|470|560|180

                Veg Cutlet|60|230|220
                ₹149|480|560|220

                Samosa Plate|60|240|260
                149/-|480|560|260

                Welcome Drink|60|250|300
                0.00|480|560|300

                Sub Total|300|455|360
                14032.00|460|560|360

                Grand Total|60|300|400
                14032.00|460|560|400
                """,
            ),
        )!!

        assertEquals(listOf("Catering Platter", "Bulk Order Box", "Veg Cutlet", "Samosa Plate"), draft.lineItems.map { it.name })
        assertEquals(listOf(123400L, 1250000L, 14900L, 14900L), draft.lineItems.map { it.amountMinor })
        assertEquals(1403200L, draft.subtotalMinor)
        assertEquals(1403200L, draft.totalMinor)
        assertTrue(draft.verified)
    }
}
