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
 * Corrector for the dropped-decimal defect (c): OCR reads a faint thermal decimal as a whole number,
 * so a "99.00" line becomes "9900" and parses 100× too large. When the item sum OVERSHOOTS a KNOWN
 * subtotal, this rescales the offending line(s) by ÷100 (amount and unit price) until the sum lands
 * back within tolerance of the subtotal — handling ONE dropped decimal or SEVERAL in the same bill.
 *
 * Deliberately general and conservative: it only ever divides down (a dropped decimal always reads
 * TOO large), only lines whose amount is an exact whole-rupee multiple (`% 100 == 0`, the fingerprint
 * of a lost `.00`), and — critically — only when a candidate rescale does NOT push the sum below the
 * subtotal (the guard), so it can never "correct" a genuinely-correct amount into a wrong one. It
 * rescales the largest guarded outlier first (a dropped decimal is 100× its true value, so it is the
 * biggest overshoot) and stops the instant the sum reconciles; an already-consistent bill returns
 * untouched. Returns the input unchanged when no guarded rescale sequence reconciles the sum.
 */
private fun correctDroppedDecimal(items: List<ReceiptLineItem>, subtotal: Long): List<ReceiptLineItem> {
    if (items.isEmpty()) return items
    var current = items
    val scaled = BooleanArray(items.size)
    // At most one rescale per item; the loop bound caps the work and guarantees termination.
    repeat(items.size) {
        val sum = current.sumOf { it.amountMinor }
        if (approx(sum, subtotal) || sum <= subtotal) return current
        // Guarded candidates: a whole-rupee outlier whose ÷100 rescale keeps the sum from dropping
        // below the subtotal (within tolerance) — so a legit line is never mistaken for an outlier.
        val idx = current.indices.filter { i ->
            !scaled[i] && current[i].amountMinor > 0 && current[i].amountMinor % 100 == 0L &&
                (sum - current[i].amountMinor + current[i].amountMinor / 100) >= subtotal - tolerance(subtotal)
        }.maxByOrNull { current[it].amountMinor } ?: return current
        scaled[idx] = true
        current = current.mapIndexed { i, item ->
            if (i == idx) item.copy(amountMinor = item.amountMinor / 100, unitPriceMinor = item.unitPriceMinor / 100) else item
        }
    }
    return current
}

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
    // Corrector pass (Fix #3): rescale a single ~100× outlier when it reconciles the item sum against
    // a known subtotal (dropped-decimal defect). `verified` may become true AFTER this correction.
    val corrected = totals.subtotal?.let { correctDroppedDecimal(items, it) } ?: items

    val itemSum = corrected.sumOf { it.amountMinor }
    val subtotal = totals.subtotal ?: itemSum
    val derivedGrandTotal = subtotal + totals.tax + totals.fees - totals.discount + totals.roundoff
    val grandTotal = totals.grandTotal ?: derivedGrandTotal

    val hasAnchor = totals.subtotal != null || totals.grandTotal != null
    val itemsMatchSubtotal = approx(itemSum, subtotal)
    val subtotalMatchesGrandTotal = approx(derivedGrandTotal, grandTotal)
    // Cross-check the units count against a printed "Total Qty" when present: a mismatch means a
    // row was missed or an item's qty misread (undercount) — the bill can't be trusted as verified.
    val qtyMatches = totals.totalQty == null || corrected.sumOf { it.qty } == totals.totalQty

    return Verified(
        items = corrected,
        subtotal = subtotal,
        tax = totals.tax,
        fees = totals.fees,
        discount = totals.discount,
        grandTotal = grandTotal,
        verified = hasAnchor && itemsMatchSubtotal && subtotalMatchesGrandTotal && qtyMatches,
        roundoff = totals.roundoff,
    )
}
