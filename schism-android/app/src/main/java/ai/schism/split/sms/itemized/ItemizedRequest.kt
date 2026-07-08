package ai.schism.split.sms.itemized

import ai.schism.split.core.net.ExpenseRequest
import ai.schism.split.core.net.PaidForDto
import ai.schism.split.groups.data.Group

/**
 * A receipt line item together with WEIGHTED shares per participant: `{devId: 2, ruId: 1}` means Dev
 * had two of it and Ru one, so Dev owes 2/3 of the line amount. The amount is minor units; an item
 * with no shares contributes to nobody's owed total.
 */
data class AssignedItem(
    val amountMinor: Long,
    val shares: Map<String, Long>,
) {
    constructor(amountMinor: Long, participantIds: List<String>) :
        this(amountMinor, participantIds.associateWith { 1L })
}

/**
 * Builds a BY_AMOUNT group expense from itemised receipt lines: each person owes the sum of their
 * share of every item they were assigned to. A shared item is split with integer division and any
 * leftover pennies go to the first assignee so the per-item split is exact (and the whole expense
 * sums to the receipt total). Participants who owe nothing are omitted from [ExpenseRequest.paidFor].
 *
 * Pure and network-free so it can be unit-tested. Returns null when no item is assigned to anyone.
 */
fun buildItemizedExpenseRequest(
    items: List<AssignedItem>,
    group: Group,
    paidById: String,
    addedBy: String?,
    title: String,
    currency: String,
    dateIso: String?,
    taxMinor: Long = 0,
    notes: String = "",
): ExpenseRequest? {
    if (items.none { it.shares.values.any { s -> s > 0 } }) return null

    // participantId -> owed minor units, preserving first-seen order for stable output.
    val owed = LinkedHashMap<String, Long>()
    for (item in items) {
        val active = item.shares.filterValues { it > 0 }
        val totalShares = active.values.sum()
        if (totalShares == 0L) continue
        var distributed = 0L
        val entries = active.entries.toList()
        entries.forEachIndexed { i, (participantId, share) ->
            // Weighted split; the last sharer absorbs rounding so the item splits exactly.
            val part = if (i == entries.lastIndex) item.amountMinor - distributed
            else item.amountMinor * share / totalShares
            distributed += part
            owed[participantId] = (owed[participantId] ?: 0L) + part
        }
    }

    // Distribute tax/charges in proportion to what each person ordered (last one gets the remainder).
    val assignedSubtotal = owed.values.sum()
    if (taxMinor > 0 && assignedSubtotal > 0) {
        val subtotalShares = owed.toMap()
        var remaining = taxMinor
        val entries = subtotalShares.entries.toList()
        entries.forEachIndexed { i, (pid, sub) ->
            val share = if (i == entries.lastIndex) remaining else taxMinor * sub / assignedSubtotal
            remaining -= share
            owed[pid] = (owed[pid] ?: 0L) + share
        }
    }

    val paidFor = owed.filterValues { it > 0L }
        .map { (participantId, amount) -> PaidForDto(participantId = participantId, shares = amount) }
    if (paidFor.isEmpty()) return null

    return ExpenseRequest(
        title = title,
        amount = assignedSubtotal + (if (assignedSubtotal > 0) taxMinor else 0L),
        expenseDate = dateIso,
        paidById = paidById,
        splitMode = "BY_AMOUNT",
        isReimbursement = false,
        addedBy = addedBy,
        notes = notes,
        paidFor = paidFor,
    )
}
