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
}
