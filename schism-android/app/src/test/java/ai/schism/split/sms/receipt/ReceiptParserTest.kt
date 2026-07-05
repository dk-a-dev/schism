package ai.schism.split.sms.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptParserTest {

    @Test
    fun extractsMerchantTotalAndDateFromLabelledTotal() {
        val ocr = listOf(
            "BIG BAZAAR",
            "GST Invoice",
            "Milk 60.00",
            "Bread 40.00",
            "Sub Total 100.00",
            "Grand Total ₹118.00",
            "Date: 05/07/2026",
            "Thank you!",
        )
        val draft = parseReceipt(ocr)!!
        assertEquals("BIG BAZAAR", draft.merchant)
        assertEquals(11800L, draft.totalMinor) // labelled Grand Total wins over line items
        assertEquals("₹", draft.currency)
        assertEquals("2026-07-05", draft.date)
    }

    @Test
    fun fallsBackToLargestAmountWhenNoTotalLabel() {
        val draft = parseReceipt(listOf("Cafe Coffee Day", "Latte 250.00", "Cookie 90.50"))!!
        assertEquals("Cafe Coffee Day", draft.merchant)
        assertEquals(25000L, draft.totalMinor)
    }

    @Test
    fun handlesThousandsSeparatorsAndDollar() {
        val draft = parseReceipt(listOf("Apple Store", "MacBook", "Total: $1,299.00"))!!
        assertEquals("$", draft.currency)
        assertEquals(129900L, draft.totalMinor)
    }

    @Test
    fun parsesIsoDateForm() {
        val draft = parseReceipt(listOf("Store", "2026-12-31", "Total 500"))!!
        assertEquals("2026-12-31", draft.date)
        assertEquals(50000L, draft.totalMinor)
    }

    @Test
    fun returnsNullWhenNoAmount() {
        assertNull(parseReceipt(listOf("Just some text", "no numbers here")))
        assertNull(parseReceipt(emptyList()))
    }

    @Test
    fun extractsLineItemsExcludingTotalsAndMerchant() {
        val ocr = listOf(
            "BIG BAZAAR",
            "GST Invoice",
            "Milk 60.00",
            "Bread 40.00",
            "Sub Total 100.00",
            "Grand Total ₹118.00",
            "Date: 05/07/2026",
            "Thank you!",
        )
        val draft = parseReceipt(ocr)!!
        val items = draft.lineItems
        assertEquals(2, items.size)
        assertEquals("Milk", items[0].name)
        assertEquals(6000L, items[0].amountMinor)
        assertEquals("Bread", items[1].name)
        assertEquals(4000L, items[1].amountMinor)
    }

    @Test
    fun ignoresTaxAndSubtotalRowsInLineItems() {
        val draft = parseReceipt(
            listOf(
                "Cafe Coffee Day",
                "Latte 250.00",
                "Cookie 90.50",
                "Subtotal 340.50",
                "Tax 17.03",
                "Total 357.53",
            ),
        )!!
        assertEquals(listOf("Latte", "Cookie"), draft.lineItems.map { it.name })
        assertEquals(listOf(25000L, 9050L), draft.lineItems.map { it.amountMinor })
    }

    @Test
    fun noLineItemsWhenLinesAreLabelledTotalsOnly() {
        val draft = parseReceipt(listOf("Apple Store", "MacBook", "Total: $1,299.00"))!!
        // "MacBook" has no trailing amount, so there are no assignable item lines.
        assertEquals(emptyList<Any>(), draft.lineItems)
    }
}
