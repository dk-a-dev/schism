package ai.schism.split.sms.receipt.engine

import ai.schism.split.sms.receipt.ReceiptDraft
import ai.schism.split.sms.receipt.ReceiptLineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmHandoffTest {
    private fun c(t: String, xL: Int, xR: Int, y: Int) = Cell(t, xL, xR, y)

    @Test fun structuredRowText_joinsCellsInXOrderWithSeparator() {
        // Cells given out of x-order — the row must still render left-to-right.
        val row = Row(listOf(c("380.00", 430, 500, 80), c("Paneer Tikka", 20, 120, 80), c("2", 330, 345, 80)))
        assertEquals("Paneer Tikka | 2 | 380.00", structuredRowText(row))
    }

    @Test fun structuredRowText_singleCellHasNoSeparator() {
        assertEquals("VRAJ RESTAURANT", structuredRowText(Row(listOf(c("VRAJ RESTAURANT", 60, 320, 20)))))
    }

    @Test fun buildLlmHandoff_noPartialDraft_isJustStructuredRows() {
        val rows = listOf(
            Row(listOf(c("Dosa", 20, 120, 80), c("2", 330, 345, 80), c("120.00", 430, 500, 80))),
            Row(listOf(c("Total", 20, 120, 120), c("120.00", 430, 500, 120))),
        )
        val handoff = buildLlmHandoff(rows, null)
        assertEquals("Dosa | 2 | 120.00\nTotal | 120.00", handoff)
    }

    @Test fun buildLlmHandoff_blankRowsAreDropped() {
        val rows = listOf(
            Row(listOf(c("Dosa", 20, 120, 80))),
            Row(emptyList()),
        )
        assertEquals("Dosa", buildLlmHandoff(rows, null))
    }

    @Test fun buildLlmHandoff_partialDraft_reportsParsedItemsAndMissingTotals() {
        val rows = listOf(Row(listOf(c("Dosa", 20, 120, 80), c("120.00", 430, 500, 80))))
        val partial = ReceiptDraft(
            merchant = "Cafe",
            totalMinor = 0L, // total missing
            currency = "₹",
            date = null,
            lineItems = listOf(ReceiptLineItem(name = "Dosa", amountMinor = 12000L, qty = 1)),
            subtotalMinor = 0L,
            verified = false,
        )
        val handoff = buildLlmHandoff(rows, partial)
        assertTrue(handoff.contains("Items already read from this bill: Dosa (qty 1, amount 120.0)"))
        assertTrue(handoff.contains("Still missing or unverified:"))
        assertTrue(handoff.contains("grand total"))
        assertTrue(handoff.contains("subtotal"))
        assertTrue(handoff.contains("arithmetic verification"))
        assertTrue(handoff.contains("OCR rows (columns separated by \" | \"):\nDosa | 120.00"))
    }

    @Test fun buildLlmHandoff_verifiedDraftWithNoGaps_reportsOnlyItems() {
        val rows = listOf(Row(listOf(c("Dosa", 20, 120, 80), c("120.00", 430, 500, 80))))
        val partial = ReceiptDraft(
            merchant = "Cafe",
            totalMinor = 12000L,
            currency = "₹",
            date = null,
            lineItems = listOf(ReceiptLineItem(name = "Dosa", amountMinor = 12000L, qty = 1)),
            subtotalMinor = 12000L,
            verified = true,
        )
        val handoff = buildLlmHandoff(rows, partial)
        assertTrue(handoff.contains("Items already read from this bill:"))
        assertTrue(!handoff.contains("Still missing or unverified"))
    }
}
