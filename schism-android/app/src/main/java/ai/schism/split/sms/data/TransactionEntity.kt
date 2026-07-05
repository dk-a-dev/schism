package ai.schism.split.sms.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Lifecycle of a locally-parsed bank transaction as the user triages it in the Inbox. */
object TransactionStatus {
    const val UNASSIGNED = "UNASSIGNED"
    const val PERSONAL = "PERSONAL"
    const val PUSHED = "PUSHED"
}

/**
 * A bank transaction parsed on-device from an SMS. The raw SMS body never leaves the device; only
 * the amount/title/date of a *pushed* transaction are sent to the backend as a group expense.
 *
 * [id] is [com.pennywiseai.parser.core.ParsedTransaction.generateTransactionId] — a stable dedup
 * hash of the SMS content, so re-ingesting the same message is a no-op.
 */
@Entity(
    tableName = "sms_transactions",
    indices = [Index("status")],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val amountMinor: Long,
    val currency: String,
    val merchant: String,
    val bankName: String,
    val timestamp: Long,
    val rawSender: String,
    val status: String = TransactionStatus.UNASSIGNED,
    val remoteGroupId: String? = null,
    val remoteExpenseId: String? = null,
)
