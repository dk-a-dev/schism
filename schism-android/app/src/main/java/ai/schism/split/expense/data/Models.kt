package ai.schism.split.expense.data

data class PaidFor(
    val participantId: String,
    val shares: Long,
)

data class Expense(
    val id: String,
    val groupId: String,
    val title: String,
    val amount: Long,
    val categoryId: Int,
    val expenseDate: String,
    val paidById: String,
    val splitMode: String,
    val isReimbursement: Boolean,
    val notes: String,
    val addedBy: String = "",
    val paidFor: List<PaidFor>,
)
