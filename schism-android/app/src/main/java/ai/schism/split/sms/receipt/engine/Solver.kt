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
 * so a single "99.00" line becomes "9900" and parses 100× too large. When the item sum overshoots a
 * KNOWN subtotal and dividing exactly one item's amount by 100 brings the sum back within tolerance
 * of that subtotal, that one item is rescaled (amount and unit price ÷100). Deliberately general and
 * conservative: it fires only for a single outlier that arithmetically explains the whole overshoot,
 * so a genuinely-correct bill (already summing to subtotal) is never touched. Returns the input
 * unchanged when no single-outlier rescale reconciles the sum.
 */
private fun correctDroppedDecimal(items: List<ReceiptLineItem>, subtotal: Long): List<ReceiptLineItem> {
    if (items.isEmpty() || approx(items.sumOf { it.amountMinor }, subtotal)) return items
    val sum = items.sumOf { it.amountMinor }
    val outlier = items.indices.firstOrNull { i ->
        val item = items[i]
        item.amountMinor % 100 == 0L && approx(sum - item.amountMinor + item.amountMinor / 100, subtotal)
    } ?: return items
    return items.mapIndexed { i, item ->
        if (i == outlier) {
            item.copy(amountMinor = item.amountMinor / 100, unitPriceMinor = item.unitPriceMinor / 100)
        } else {
            item
        }
    }
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
