package ai.schism.split.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PlusInsightsTest {

    private val utc = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2026, 8, 9, 12, 0, 0, 0, utc).toInstant().toEpochMilli()

    private fun at(year: Int, month: Int, day: Int): Long =
        ZonedDateTime.of(year, month, day, 12, 0, 0, 0, utc).toInstant().toEpochMilli()

    @Test
    fun `compares this month with last`() {
        val insights = plusInsights(
            listOf(
                SpendTxn(1000, "INR", "A", at(2026, 8, 2)),
                SpendTxn(500, "INR", "B", at(2026, 8, 3)),
                SpendTxn(900, "INR", "A", at(2026, 7, 15)),
            ),
            now,
            utc,
        ).byCurrency.single()

        assertEquals(1500L, insights.thisMonthMinor)
        assertEquals(900L, insights.lastMonthMinor)
        assertTrue(insights.hasLastMonth)
        assertEquals(600L, insights.changeMinor)
    }

    @Test
    fun `no last month means no comparison rather than a fake zero baseline`() {
        val insights = plusInsights(listOf(SpendTxn(1000, "INR", "A", at(2026, 8, 2))), now, utc).byCurrency.single()
        assertFalse(insights.hasLastMonth)
        assertEquals(0L, insights.lastMonthMinor)
    }

    @Test
    fun `currencies are bucketed separately and never summed`() {
        val result = plusInsights(
            listOf(
                SpendTxn(1000, "INR", "A", at(2026, 8, 2)),
                SpendTxn(2000, "USD", "B", at(2026, 8, 2)),
                SpendTxn(300, "USD", "C", at(2026, 7, 2)),
            ),
            now,
            utc,
        )
        assertEquals(2, result.byCurrency.size)
        assertEquals(1000L, result.byCurrency.first { it.currency == "INR" }.thisMonthMinor)
        assertEquals(2000L, result.byCurrency.first { it.currency == "USD" }.thisMonthMinor)
        assertEquals(0L, result.byCurrency.first { it.currency == "INR" }.lastMonthMinor)
        assertEquals(300L, result.byCurrency.first { it.currency == "USD" }.lastMonthMinor)
    }

    @Test
    fun `merchants aggregate by name, biggest first`() {
        val insights = plusInsights(
            listOf(
                SpendTxn(100, "INR", "Small", at(2026, 8, 1)),
                SpendTxn(400, "INR", "Big", at(2026, 8, 1)),
                SpendTxn(600, "INR", "Big", at(2026, 8, 2)),
            ),
            now,
            utc,
        ).byCurrency.single()

        assertEquals("Big", insights.topMerchants.first().merchant)
        assertEquals(1000L, insights.topMerchants.first().totalMinor)
        assertEquals(2, insights.topMerchants.first().count)
    }

    @Test
    fun `months are chronological and keyed yyyy-MM`() {
        val insights = plusInsights(
            listOf(
                SpendTxn(100, "INR", "A", at(2026, 8, 1)),
                SpendTxn(100, "INR", "A", at(2026, 6, 1)),
                SpendTxn(100, "INR", "A", at(2026, 7, 1)),
            ),
            now,
            utc,
        ).byCurrency.single()
        assertEquals(listOf("2026-06", "2026-07", "2026-08"), insights.byMonth.map { it.month })
    }

    @Test
    fun `empty ledger yields no currency buckets`() {
        assertTrue(plusInsights(emptyList(), now, utc).byCurrency.isEmpty())
    }
}
