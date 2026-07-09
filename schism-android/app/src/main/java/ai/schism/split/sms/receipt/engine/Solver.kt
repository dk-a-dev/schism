package ai.schism.split.sms.receipt.engine

import ai.schism.split.sms.receipt.ReceiptLineItem
import kotlin.math.abs
import kotlin.math.ceil

/**
 * A reconciled bill: the line items alongside a totals breakdown that's been checked for internal
 * arithmetic consistency (`verified`). A missing [subtotal]/[grandTotal] on the input [Totals] is
 * derived here, so this is always a complete breakdown even when the OCR'd totals region was
 * partial.
 */
data class Verified(
    val items: List<ReceiptLineItem>,
    val subtotal: Long,
    val tax: Long,
    val fees: Long,
    val discount: Long,
    val grandTotal: Long,
    val verified: Boolean,
    val roundoff: Long = 0,
)

/** Tolerance (minor units) for an arithmetic invariant against [reference]: 1% of it, floored at 200 (₹2/$2). */
private fun tolerance(reference: Long): Long = maxOf(ceil(abs(reference) * 0.01).toLong(), 200L)

/** True when [value] lands within [tolerance] of [reference]. */
private fun approx(value: Long, reference: Long): Boolean = abs(value - reference) <= tolerance(reference)

/**
 * Reconciles [items]' arithmetic against [totals], the constraint solver that makes a bill's
 * numbers provably consistent: `Σitems ≈ subtotal` and
 * `subtotal+tax+fees−discount+roundoff ≈ grandTotal`, each within [tolerance]. `verified` is true
 * iff both invariants hold AND at least one of [Totals.subtotal]/[Totals.grandTotal] was actually
 * read off the bill — with neither, there is no independent anchor to check the arithmetic
 * against (both sides of the grand-total invariant would be derived from the same inputs, so it
 * would trivially "pass" without having verified anything).
 *
 * A missing [Totals.subtotal] is derived from the item sum (so it trivially matches); a missing
 * [Totals.grandTotal] is derived from `subtotal+tax+fees−discount+roundoff` (ditto) — either way
 * the returned [Verified] always carries concrete, non-null totals. The split's charge pot is
 * `tax+fees−discount+roundoff` (signed): callers distribute it proportionally across the items.
 */
fun reconcile(items: List<ReceiptLineItem>, totals: Totals): Verified {
    val itemSum = items.sumOf { it.amountMinor }
    val subtotal = totals.subtotal ?: itemSum
    val derivedGrandTotal = subtotal + totals.tax + totals.fees - totals.discount + totals.roundoff
    val grandTotal = totals.grandTotal ?: derivedGrandTotal

    val hasAnchor = totals.subtotal != null || totals.grandTotal != null
    val itemsMatchSubtotal = approx(itemSum, subtotal)
    val subtotalMatchesGrandTotal = approx(derivedGrandTotal, grandTotal)

    return Verified(
        items = items,
        subtotal = subtotal,
        tax = totals.tax,
        fees = totals.fees,
        discount = totals.discount,
        grandTotal = grandTotal,
        verified = hasAnchor && itemsMatchSubtotal && subtotalMatchesGrandTotal,
        roundoff = totals.roundoff,
    )
}
