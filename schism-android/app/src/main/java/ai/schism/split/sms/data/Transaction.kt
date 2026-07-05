package ai.schism.split.sms.data

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.parser.core.TransactionType

/** Domain model exposed to the UI layer (no entity/parser leakage upward). Money is Long minor units. */
data class Transaction(
    val id: String,
    val amountMinor: Long,
    val currency: String,
    val merchant: String,
    val bankName: String,
    val timestamp: Long,
    val status: String,
)

fun TransactionEntity.toDomain(): Transaction = Transaction(
    id = id,
    amountMinor = amountMinor,
    currency = currency,
    merchant = merchant,
    bankName = bankName,
    timestamp = timestamp,
    status = status,
)

/** Expense-like (money-out) transaction types the Inbox surfaces; credits/income are skipped. */
fun TransactionType.isExpenseLike(): Boolean = this == TransactionType.EXPENSE

/**
 * Maps a parsed bank SMS to a storable entity. Amount (a [java.math.BigDecimal]) becomes minor units
 * (e.g. 42.00 -> 4200), a missing merchant falls back to "Unknown", and the row starts UNASSIGNED.
 */
fun ParsedTransaction.toEntity(): TransactionEntity = TransactionEntity(
    id = generateTransactionId(),
    amountMinor = amount.movePointRight(2).toLong(),
    currency = currency,
    merchant = merchant ?: "Unknown",
    bankName = bankName,
    timestamp = timestamp,
    rawSender = sender,
    status = TransactionStatus.UNASSIGNED,
)
