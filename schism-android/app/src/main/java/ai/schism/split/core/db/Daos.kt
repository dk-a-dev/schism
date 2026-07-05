package ai.schism.split.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Transaction
    @Query("SELECT * FROM groups ORDER BY isFavorite DESC, name COLLATE NOCASE")
    fun observeGroups(): Flow<List<GroupWithParticipants>>

    @Transaction
    @Query("SELECT * FROM groups WHERE id = :id")
    fun observeGroup(id: String): Flow<GroupWithParticipants?>

    @Upsert
    suspend fun upsertGroup(group: GroupEntity)

    @Upsert
    suspend fun upsertParticipants(participants: List<ParticipantEntity>)

    @Query("DELETE FROM participants WHERE groupId = :groupId")
    suspend fun clearParticipants(groupId: String)

    @Query("UPDATE groups SET isFavorite = :favorite WHERE id = :groupId")
    suspend fun setFavorite(groupId: String, favorite: Boolean)

    @Query("UPDATE groups SET activeParticipantId = :participantId WHERE id = :groupId")
    suspend fun setActiveParticipant(groupId: String, participantId: String?)

    /** Replaces a group and its participant set atomically, preserving local-only flags. */
    @Transaction
    suspend fun upsertGroupWithParticipants(group: GroupEntity, participants: List<ParticipantEntity>) {
        val existing = currentFlags(group.id)
        val merged = if (existing != null) {
            group.copy(isFavorite = existing.isFavorite, activeParticipantId = existing.activeParticipantId)
        } else {
            group
        }
        upsertGroup(merged)
        clearParticipants(group.id)
        upsertParticipants(participants)
    }

    @Query("SELECT isFavorite, activeParticipantId FROM groups WHERE id = :groupId")
    suspend fun currentFlags(groupId: String): GroupLocalFlags?
}

data class GroupLocalFlags(
    val isFavorite: Boolean,
    val activeParticipantId: String?,
)

@Dao
interface ExpenseDao {
    @Transaction
    @Query("SELECT * FROM expenses WHERE groupId = :groupId ORDER BY expenseDate DESC, createdAt DESC")
    fun observeExpenses(groupId: String): Flow<List<ExpenseWithPaidFor>>

    @Upsert
    suspend fun upsertExpense(expense: ExpenseEntity)

    @Upsert
    suspend fun upsertPaidFor(paidFor: List<PaidForEntity>)

    @Query("DELETE FROM expense_paid_for WHERE expenseId = :expenseId")
    suspend fun clearPaidFor(expenseId: String)

    @Query("DELETE FROM expenses WHERE id = :expenseId")
    suspend fun deleteExpense(expenseId: String)

    @Query("DELETE FROM expenses WHERE groupId = :groupId")
    suspend fun clearGroupExpenses(groupId: String)

    @Transaction
    suspend fun replaceGroupExpenses(groupId: String, expenses: List<ExpenseWithPaidFor>) {
        clearGroupExpenses(groupId)
        for (e in expenses) {
            upsertExpense(e.expense)
            upsertPaidFor(e.paidFor)
        }
    }

    @Transaction
    suspend fun upsertExpenseWithPaidFor(expense: ExpenseEntity, paidFor: List<PaidForEntity>) {
        upsertExpense(expense)
        clearPaidFor(expense.id)
        upsertPaidFor(paidFor)
    }
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY id")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Upsert
    suspend fun upsertAll(categories: List<CategoryEntity>)
}
