package ai.schism.split.sms.itemized.claim

import ai.schism.split.core.net.ClaimDto
import ai.schism.split.core.net.ClaimItemDto

/**
 * Client-side "you owe" preview: the same weighted + charge-pot algorithm as the server's
 * `ComputeClaimSplit` (Go), evaluated over only the *existing* claims (no unclaimed-item
 * resolutions) — this lets the claim screen show a live "you owe ₹…" without waiting for a poll
 * round-trip. Ports `ComputeClaimSplit` exactly: each item's weights are scaled to integer
 * "centi-weights" (`Math.round(weight * 100)`, duplicate claim rows per participant aggregated
 * first) and the amount split by pure integer division, with the LAST participant id in SORTED
 * order absorbing the per-item rounding remainder so the split is always exact. The net charge pot
 * (tax + fees − discount + roundoff) then splits proportionally to each person's claimed subtotal,
 * again with the last-sorted id absorbing the remainder — falling back to an even split (still
 * last-sorted-absorbs) when the claimed subtotal is zero but the pot isn't. Pure and network-free.
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

    val owed = LinkedHashMap<String, Long>()
    for (item in items) {
        val active = claimsByItem[item.idx].orEmpty().filter { it.weight > 0 }
        val scaled = active
            .groupBy { it.participantId }
            .mapValues { (_, cs) -> Math.round(cs.sumOf { it.weight } * 100) }
            .filterValues { it > 0 }
        val total = scaled.values.sum()
        if (total <= 0L) continue
        var distributed = 0L
        val pids = scaled.keys.sorted() // last (by sorted participant id) absorbs the remainder
        pids.forEachIndexed { i, pid ->
            val part = if (i == pids.lastIndex) {
                item.amountMinor - distributed
            } else {
                item.amountMinor * scaled.getValue(pid) / total
            }
            distributed += part
            owed[pid] = (owed[pid] ?: 0L) + part
        }
    }

    // Net charge pot split proportionally to each person's claimed subtotal (last-sorted id absorbs
    // the remainder); if nobody has a positive subtotal but the pot is non-zero, split it evenly
    // across claimants instead of dropping it (mirrors ComputeClaimSplit's assignedSubtotal==0 branch).
    val chargePot = taxMinor + feesMinor - discountMinor + roundoffMinor
    val assignedSubtotal = owed.values.sum()
    if (chargePot != 0L && owed.isNotEmpty()) {
        val pids = owed.keys.sorted()
        var remaining = chargePot
        pids.forEachIndexed { i, pid ->
            val share = when {
                i == pids.lastIndex -> remaining
                assignedSubtotal > 0 -> chargePot * owed.getValue(pid) / assignedSubtotal
                else -> chargePot / pids.size
            }
            remaining -= share
            owed[pid] = (owed[pid] ?: 0L) + share
        }
    }

    return owed
}
