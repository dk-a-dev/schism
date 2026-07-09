package ai.schism.split.sms.receipt.engine

import ai.schism.split.sms.receipt.ReceiptDraft

/**
 * Renders one OCR [Row] as its cells joined **in x-order with a clear column separator** (`" | "`),
 * instead of [Row.text]'s plain space-join — so a row like `Paneer Tikka | 2 | 190.00 | 380.00` keeps
 * its qty/rate/amount fields visually distinct for a downstream reader (human or model), rather than
 * collapsing them into one ambiguous run of tokens ("Paneer Tikka 2 190.00 380.00") where it's unclear
 * where the name ends and the numbers begin.
 */
fun structuredRowText(row: Row): String =
    row.cells.sortedBy { it.xLeft }.joinToString(" | ") { it.text.trim() }.trim()

/**
 * Builds the text handed to the on-device LLM ([ai.schism.split.core.ai.LlmExpenseParser.parseReceipt])
 * when the deterministic engine ([parseBill]) fails outright or produced a draft it couldn't verify.
 *
 * Two improvements over handing over raw `rows.map { it.text }`:
 * 1. Each row is rendered column-structured ([structuredRowText]) so numbers stay aligned/labelled
 *    instead of being flattened into one string the model has to re-segment from scratch.
 * 2. When the engine produced a [partial] draft, a short summary of what it already read — which
 *    items, which totals are missing or unverified — is prepended, so the model repairs against that
 *    structure instead of re-deriving the whole bill from noise.
 *
 * Returns just the structured OCR rows (one per line) when there's no partial draft to report.
 */
fun buildLlmHandoff(rows: List<Row>, partial: ReceiptDraft?): String {
    val structured = rows.map(::structuredRowText).filter { it.isNotEmpty() }.joinToString("\n")
    if (partial == null) return structured

    val notes = mutableListOf<String>()
    if (partial.lineItems.isNotEmpty()) {
        notes += "Items already read from this bill: " + partial.lineItems.joinToString("; ") { item ->
            "${item.name} (qty ${item.qty}, amount ${item.amountMinor / 100.0})"
        } + ". Keep these unless the OCR rows below clearly show otherwise."
    }
    val missing = mutableListOf<String>()
    if (partial.totalMinor <= 0) missing += "grand total"
    if (partial.subtotalMinor <= 0) missing += "subtotal"
    if (partial.lineItems.isEmpty()) missing += "line items"
    if (!partial.verified) missing += "arithmetic verification (the items and totals read so far don't reconcile)"
    if (missing.isNotEmpty()) {
        notes += "Still missing or unverified: " + missing.joinToString(", ") + "."
    }

    return if (notes.isEmpty()) {
        structured
    } else {
        notes.joinToString("\n") + "\n\nOCR rows (columns separated by \" | \"):\n" + structured
    }
}
