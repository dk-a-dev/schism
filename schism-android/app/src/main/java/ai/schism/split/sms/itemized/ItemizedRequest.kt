package ai.schism.split.sms.itemized

import ai.schism.split.core.net.ExpenseRequest
import ai.schism.split.core.net.PaidForDto
import ai.schism.split.groups.data.Group

/**
 * A receipt line item together with the participants who are sharing it. The amount is minor units;
 * an item with no assignees contributes to nobody's owed total.
 */
data class AssignedItem(
    val amountMinor: Long,
    val participantIds: List<String>,
)

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
): ExpenseRequest? {
    if (items.none { it.participantIds.isNotEmpty() }) return null

    // participantId -> owed minor units, preserving first-seen order for stable output.
    val owed = LinkedHashMap<String, Long>()
    for (item in items) {
        val shareCount = item.participantIds.size
        if (shareCount == 0) continue
        val base = item.amountMinor / shareCount
        var remainder = item.amountMinor - base * shareCount
        for (participantId in item.participantIds) {
            // Hand the leftover pennies to the first assignee(s) so the item splits exactly.
            val extra = if (remainder > 0) 1L else 0L
            if (remainder > 0) remainder--
            owed[participantId] = (owed[participantId] ?: 0L) + base + extra
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
        notes = "",
        paidFor = paidFor,
    )
}
