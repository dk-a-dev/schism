package ai.schism.split.sms.receipt.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end integration of the whole deterministic engine via [parseBill], on realistic bill
 * shapes. Fixtures are written in a compact `text|xLeft|xRight|yCenter` cell-dump (blank line = new
 * visual row) so the geometry → columns → regions → items → solver → templates pipeline runs exactly
 * as it would on ML-Kit output. These are a representative SAMPLE plus a held-out bill the engine was
 * not tuned on — the deliverable is the general engine, not these specific numbers.
 */
class ParseBillIntegrationTest {

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

    /**
     * Vraj Restaurant — paper POS, columns `No. | Item | Qty | Price | Amount`. Exercises: a leftmost
     * serial-number column that must NOT be read as qty, qty-before-... no wait, Qty|Price|Amount order,
     * and Sub Total / Grand Total anchors with no tax. Items sum 1249 = subtotal = grand total.
     */
    @Test
    fun vrajPaperBillParsesAndVerifies() {
        val draft = parseBill(
            rowsOf(
                """
                VRAJ RESTAURANT|60|320|20

                No.Item|20|180|90
                Qty.|300|350|90
                Price|370|440|90
                Amount|470|560|90

                1|20|35|140
                Manchow Soup|60|260|140
                2|300|320|140
                159.00|370|440|140
                318.00|470|560|140

                2|20|35|180
                Paneer Tufani|60|280|180
                1|300|320|180
                299.00|370|440|180
                299.00|470|560|180

                3|20|35|220
                Butter Roti|60|250|220
                6|300|320|220
                39.00|370|440|220
                234.00|470|560|220

                4|20|35|260
                Cheese Corn Kebab|60|300|260
                1|300|320|260
                289.00|370|440|260
                289.00|470|560|260

                5|20|35|300
                Masala Papad|60|270|300
                1|300|320|300
                49.00|370|440|300
                49.00|470|560|300

                6|20|35|340
                Buttermilk|60|220|340
                2|300|320|340
                30.00|370|440|340
                60.00|470|560|340

                Total Qty: 13|60|300|400
                Sub Total|360|455|400
                1249.00|470|560|400

                Grand Total|60|300|440
                1249.00|470|560|440
                """,
            ),
        )!!

        assertEquals(
            listOf("Manchow Soup", "Paneer Tufani", "Butter Roti", "Cheese Corn Kebab", "Masala Papad", "Buttermilk"),
            draft.lineItems.map { it.name },
        )
        assertEquals(listOf(2, 1, 6, 1, 1, 2), draft.lineItems.map { it.qty })
        assertEquals(listOf(31800L, 29900L, 23400L, 28900L, 4900L, 6000L), draft.lineItems.map { it.amountMinor })
        assertEquals(124900L, draft.lineItems.sumOf { it.amountMinor })
        assertEquals(124900L, draft.subtotalMinor)
        assertEquals(0L, draft.taxMinor)
        assertEquals(124900L, draft.totalMinor)
        assertTrue("Vraj bill should verify (items sum to subtotal to grand total)", draft.verified)
    }

    /**
     * HELD-OUT bill the engine was not tuned on: a US café in `$`, columns `Item | Qty | Price |
     * Amount`, with a Subtotal, a Tax line, and a Tip line, and a Total. Different currency, different
     * charge vocabulary ("Tip") — must parse with the SAME general code. Items 12.00; tax 0.96; tip
     * 2.00; total 14.96.
     */
    @Test
    fun heldOutUsCafeWithTipParsesAndVerifies() {
        val draft = parseBill(
            rowsOf(
                """
                BLUE BOTTLE CAFE|60|320|20

                Item|60|140|90
                Qty|300|340|90
                Price|360|430|90
                Amount|470|560|90

                Cappuccino|60|220|140
                2|300|320|140
                4.50|360|430|140
                9.00|470|560|140

                Croissant|60|220|180
                1|300|320|180
                3.00|360|430|180
                3.00|470|560|180

                Subtotal|60|300|240
                12.00|470|560|240

                Sales Tax|60|300|280
                0.96|470|560|280

                Tip|60|300|320
                2.00|470|560|320

                Total|60|300|360
                $14.96|470|560|360
                """,
            ),
        )!!

        assertEquals(listOf("Cappuccino", "Croissant"), draft.lineItems.map { it.name })
        assertEquals(listOf(2, 1), draft.lineItems.map { it.qty })
        assertEquals(listOf(900L, 300L), draft.lineItems.map { it.amountMinor })
        assertEquals(1200L, draft.subtotalMinor)
        // charge pot = tax 0.96 + tip (a fee/charge) 2.00 = 2.96
        assertEquals(296L, draft.taxMinor)
        assertEquals(1496L, draft.totalMinor)
        assertTrue("Held-out café bill should verify with the same general code", draft.verified)
    }

    /**
     * Olive Street Food Cafe — the canonical failing thermal bill. Encoded EXACTLY as ML Kit emits:
     * the header prints `No.Item  Qty.  PriceAmount` with Price+Amount fused into ONE cell (no gap),
     * each item's numeric triple (qty, price, amount) sits on the FIRST line with the first name word,
     * and the rest of the (wrapped) name follows on SEPARATE moneyless rows. The Price and Amount
     * money columns are printed so close together that the geometry clusterer merges them — reproducing
     * defect (d): with no distinct RATE column and a fused header, the naive reading grabs the leftmost
     * money cell (the price) instead of the line amount, undercounting qty>1 rows. The engine must
     * still recover the correct (qty, unit price, amount) per row via the qty×price=amount invariant.
     */
    @Test
    fun oliveThermalBillWithFusedPriceAmountHeaderAndWrappedNames() {
        val rows = rowsOf(
            """
            OLIVE STREET FOOD CAFE|60|360|20

            No.Item|60|300|90
            Qty.|833|867|90
            PriceAmount|890|960|90

            Egg|140|180|140
            1|833|867|140
            149.00|890|924|140
            149.00|926|960|140

            & Sausage Blast Roll|140|520|170

            Jumbo|140|230|220
            3|833|867|220
            149.00|890|924|220
            447.00|926|960|220

            King Roll|140|300|250

            Chicken|140|250|300
            1|833|867|300
            89.00|890|924|300
            89.00|926|960|300

            Lahori Roll|140|320|330

            Bombay|140|260|380
            1|833|867|380
            99.00|890|924|380
            99.00|926|960|380

            Parotta Roll|140|330|410

            Lime|140|220|460
            3|833|867|460
            35.00|895|924|460
            105.00|926|960|460

            Juice|140|230|490

            Water|140|240|540
            1|833|867|540
            55.00|895|924|540
            55.00|926|960|540

            Melon|140|250|570

            Total Qty: 10|140|360|620

            Sub Total|140|300|660
            944.00|860|960|660

            SGST|140|230|700
            2.5%|300|360|700
            23.60|860|960|700

            CGST|140|230|740
            2.5%|300|360|740
            23.60|860|960|740

            Round off|140|300|780
            -0.20|860|960|780

            Grand Total|140|340|820
            991.00|860|960|820
            """,
        )
        val draft = parseBill(rows)!!

        assertEquals(
            listOf(
                "Egg & Sausage Blast Roll", "Jumbo King Roll", "Chicken Lahori Roll",
                "Bombay Parotta Roll", "Lime Juice", "Water Melon",
            ),
            draft.lineItems.map { it.name },
        )
        assertEquals(listOf(1, 3, 1, 1, 3, 1), draft.lineItems.map { it.qty })
        assertEquals(
            listOf(14900L, 14900L, 8900L, 9900L, 3500L, 5500L),
            draft.lineItems.map { it.unitPriceMinor },
        )
        assertEquals(
            listOf(14900L, 44700L, 8900L, 9900L, 10500L, 5500L),
            draft.lineItems.map { it.amountMinor },
        )
        assertEquals(94400L, draft.lineItems.sumOf { it.amountMinor })
        assertEquals(94400L, draft.subtotalMinor)
        assertEquals(99100L, draft.totalMinor)
        assertTrue("Olive bill must verify deterministically (items→subtotal→grand total)", draft.verified)

        // Totals-region specifics: the two GST lines survive their trailing "2.5%" tokens, and the
        // signed round-off is captured as −0.20.
        val totals = readTotals(segment(rows))
        assertEquals(4720L, totals.tax) // SGST 23.60 + CGST 23.60
        assertEquals(-20L, totals.roundoff)
        assertEquals(94400L, totals.subtotal)
        assertEquals(99100L, totals.grandTotal)
    }
}
