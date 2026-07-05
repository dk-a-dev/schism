package ai.schism.split.groups.detail.settle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettlementRequestTest {

    @Test
    fun buildsReimbursementFromPayerToPayee() {
        val request = buildSettlementRequest(
            fromParticipantId = "p1",
            toParticipantId = "p2",
            amountMinor = 50_000L,
            currency = "₹",
        )

        assertEquals("Settle up", request.title)
        assertEquals(50_000L, request.amount)
        assertEquals("p1", request.paidById)
        assertEquals("EVENLY", request.splitMode)
        assertTrue(request.isReimbursement)

        assertEquals(1, request.paidFor.size)
        assertEquals("p2", request.paidFor[0].participantId)
        assertEquals(1L, request.paidFor[0].shares)
    }
}
