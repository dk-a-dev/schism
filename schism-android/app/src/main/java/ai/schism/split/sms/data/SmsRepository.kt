package ai.schism.split.sms.data

import ai.schism.split.sms.receipt.ReceiptDraft
import com.pennywiseai.parser.core.bank.BankParserFactory
import com.pennywiseai.parser.core.md5Hex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device ledger of bank transactions parsed from SMS. The SMS body is parsed locally and NEVER
 * leaves the device; only a *pushed* transaction's amount/title/date go to the backend (via
 * [ai.schism.split.expense.data.ExpenseRepository]). Room suspend calls are main-safe, so no
 * explicit dispatcher hop is needed.
 */
@Singleton
class SmsRepository @Inject constructor(
    private val dao: TransactionDao,
) {
    /** Unassigned transactions awaiting triage, newest first. */
    fun observeInbox(): Flow<List<Transaction>> =
        dao.observeByStatus(TransactionStatus.UNASSIGNED).map { list -> list.map { it.toDomain() } }

    /**
     * Parses one SMS on-device and, if it's a recognized expense-like bank transaction, records it.
     * Dedup is by the parser's stable transaction id, so ingesting the same message twice is a no-op.
     */
    suspend fun ingest(smsBody: String, sender: String, timestamp: Long) {
        val parsed = BankParserFactory.parse(smsBody, sender, timestamp) ?: return
        if (!parsed.type.isExpenseLike()) return
        dao.upsertIgnore(parsed.toEntity())
    }

    /**
     * Records a receipt scanned on-device (via ML Kit OCR + [ai.schism.split.sms.receipt.parseReceipt])
     * as an unassigned transaction, so it flows through the same keep-personal / split-to-group triage.
     * Deduped by a stable hash of its fields. Returns the transaction id.
     */
    suspend fun addReceipt(draft: ReceiptDraft): String {
        val id = md5Hex("receipt|${draft.merchant}|${draft.totalMinor}|${draft.date}")
        val timestamp = draft.date
            ?.let { runCatching { LocalDate.parse(it).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull() }
            ?: System.currentTimeMillis()
        dao.upsertIgnore(
            TransactionEntity(
                id = id,
                amountMinor = draft.totalMinor,
                currency = draft.currency,
                merchant = draft.merchant,
                bankName = "Receipt",
                timestamp = timestamp,
                rawSender = "receipt",
                status = TransactionStatus.UNASSIGNED,
            ),
        )
        return id
    }

    suspend fun keepPersonal(id: String) = dao.setStatus(id, TransactionStatus.PERSONAL)

    suspend fun markPushed(id: String, groupId: String, expenseId: String) =
        dao.markPushed(id, groupId, expenseId)

    suspend fun getById(id: String): Transaction? = dao.getById(id)?.toDomain()
}
