package ai.schism.split.sms.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM sms_transactions WHERE status = :status ORDER BY timestamp DESC")
    fun observeByStatus(status: String): Flow<List<TransactionEntity>>

    /** Every transaction, regardless of status — the full ledger of what the user has spent. */
    @Query("SELECT * FROM sms_transactions ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    /** Dedup insert: a transaction with an existing [TransactionEntity.id] is ignored, not replaced. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertIgnore(tx: TransactionEntity)

    @Query("UPDATE sms_transactions SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)

    /** Inline-edit a transaction's merchant/title and amount (SMS parsing isn't always right). */
    @Query("UPDATE sms_transactions SET merchant = :merchant, amountMinor = :amountMinor WHERE id = :id")
    suspend fun editTransaction(id: String, merchant: String, amountMinor: Long)

    @Query(
        "UPDATE sms_transactions SET status = '${TransactionStatus.PUSHED}', " +
            "remoteGroupId = :groupId, remoteExpenseId = :expenseId WHERE id = :id",
    )
    suspend fun markPushed(id: String, groupId: String, expenseId: String)

    @Query("SELECT * FROM sms_transactions WHERE id = :id")
    suspend fun getById(id: String): TransactionEntity?
}
