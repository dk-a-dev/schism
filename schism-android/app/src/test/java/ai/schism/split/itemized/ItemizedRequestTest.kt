package ai.schism.split.sms.itemized

import ai.schism.split.groups.data.Group
import ai.schism.split.groups.data.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ItemizedRequestTest {

    private fun group(vararg ids: String) = Group(
        id = "g1",
        name = "Trip",
        information = "",
        currency = "₹",
        currencyCode = "INR",
        participants = ids.map { Participant(id = it, groupId = "g1", name = it.uppercase()) },
        activeParticipantId = ids.firstOrNull(),
    )

    @Test
    fun evenSplitAcrossAssignees() {
        val items = listOf(
            AssignedItem(amountMinor = 10000L, participantIds = listOf("a", "b")), // 50/50
            AssignedItem(amountMinor = 6000L, participantIds = listOf("b")), // all b
        )
        val req = buildItemizedExpenseRequest(
            items = items,
            group = group("a", "b"),
            paidById = "a",
            addedBy = null,
            title = "Dinner",
            currency = "₹",
            dateIso = "2026-07-05",
        )!!

        assertEquals("BY_AMOUNT", req.splitMode)
        assertEquals(16000L, req.amount)
        assertEquals("Dinner", req.title)
        assertEquals("2026-07-05", req.expenseDate)
        val owed = req.paidFor.associate { it.participantId to it.shares }
        assertEquals(5000L, owed["a"])
        assertEquals(11000L, owed["b"])
    }

    @Test
    fun remainderPenniesGoToLastSharerAndSplitIsExact() {
        // 100 minor split 3 ways = 33/33/34 (the last sharer absorbs rounding so the split is exact).
        val items = listOf(AssignedItem(amountMinor = 100L, participantIds = listOf("a", "b", "c")))
        val req = buildItemizedExpenseRequest(
            items = items,
            group = group("a", "b", "c"),
            paidById = "a",
            addedBy = null,
            title = "Snacks",
            currency = "₹",
            dateIso = null,
        )!!

        val owed = req.paidFor.associate { it.participantId to it.shares }
        assertEquals(33L, owed["a"])
        assertEquals(33L, owed["b"])
        assertEquals(34L, owed["c"])
        // The split is exact: shares sum back to the item amount.
        assertEquals(100L, req.paidFor.sumOf { it.shares })
        assertEquals(100L, req.amount)
    }

    @Test
    fun weightedSharesSplitProportionally() {
        // ₹300 dish, dev had 2 of it, ru had 1 → dev owes 200, ru owes 100.
        val items = listOf(AssignedItem(amountMinor = 30000L, shares = mapOf("dev" to 2L, "ru" to 1L)))
        val req = buildItemizedExpenseRequest(
            items = items,
            group = group("dev", "ru"),
            paidById = "dev",
            addedBy = null,
            title = "Dinner",
            currency = "₹",
            dateIso = null,
        )!!

        val owed = req.paidFor.associate { it.participantId to it.shares }
        assertEquals(20000L, owed["dev"])
        assertEquals(10000L, owed["ru"])
        assertEquals(30000L, req.amount)
    }

    @Test
    fun participantsOwingNothingAreOmitted() {
        val items = listOf(AssignedItem(amountMinor = 500L, participantIds = listOf("a")))
        val req = buildItemizedExpenseRequest(
            items = items,
            group = group("a", "b"),
            paidById = "a",
            addedBy = null,
            title = "Coffee",
            currency = "₹",
            dateIso = null,
        )!!
        assertEquals(listOf("a"), req.paidFor.map { it.participantId })
        assertEquals(500L, req.paidFor.single().shares)
    }

    @Test
    fun returnsNullWhenNothingAssigned() {
        val items = listOf(
            AssignedItem(amountMinor = 500L, participantIds = emptyList()),
            AssignedItem(amountMinor = 300L, participantIds = emptyList()),
        )
        assertNull(
            buildItemizedExpenseRequest(
                items = items,
                group = group("a", "b"),
                paidById = "a",
                addedBy = null,
                title = "Nothing",
                currency = "₹",
                dateIso = null,
            ),
        )
    }
}
