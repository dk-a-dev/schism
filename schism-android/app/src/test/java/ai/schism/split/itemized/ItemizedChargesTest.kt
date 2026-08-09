package ai.schism.split.sms.itemized

import ai.schism.split.core.net.ClaimDto
import ai.schism.split.core.net.ClaimItemDto
import ai.schism.split.groups.data.Group
import ai.schism.split.groups.data.Participant
import ai.schism.split.sms.itemized.claim.previewOwes
import ai.schism.split.sms.receipt.ReceiptDraft
import ai.schism.split.sms.receipt.ReceiptLineItem
import ai.schism.split.sms.receipt.engine.parseMinor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The editable tax/charge lines: seeding them off a scanned draft, the add/edit/delete reducers, and
 * what they do to the bill total, the per-person shares and the Live Split claim pot.
 */
class ItemizedChargesTest {

    private fun group(vararg ids: String) = Group(
        id = "g1",
        name = "Trip",
        information = "",
        currency = "₹",
        currencyCode = "INR",
        participants = ids.map { Participant(id = it, groupId = "g1", name = it.uppercase()) },
        activeParticipantId = ids.firstOrNull(),
    )

    /** Two ₹500 items, shared 1× each by a and b, plus whatever [charges] say. */
    private fun state(charges: List<ChargeLine>, printedTotalMinor: Long = 0L) = ItemizedSplitUiState(
        loading = false,
        draft = printedTotalMinor.takeIf { it > 0 }?.let {
            ReceiptDraft(merchant = "Cafe", totalMinor = it, currency = "₹", date = null)
        },
        items = listOf(
            ReceiptLineItem("Biryani", 50_000, qty = 1),
            ReceiptLineItem("Coke", 50_000, qty = 1),
        ),
        charges = charges,
        groups = listOf(group("a", "b")),
        selectedGroupId = "g1",
        assignments = mapOf(
            0 to mapOf("a" to 1L, "b" to 1L),
            1 to mapOf("a" to 1L, "b" to 1L),
        ),
    )

    // ---- seeding from the engine's collapsed aggregates ----

    @Test
    fun `seeds one line per non-zero aggregate and sums back to the draft's net pot`() {
        // taxMinor is the engine's NET pot: tax(180) + fees(50) − discount(30) = 200.
        val draft = ReceiptDraft(
            merchant = "Cafe",
            totalMinor = 120_000,
            currency = "₹",
            date = null,
            taxMinor = 20_000,
            feesMinor = 5_000,
            discountMinor = 3_000,
        )
        val charges = seedCharges(draft, "Tax", "Charges", "Discount")

        assertEquals(
            listOf(
                ChargeLine("Tax", 18_000),
                ChargeLine("Charges", 5_000),
                ChargeLine("Discount", -3_000),
            ),
            charges,
        )
        assertEquals(draft.taxMinor, charges.sumOf { it.amountMinor })
    }

    @Test
    fun `seeds nothing for a bill with no charges, or no scan at all`() {
        val plain = ReceiptDraft(merchant = "Cafe", totalMinor = 100_000, currency = "₹", date = null)
        assertEquals(emptyList<ChargeLine>(), seedCharges(plain, "Tax", "Charges", "Discount"))
        assertEquals(emptyList<ChargeLine>(), seedCharges(null, "Tax", "Charges", "Discount"))
    }

    // ---- editing an amount ----

    @Test
    fun `correcting a misread amount recomputes the total and everyone's share`() {
        // OCR read ₹1,800 of GST on a ₹1,000 bill; it was really ₹180.
        val before = state(listOf(ChargeLine("GST", 180_000)))
        assertEquals(280_000, before.totalMinor)
        assertEquals(mapOf("a" to 140_000L, "b" to 140_000L), before.perPersonMinor)

        val after = before.updateCharge(0, "GST", 18_000)
        assertEquals(18_000, after.taxMinor)
        assertEquals(118_000, after.totalMinor)
        assertEquals(mapOf("a" to 59_000L, "b" to 59_000L), after.perPersonMinor)
    }

    @Test
    fun `a relabelled line keeps its amount`() {
        val after = state(listOf(ChargeLine("Tax", 18_000))).updateCharge(0, "  CGST 2.5  ", 18_000)
        assertEquals(listOf(ChargeLine("CGST 2.5", 18_000)), after.charges)
    }

    // ---- deleting a spurious line ----

    @Test
    fun `deleting a spurious line recomputes the total and everyone's share`() {
        val before = state(listOf(ChargeLine("GST", 18_000), ChargeLine("Table no. 12", 1_200)))
        assertEquals(119_200, before.totalMinor)

        val after = before.removeCharge(1)
        assertEquals(listOf(ChargeLine("GST", 18_000)), after.charges)
        assertEquals(118_000, after.totalMinor)
        assertEquals(mapOf("a" to 59_000L, "b" to 59_000L), after.perPersonMinor)
    }

    @Test
    fun `deleting the last line leaves the items alone`() {
        val after = state(listOf(ChargeLine("GST", 18_000))).removeCharge(0)
        assertEquals(0L, after.taxMinor)
        assertEquals(100_000, after.totalMinor)
        assertEquals(mapOf("a" to 50_000L, "b" to 50_000L), after.perPersonMinor)
    }

    // ---- adding a missing line ----

    @Test
    fun `adding a missed line recomputes the total and everyone's share`() {
        val after = state(emptyList()).addCharge("Service charge", 10_000)
        assertEquals(10_000, after.taxMinor)
        assertEquals(110_000, after.totalMinor)
        assertEquals(mapOf("a" to 55_000L, "b" to 55_000L), after.perPersonMinor)
    }

    @Test
    fun `a negative line reduces the bill and everyone's share`() {
        val after = state(listOf(ChargeLine("GST", 18_000))).addCharge("Discount", -20_000)
        assertEquals(-2_000, after.taxMinor)
        assertEquals(98_000, after.totalMinor)
        assertEquals(mapOf("a" to 49_000L, "b" to 49_000L), after.perPersonMinor)
    }

    // ---- the printed total is never silently overridden ----

    @Test
    fun `a mismatch against the printed total is surfaced, both over and under`() {
        val matching = state(listOf(ChargeLine("GST", 18_000)), printedTotalMinor = 118_000)
        assertNull(matching.totalMismatchMinor)

        val over = matching.updateCharge(0, "GST", 20_000)
        assertEquals(2_000L, over.totalMismatchMinor)

        val under = matching.removeCharge(0)
        assertEquals(-18_000L, under.totalMismatchMinor)
    }

    @Test
    fun `a bill with no printed total never reports a mismatch`() {
        assertNull(state(listOf(ChargeLine("GST", 18_000))).totalMismatchMinor)
    }

    // ---- invalid input ----

    @Test
    fun `invalid input is rejected without crashing`() {
        val s = state(listOf(ChargeLine("GST", 18_000)))
        // Not money: what the dialog's Save button gates on.
        assertNull(parseMinor("abc"))
        assertNull(parseMinor(""))
        assertNull(parseMinor("18%"))
        // Blank labels and out-of-range indices leave the state untouched.
        assertSame(s, s.addCharge("   ", 5_000))
        assertSame(s, s.updateCharge(0, "", 5_000))
        assertSame(s, s.updateCharge(7, "GST", 5_000))
        assertSame(s, s.updateCharge(-1, "GST", 5_000))
        assertSame(s, s.removeCharge(7))
        assertSame(s, s.removeCharge(-1))
    }

    // ---- the same edited pot drives the Live Split claim flow ----

    @Test
    fun `edited charges flow into the claim session's pot and preview`() {
        val edited = state(listOf(ChargeLine("GST", 180_000))).updateCharge(0, "GST", 18_000)

        val items = edited.items.mapIndexed { i, item -> ClaimItemDto(i, item.name, item.qty, item.amountMinor) }
        val claims = listOf(
            ClaimDto(0, "a", 1.0),
            ClaimDto(1, "b", 1.0),
        )
        // The screen sends the whole (edited) pot as taxMinor; fees/discount/roundoff stay 0.
        val owes = previewOwes(items, claims, taxMinor = edited.taxMinor)
        assertEquals(mapOf("a" to 59_000L, "b" to 59_000L), owes)
    }

    @Test
    fun `the expense request splits the edited pot, including when it goes negative`() {
        val edited = state(listOf(ChargeLine("GST", 18_000))).addCharge("Discount", -20_000)
        val assigned = edited.items.mapIndexed { i, item ->
            AssignedItem(item.amountMinor, edited.assignments[i].orEmpty())
        }
        val request = buildItemizedExpenseRequest(
            items = assigned,
            group = group("a", "b"),
            paidById = "a",
            addedBy = null,
            title = "Cafe",
            currency = "₹",
            dateIso = null,
            taxMinor = edited.taxMinor,
        )!!
        assertEquals(98_000L, request.amount)
        assertEquals(98_000L, request.paidFor.sumOf { it.shares })
    }
}
