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
    fun restaurantBillExcludesMetadataAndJoinsWrappedNames() {
        // Real POSIST-style bill: phone numbers, covers, bill no, GST rows and PAY must never be
        // items; wrapped names ("Buff Oklahoma" ↵ "Smash 1 520.00") are joined; qty is read.
        val ocr = listOf(
            "SMASH GUYS",
            "Bill No.:T3",
            "Date:2026-07-05 20:36:47",
            "Covers:3",
            "Customer Detail",
            "Name: dev",
            "Mobile: 9555713188",
            "Item  Qty  Amt",
            "Flings Buffalo",
            "Chkn  1  348.00",
            "Sober Picante  1  248.00",
            "Hazelnut Cold",
            "Coffee  1  295.00",
            "Buff Oklahoma",
            "Smash  1  520.00",
            "Lamb Chilli",
            "Cheese  1  550.00",
            "Chicken Chilli",
            "Cheese Burg  1  450.00",
            "Total Qty:  6",
            "Sub Total:  2411.00",
            "GST@5%  120.55",
            "CGST @2.5  60.27",
            "SGST @2.5  60.27",
            "Round Off:  0.45",
            "Total Invoice Value  2532",
            "PAY:2532",
            "Feedback: mail@popoventures.com",
            "Powered by - POSIST",
        )
        val draft = parseReceipt(ocr)!!

        assertEquals("SMASH GUYS", draft.merchant)
        assertEquals(6, draft.lineItems.size)
        assertEquals(
            listOf(
                "Flings Buffalo Chkn", "Sober Picante", "Hazelnut Cold Coffee",
                "Buff Oklahoma Smash", "Lamb Chilli Cheese", "Chicken Chilli Cheese Burg",
            ),
            draft.lineItems.map { it.name },
        )
        assertEquals(
            listOf(34800L, 24800L, 29500L, 52000L, 55000L, 45000L),
            draft.lineItems.map { it.amountMinor },
        )
        assertEquals(List(6) { 1 }, draft.lineItems.map { it.qty })
        // No phone number / covers / GST / PAY leaked into the items.
        assertEquals(2411_00L, draft.lineItems.sumOf { it.amountMinor })
        assertEquals(253200L, draft.totalMinor)
        assertEquals(12055L, draft.taxMinor) // combined GST row wins over CGST+SGST sublines
        assertEquals(241100L, draft.subtotalMinor)
    }

    @Test
    fun qtyGreaterThanOneIsParsed() {
        val draft = parseReceipt(listOf("Dhaba", "Butter Roti  4  120.00", "Total 120.00"))!!
        assertEquals(1, draft.lineItems.size)
        assertEquals("Butter Roti", draft.lineItems[0].name)
        assertEquals(4, draft.lineItems[0].qty)
        assertEquals(12000L, draft.lineItems[0].amountMinor)
    }

    @Test
    fun rateColumnBillUsesLineAmountNotRate() {
        // "name qty rate amount" columns (common on Indian restaurant bills).
        val draft = parseReceipt(
            listOf(
                "SHARMA DHABA",
                "Paneer Tikka  2  190.00  380.00",
                "Dal Makhani  1  220.00  220.00",
                "Butter Naan  4  40.00  160.00",
                "Total  760.00",
            ),
        )!!
        assertEquals(listOf("Paneer Tikka", "Dal Makhani", "Butter Naan"), draft.lineItems.map { it.name })
        assertEquals(listOf(38000L, 22000L, 16000L), draft.lineItems.map { it.amountMinor })
        assertEquals(listOf(2, 1, 4), draft.lineItems.map { it.qty })
        assertEquals(76000L, draft.totalMinor)
    }

    @Test
    fun leadingQtyCafeStyle() {
        val draft = parseReceipt(listOf("Blue Tokai", "2 x Cappuccino  7.00", "1 x Croissant  3.50", "Total 10.50"))!!
        assertEquals(listOf("Cappuccino", "Croissant"), draft.lineItems.map { it.name })
        assertEquals(listOf(2, 1), draft.lineItems.map { it.qty })
        assertEquals(listOf(700L, 350L), draft.lineItems.map { it.amountMinor })
    }

    @Test
    fun dottedLeadersAndCurrencyPrefix() {
        val draft = parseReceipt(
            listOf("Udupi Palace", "Masala Dosa......85.00", "Filter Coffee ₹40.00", "Total ₹125.00"),
        )!!
        assertEquals(listOf("Masala Dosa", "Filter Coffee"), draft.lineItems.map { it.name })
        assertEquals(listOf(8500L, 4000L), draft.lineItems.map { it.amountMinor })
    }

    @Test
    fun plainIntegerTotalIsNotTruncated() {
        // Regression: "2532" once parsed as 253 (three-digit group), corrupting the total.
        val draft = parseReceipt(listOf("Store", "Snack 100.00", "Total Invoice Value 2532"))!!
        assertEquals(253200L, draft.totalMinor)
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
