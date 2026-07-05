package ai.schism.split.core.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A pending write queued while offline (or after a network failure). The [payload] is the serialized
 * `ExpenseRequest`; on sync it's replayed to the server with [idempotencyKey] so retries never
 * double-post. [optimisticExpenseId] is the temporary local expense shown immediately — dropped if
 * the server rejects the write.
 */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val groupId: String,
    val idempotencyKey: String,
    val optimisticExpenseId: String,
    val payload: String,
    val createdAt: Long,
)

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(entry: OutboxEntity)

    @Query("SELECT * FROM outbox ORDER BY createdAt ASC")
    suspend fun all(): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM outbox WHERE localId = :localId")
    suspend fun delete(localId: Long)
}
