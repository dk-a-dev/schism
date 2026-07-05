package ai.schism.split.core.net

import kotlinx.serialization.Serializable

// DTOs mirror schism-backend/docs/api-contract.md (camelCase JSON, money as Long minor units).

@Serializable
data class ParticipantDto(
    val id: String = "",
    val groupId: String = "",
    val name: String = "",
    val userId: String? = null,
)

@Serializable
data class UserDto(
    val id: String,
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val createdAt: String = "",
    /** Bearer token, present only in the register response; stored and sent on later requests. */
    val token: String? = null,
)

@Serializable
data class UserRequest(
    val name: String,
    val email: String = "",
    val phone: String = "",
)

@Serializable
data class GroupDto(
    val id: String,
    val name: String,
    val information: String = "",
    val currency: String = "",
    val currencyCode: String = "",
    val createdAt: String = "",
    val participants: List<ParticipantDto> = emptyList(),
)

@Serializable
data class CategoryDto(
    val id: Int,
    val grouping: String,
    val name: String,
)

@Serializable
data class PaidForDto(
    val participantId: String,
    val shares: Long,
)

@Serializable
data class ExpenseDto(
    val id: String,
    val groupId: String = "",
    val title: String,
    val amount: Long,
    val categoryId: Int = 0,
    val expenseDate: String = "",
    val paidById: String,
    val splitMode: String = "EVENLY",
    val isReimbursement: Boolean = false,
    val notes: String = "",
    val createdAt: String = "",
    val addedBy: String = "",
    val paidFor: List<PaidForDto> = emptyList(),
)

@Serializable
data class BalanceDto(
    val paid: Long = 0,
    val paidFor: Long = 0,
    val total: Long = 0,
)

@Serializable
data class ReimbursementDto(
    val from: String,
    val to: String,
    val amount: Long,
)

@Serializable
data class BalancesResponseDto(
    val balances: Map<String, BalanceDto> = emptyMap(),
    val reimbursements: List<ReimbursementDto> = emptyList(),
)

@Serializable
data class ActivityDto(
    val id: String,
    val groupId: String = "",
    val time: String = "",
    val activityType: String = "",
    val participantId: String? = null,
    val expenseId: String? = null,
    val data: String = "",
    val amount: Long? = null,
)

// ---- requests ----

@Serializable
data class AuthRequest(
    val email: String,
    val password: String,
    val name: String = "",
    val phone: String = "",
)

@Serializable
data class MyGroupsDto(
    val groupIds: List<String> = emptyList(),
)

@Serializable
data class AuthResponse(
    val id: String,
    val name: String = "",
    val email: String = "",
    val token: String = "",
)

@Serializable
data class ParticipantRequest(
    val id: String? = null,
    val name: String,
    val userId: String? = null,
    /** Phone number for invite-by-contact: lets the backend link this participant when they join. */
    val phone: String? = null,
)

@Serializable
data class CreateGroupRequest(
    val name: String,
    val information: String = "",
    val currency: String,
    val currencyCode: String = "",
    val participants: List<ParticipantRequest>,
)

@Serializable
data class CreateGroupResponse(
    val groupId: String,
)

@Serializable
data class ExpenseRequest(
    val title: String,
    val amount: Long,
    val categoryId: Int = 0,
    val expenseDate: String? = null,
    val paidById: String,
    val splitMode: String = "EVENLY",
    val isReimbursement: Boolean = false,
    val notes: String = "",
    val addedBy: String? = null,
    val paidFor: List<PaidForDto>,
)

// ---- dashboards ----

@Serializable
data class CategoryTotalDto(
    val categoryId: Int,
    val grouping: String = "",
    val name: String = "",
    val amount: Long = 0,
    val count: Int = 0,
)

@Serializable
data class ParticipantTotalDto(
    val participantId: String,
    val name: String = "",
    val paid: Long = 0,
    val share: Long = 0,
    val net: Long = 0,
)

@Serializable
data class MonthTotalDto(
    val month: String,
    val amount: Long = 0,
    val count: Int = 0,
)

@Serializable
data class ExpenseSummaryDto(
    val id: String,
    val title: String,
    val amount: Long,
    val date: String = "",
    val paidById: String = "",
    val categoryId: Int = 0,
)

@Serializable
data class PersonalInGroupDto(
    val participantId: String,
    val name: String = "",
    val paid: Long = 0,
    val share: Long = 0,
    val net: Long = 0,
    val expenseCount: Int = 0,
    val byCategory: List<CategoryTotalDto> = emptyList(),
)

@Serializable
data class GroupDashboardDto(
    val groupId: String,
    val name: String = "",
    val currency: String = "",
    val currencyCode: String = "",
    val totalSpending: Long = 0,
    val expenseCount: Int = 0,
    val reimbursementCount: Int = 0,
    val averageExpense: Long = 0,
    val firstExpenseDate: String? = null,
    val lastExpenseDate: String? = null,
    val byCategory: List<CategoryTotalDto> = emptyList(),
    val byParticipant: List<ParticipantTotalDto> = emptyList(),
    val byMonth: List<MonthTotalDto> = emptyList(),
    val topExpenses: List<ExpenseSummaryDto> = emptyList(),
    val personal: PersonalInGroupDto? = null,
)

@Serializable
data class PersonalGroupSliceDto(
    val groupId: String,
    val groupName: String = "",
    val currency: String = "",
    val currencyCode: String = "",
    val participantId: String = "",
    val participantName: String = "",
    val paid: Long = 0,
    val share: Long = 0,
    val net: Long = 0,
    val expenseCount: Int = 0,
)

@Serializable
data class CurrencyTotalDto(
    val currencyCode: String = "",
    val currency: String = "",
    val paid: Long = 0,
    val share: Long = 0,
    val net: Long = 0,
    val groupCount: Int = 0,
)

@Serializable
data class PersonalDashboardDto(
    val identity: String = "",
    val groupCount: Int = 0,
    val groups: List<PersonalGroupSliceDto> = emptyList(),
    val totals: List<CurrencyTotalDto> = emptyList(),
)
