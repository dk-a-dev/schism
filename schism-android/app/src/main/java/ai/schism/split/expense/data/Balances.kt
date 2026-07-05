package ai.schism.split.expense.data

import ai.schism.split.core.net.ActivityDto
import ai.schism.split.core.net.BalanceDto
import ai.schism.split.core.net.BalancesResponseDto
import ai.schism.split.core.net.ReimbursementDto

// Server-computed values that are not cached in Room. Money is Long minor units.

data class Balance(
    val paid: Long,
    val paidFor: Long,
    val total: Long,
)

data class Reimbursement(
    val from: String,
    val to: String,
    val amount: Long,
)

data class Balances(
    val perParticipant: Map<String, Balance>,
    val reimbursements: List<Reimbursement>,
)

data class Activity(
    val id: String,
    val groupId: String,
    val time: String,
    val activityType: String,
    val participantId: String?,
    val expenseId: String?,
    val data: String,
)

// ---- DTO -> domain (pure, no Room; balances/activities are not entities) ----

fun BalanceDto.toDomain(): Balance = Balance(paid = paid, paidFor = paidFor, total = total)

fun ReimbursementDto.toDomain(): Reimbursement = Reimbursement(from = from, to = to, amount = amount)

fun BalancesResponseDto.toDomain(): Balances = Balances(
    perParticipant = balances.mapValues { (_, dto) -> dto.toDomain() },
    reimbursements = reimbursements.map { it.toDomain() },
)

fun ActivityDto.toDomain(): Activity = Activity(
    id = id,
    groupId = groupId,
    time = time,
    activityType = activityType,
    participantId = participantId,
    expenseId = expenseId,
    data = data,
)
