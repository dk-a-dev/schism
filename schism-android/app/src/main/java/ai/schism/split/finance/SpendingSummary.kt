package ai.schism.split.finance

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One transaction reduced to just the fields spending insights care about. The domain input to
 * [summarize] — decoupled from Room's `TransactionEntity` so the aggregation stays pure and testable.
 */
data class SpendTxn(
    val amountMinor: Long,
    val currency: String,
    val merchant: String,
    val timestamp: Long,
)

/** Total spent at a single merchant across the whole ledger. */
data class MerchantSpend(val merchant: String, val totalMinor: Long, val count: Int)

/** Total spent in a calendar month, keyed `yyyy-MM`. */
data class MonthSpend(val month: String, val totalMinor: Long)

/**
 * On-device spending insights derived from the local transaction ledger. [monthTotalMinor] is the
 * sum for the current calendar month only; [byMerchant] and [byMonth] cover the whole ledger
 * (trimmed to the top merchants / most-recent months). All money is in minor units of [currency].
 */
data class SpendingSummary(
    val monthTotalMinor: Long,
    val currency: String,
    val byMerchant: List<MerchantSpend>,
    val byMonth: List<MonthSpend>,
)

private const val MAX_MERCHANTS = 8
private const val MAX_MONTHS = 6
private val MONTH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

/**
 * Aggregate [transactions] into spending insights as of [nowEpochMillis]. Pure — the caller passes
 * the clock so results are deterministic; months are bucketed in the system default time zone.
 *
 * - [SpendingSummary.monthTotalMinor]: sum of txns whose `yyyy-MM` equals now's `yyyy-MM`.
 * - [SpendingSummary.byMerchant]: grouped by merchant, most-spent first, top [MAX_MERCHANTS].
 * - [SpendingSummary.byMonth]: grouped by `yyyy-MM`, most-recent [MAX_MONTHS], chronological.
 * - [SpendingSummary.currency]: the most common currency in the ledger.
 */
fun summarize(transactions: List<SpendTxn>, nowEpochMillis: Long): SpendingSummary {
    val zone = ZoneId.systemDefault()
    val currency = transactions.groupingBy { it.currency }.eachCount()
        .maxByOrNull { it.value }?.key.orEmpty()

    val nowMonth = monthKey(nowEpochMillis, zone)
    val monthTotal = transactions
        .filter { monthKey(it.timestamp, zone) == nowMonth }
        .sumOf { it.amountMinor }

    val byMerchant = transactions
        .groupBy { it.merchant }
        .map { (merchant, txns) ->
            MerchantSpend(merchant, txns.sumOf { it.amountMinor }, txns.size)
        }
        .sortedByDescending { it.totalMinor }
        .take(MAX_MERCHANTS)

    val byMonth = transactions
        .groupBy { monthKey(it.timestamp, zone) }
        .map { (month, txns) -> MonthSpend(month, txns.sumOf { it.amountMinor }) }
        .sortedByDescending { it.month }
        .take(MAX_MONTHS)
        .sortedBy { it.month }

    return SpendingSummary(
        monthTotalMinor = monthTotal,
        currency = currency,
        byMerchant = byMerchant,
        byMonth = byMonth,
    )
}

private fun monthKey(epochMillis: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone).format(MONTH_FORMAT)
