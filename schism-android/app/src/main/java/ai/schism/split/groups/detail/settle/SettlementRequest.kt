package ai.schism.split.groups.detail.settle

import ai.schism.split.core.net.ExpenseRequest
import ai.schism.split.core.net.PaidForDto

/**
 * Builds the [ExpenseRequest] that records a settle-up as a reimbursement expense: [fromParticipantId]
 * pays [toParticipantId] [amountMinor] (minor units). Recording it flips the pair's net balance so the
 * Balances tab shows them settled. Pure — no I/O — so it is unit-tested directly.
 */
fun buildSettlementRequest(
    fromParticipantId: String,
    toParticipantId: String,
    amountMinor: Long,
    currency: String,
): ExpenseRequest = ExpenseRequest(
    title = "Settle up",
    amount = amountMinor,
    paidById = fromParticipantId,
    splitMode = "EVENLY",
    isReimbursement = true,
    paidFor = listOf(PaidForDto(participantId = toParticipantId, shares = 1L)),
)
