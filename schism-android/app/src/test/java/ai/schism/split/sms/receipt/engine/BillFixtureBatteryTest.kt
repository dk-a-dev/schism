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
}
