package ai.schism.split.sms.itemized.claim

import ai.schism.split.core.net.ClaimDto
import ai.schism.split.core.net.ClaimItemDto

/**
 * Client-side "you owe" preview: the same weighted + charge-pot algorithm as the server's
 * `ComputeClaimSplit` (Go), evaluated over only the *existing* claims (no unclaimed-item
 * resolutions) — this lets the claim screen show a live "you owe ₹…" without waiting for a poll
 * round-trip. Ports the exact weighted split + last-sharer/last-person rounding from
 * [ai.schism.split.sms.itemized.buildItemizedExpenseRequest]: each item's amount splits by weight
 * with the last claimant absorbing rounding so the per-item split is exact; the net charge pot
 * (tax + fees − discount + roundoff) then splits proportionally to each person's claimed subtotal,
 * with the last person (by first-seen order) absorbing the remainder. Pure and network-free.
 */
fun previewOwes(
    items: List<ClaimItemDto>,
    claims: List<ClaimDto>,
    taxMinor: Long = 0,
    feesMinor: Long = 0,
    discountMinor: Long = 0,
    roundoffMinor: Long = 0,
): Map<String, Long> {
    val claimsByItem = claims.groupBy { it.itemIdx }

    // participantId -> owed minor units, preserving first-seen order so the "last person" rule below
    // is stable.
    val owed = LinkedHashMap<String, Long>()
    for (item in items) {
        val active = claimsByItem[item.idx].orEmpty().filter { it.weight > 0 }
        val totalWeight = active.sumOf { it.weight }
        if (totalWeight <= 0.0) continue
        var distributed = 0L
        active.forEachIndexed { i, claim ->
            // Weighted split; the last claimant absorbs rounding so the item splits exactly.
            val part = if (i == active.lastIndex) {
                item.amountMinor - distributed
            } else {
                (item.amountMinor * claim.weight / totalWeight).toLong()
            }
            distributed += part
            owed[claim.participantId] = (owed[claim.participantId] ?: 0L) + part
        }
    }

    // Net charge pot split proportionally to each person's claimed subtotal (last person absorbs the
    // remainder).
    val chargePot = taxMinor + feesMinor - discountMinor + roundoffMinor
    val assignedSubtotal = owed.values.sum()
    if (chargePot != 0L && assignedSubtotal > 0) {
        var remaining = chargePot
        val entries = owed.entries.toList()
        entries.forEachIndexed { i, (participantId, subtotal) ->
            val share = if (i == entries.lastIndex) remaining else chargePot * subtotal / assignedSubtotal
            remaining -= share
            owed[participantId] = (owed[participantId] ?: 0L) + share
        }
    }

    return owed
}
