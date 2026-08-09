package ai.schism.split.finance

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Insights for one currency. Currencies are never summed together — ₹ and $ are separate lives. */
data class CurrencyInsights(
    val currency: String,
    val thisMonthMinor: Long,
    val lastMonthMinor: Long,
    val hasLastMonth: Boolean,
    val topMerchants: List<MerchantSpend>,
    val byMonth: List<MonthSpend>,
) {
    /** Positive = spent more than last month. Meaningless unless [hasLastMonth]. */
    val changeMinor: Long get() = thisMonthMinor - lastMonthMinor
}

/** The Plus-only view of the same on-device ledger the free summary already reads. */
data class PlusInsightsData(val byCurrency: List<CurrencyInsights>)

private const val MAX_MERCHANTS = 10
private const val MAX_MONTHS = 12

/**
 * Month-over-month comparison plus merchant and month history, bucketed per currency. Pure: the
 * caller passes the clock and zone, so results are deterministic. Reads only what the device already
 * holds — no network, no upload.
 */
fun plusInsights(
    transactions: List<SpendTxn>,
    nowEpochMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): PlusInsightsData {
    val thisMonth = YearMonth.from(Instant.ofEpochMilli(nowEpochMillis).atZone(zone))
    val lastMonth = thisMonth.minusMonths(1)

    val byCurrency = transactions
        .groupBy { it.currency }
        .map { (currency, txns) ->
            val months = txns.groupBy { YearMonth.from(Instant.ofEpochMilli(it.timestamp).atZone(zone)) }
            CurrencyInsights(
                currency = currency,
                thisMonthMinor = months[thisMonth]?.sumOf { it.amountMinor } ?: 0L,
                lastMonthMinor = months[lastMonth]?.sumOf { it.amountMinor } ?: 0L,
                hasLastMonth = months.containsKey(lastMonth),
                topMerchants = txns
                    .groupBy { it.merchant }
                    .map { (merchant, m) -> MerchantSpend(merchant, m.sumOf { it.amountMinor }, m.size) }
                    .sortedByDescending { it.totalMinor }
                    .take(MAX_MERCHANTS),
                byMonth = months
                    .map { (month, m) -> MonthSpend(month.toString(), m.sumOf { it.amountMinor }) }
                    .sortedByDescending { it.month }
                    .take(MAX_MONTHS)
                    .sortedBy { it.month },
            )
        }
        .sortedByDescending { it.thisMonthMinor }

    return PlusInsightsData(byCurrency)
}
