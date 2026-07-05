package ai.schism.split.core.db

import ai.schism.split.core.net.ExpenseDto
import ai.schism.split.core.net.GroupDto
import ai.schism.split.core.net.PaidForDto
import ai.schism.split.core.net.ParticipantDto
import org.junit.Assert.assertEquals
import org.junit.Test

class MappersTest {
    @Test
    fun groupDtoRoundTripsToDomain() {
        val dto = GroupDto(
            id = "g1", name = "Trip", information = "info", currency = "₹", currencyCode = "INR",
            createdAt = "2026-07-05T00:00:00Z",
            participants = listOf(ParticipantDto("p1", "g1", "Dev"), ParticipantDto("p2", "g1", "Sam")),
        )

        val relation = GroupWithParticipants(dto.toEntity(), dto.participantEntities())
        val domain = relation.toDomain()

        assertEquals("g1", domain.id)
        assertEquals("Trip", domain.name)
        assertEquals("INR", domain.currencyCode)
        assertEquals(2, domain.participants.size)
        assertEquals("Dev", domain.participants[0].name)
        assertEquals("g1", domain.participants[0].groupId)
    }

    @Test
    fun expenseDtoRoundTripsToDomain() {
        val dto = ExpenseDto(
            id = "e1", groupId = "g1", title = "Dinner", amount = 1200, categoryId = 7,
            expenseDate = "2026-07-05T00:00:00Z", paidById = "p1", splitMode = "EVENLY",
            paidFor = listOf(PaidForDto("p1", 100), PaidForDto("p2", 100)),
        )

        val domain = dto.toWithPaidFor().toDomain()

        assertEquals("e1", domain.id)
        assertEquals(1200L, domain.amount)
        assertEquals(7, domain.categoryId)
        assertEquals(2, domain.paidFor.size)
        assertEquals(100L, domain.paidFor[0].shares)
    }
}
