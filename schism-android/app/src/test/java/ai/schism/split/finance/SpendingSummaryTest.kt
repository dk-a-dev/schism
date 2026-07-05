package ai.schism.split.finance

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SpendingSummaryTest {

    /** Noon on the 15th in the system zone — safely mid-month so the yyyy-MM bucket is zone-stable. */
    private fun midMonth(year: Int, month: Int): Long =
        ZonedDateTime.of(year, month, 15, 12, 0, 0, 0, ZoneId.systemDefault())
            .toInstant().toEpochMilli()

    @Test
    fun summarizesMerchantsMonthsAndCurrentMonthTotal() {
        val now = midMonth(2026, 7)
        val txns = listOf(
            // July (current month)
            SpendTxn(amountMinor = 3000, currency = "₹", merchant = "Amazon", timestamp = midMonth(2026, 7)),
            SpendTxn(amountMinor = 1000, currency = "₹", merchant = "Amazon", timestamp = midMonth(2026, 7)),
            SpendTxn(amountMinor = 2000, currency = "₹", merchant = "Zomato", timestamp = midMonth(2026, 7)),
            // June (previous month)
            SpendTxn(amountMinor = 5000, currency = "₹", merchant = "Zomato", timestamp = midMonth(2026, 6)),
        )

        val summary = summarize(txns, now)

        // Current month total: July only -> 3000 + 1000 + 2000
        assertEquals(6000L, summary.monthTotalMinor)
        assertEquals("₹", summary.currency)

        // By merchant: sorted desc by total. Zomato 7000 (2 txns), Amazon 4000 (2 txns).
        assertEquals(2, summary.byMerchant.size)
        assertEquals("Zomato", summary.byMerchant[0].merchant)
        assertEquals(7000L, summary.byMerchant[0].totalMinor)
        assertEquals(2, summary.byMerchant[0].count)
        assertEquals("Amazon", summary.byMerchant[1].merchant)
        assertEquals(4000L, summary.byMerchant[1].totalMinor)
        assertEquals(2, summary.byMerchant[1].count)

        // By month: chronological, June then July.
        assertEquals(2, summary.byMonth.size)
        assertEquals("2026-06", summary.byMonth[0].month)
        assertEquals(5000L, summary.byMonth[0].totalMinor)
        assertEquals("2026-07", summary.byMonth[1].month)
        assertEquals(6000L, summary.byMonth[1].totalMinor)
    }

    @Test
    fun emptyLedgerYieldsZeroTotalAndBlankCurrency() {
        val summary = summarize(emptyList(), midMonth(2026, 7))
        assertEquals(0L, summary.monthTotalMinor)
        assertEquals("", summary.currency)
        assertEquals(emptyList<MerchantSpend>(), summary.byMerchant)
        assertEquals(emptyList<MonthSpend>(), summary.byMonth)
    }

    @Test
    fun picksMostCommonCurrency() {
        val now = midMonth(2026, 7)
        val txns = listOf(
            SpendTxn(1000, "₹", "A", midMonth(2026, 7)),
            SpendTxn(1000, "₹", "B", midMonth(2026, 7)),
            SpendTxn(1000, "$", "C", midMonth(2026, 7)),
        )
        assertEquals("₹", summarize(txns, now).currency)
    }
}
