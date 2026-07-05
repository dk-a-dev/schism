package ai.schism.split.core.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val information: String,
    val currency: String,
    val currencyCode: String,
    val createdAt: String,
    val isFavorite: Boolean = false,
    val activeParticipantId: String? = null,
)

@Entity(
    tableName = "participants",
    indices = [Index("groupId")],
)
data class ParticipantEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val name: String,
)

@Entity(
    tableName = "expenses",
    indices = [Index("groupId")],
)
data class ExpenseEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val title: String,
    val amount: Long,
    val categoryId: Int,
    val expenseDate: String,
    val paidById: String,
    val splitMode: String,
    val isReimbursement: Boolean,
    val notes: String,
    val createdAt: String,
    val addedBy: String = "",
)

@Entity(
    tableName = "expense_paid_for",
    primaryKeys = ["expenseId", "participantId"],
    indices = [Index("expenseId")],
)
data class PaidForEntity(
    val expenseId: String,
    val participantId: String,
    val shares: Long,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: Int,
    val grouping: String,
    val name: String,
)

// ---- relations ----

data class GroupWithParticipants(
    @Embedded val group: GroupEntity,
    @Relation(parentColumn = "id", entityColumn = "groupId")
    val participants: List<ParticipantEntity>,
)

data class ExpenseWithPaidFor(
    @Embedded val expense: ExpenseEntity,
    @Relation(parentColumn = "id", entityColumn = "expenseId")
    val paidFor: List<PaidForEntity>,
)
