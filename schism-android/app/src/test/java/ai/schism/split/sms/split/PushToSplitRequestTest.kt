package ai.schism.split.sms.split

import ai.schism.split.groups.data.Group
import ai.schism.split.groups.data.Participant
import ai.schism.split.sms.data.Transaction
import ai.schism.split.sms.data.TransactionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushToSplitRequestTest {

    private val txn = Transaction(
        id = "txn-abc",
        amountMinor = 45_000L,
        currency = "INR",
        merchant = "SWIGGY",
        bankName = "HDFC Bank",
        // 2026-07-05T00:00:00Z -> "2026-07" in every zone for the assertion below
        timestamp = 1_783_209_600_000L,
        status = TransactionStatus.UNASSIGNED,
    )

    private val group = Group(
        id = "g1",
        name = "Flatmates",
        information = "",
        currency = "₹",
        currencyCode = "INR",
        participants = listOf(
            Participant("p1", "g1", "You", userId = "u1"),
            Participant("p2", "g1", "Riya"),
            Participant("p3", "g1", "Sam"),
        ),
        activeParticipantId = "p1",
    )

    @Test
    fun buildsEvenSplitOverChosenParticipantsFromTransaction() {
        val request = buildPushToSplitRequest(
            txn, paidById = "p1", addedBy = "u1",
            title = "SWIGGY", participantIds = listOf("p1", "p2", "p3"),
        )

        assertEquals("SWIGGY", request.title)
        assertEquals(45_000L, request.amount)
        assertEquals("EVENLY", request.splitMode)
        assertEquals("p1", request.paidById)
        assertEquals("u1", request.addedBy)
        assertEquals(false, request.isReimbursement)

        // one share for each chosen participant
        assertEquals(listOf("p1", "p2", "p3"), request.paidFor.map { it.participantId })
        assertEquals(listOf(1L, 1L, 1L), request.paidFor.map { it.shares })

        // expenseDate is an ISO yyyy-MM-dd derived from the txn timestamp
        assertEquals(10, request.expenseDate?.length)
        assertEquals("2026-07", request.expenseDate?.take(7))
    }

    @Test
    fun splitsOnlyAmongIncludedParticipants() {
        val request = buildPushToSplitRequest(
            txn, paidById = "p1", addedBy = "u1",
            title = "Dinner", participantIds = listOf("p1", "p2"),
        )
        assertEquals("Dinner", request.title)
        assertEquals(listOf("p1", "p2"), request.paidFor.map { it.participantId })
    }

    @Test
    fun addedByIsNullWhenNotYou() {
        val request = buildPushToSplitRequest(
            txn, paidById = "p2", addedBy = null,
            title = "SWIGGY", participantIds = listOf("p2", "p3"),
        )
        assertNull(request.addedBy)
        assertEquals("p2", request.paidById)
    }
}
