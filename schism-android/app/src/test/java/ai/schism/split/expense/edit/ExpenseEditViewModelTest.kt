package ai.schism.split.expense.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JUnit tests of the pure [buildExpenseRequest] / [parseAmountToMinor] functions.
 * No Robolectric or MockWebServer needed — these functions have no Android/network deps.
 */
class ExpenseEditViewModelTest {

    private fun input(
        id: String,
        selected: Boolean = true,
        weight: Long = 1L,
        percentBasisPoints: Long = 0L,
        amountMinor: Long = 0L,
    ) = ParticipantInput(id, selected, weight, percentBasisPoints, amountMinor)

    private fun form(
        title: String = "Dinner",
        amountMinor: Long = 4200L,
        splitMode: SplitMode = SplitMode.EVENLY,
        participants: List<ParticipantInput> = listOf(input("p1"), input("p2")),
    ) = ExpenseForm(
        title = title,
        amountMinor = amountMinor,
        categoryId = 3,
        expenseDate = "2026-07-05",
        paidById = "p1",
        splitMode = splitMode,
        isReimbursement = false,
        notes = "  note  ",
        participants = participants,
    )

    @Test
    fun evenlyEncodesEqualShares() {
        val request = buildExpenseRequest(form(splitMode = SplitMode.EVENLY)).getOrThrow()
        assertEquals("EVENLY", request.splitMode)
        assertEquals(listOf(1L, 1L), request.paidFor.map { it.shares })
        assertEquals(listOf("p1", "p2"), request.paidFor.map { it.participantId })
        assertEquals("Dinner", request.title)
        assertEquals("note", request.notes)
    }

    @Test
    fun bySharesEncodesWeights() {
        val result = buildExpenseRequest(
            form(splitMode = SplitMode.BY_SHARES, participants = listOf(input("p1", weight = 2), input("p2", weight = 1))),
        )
        assertEquals(listOf(2L, 1L), result.getOrThrow().paidFor.map { it.shares })
    }

    @Test
    fun byPercentageSummingTo10000Succeeds() {
        val result = buildExpenseRequest(
            form(
                splitMode = SplitMode.BY_PERCENTAGE,
                participants = listOf(input("p1", percentBasisPoints = 6000), input("p2", percentBasisPoints = 4000)),
            ),
        )
        assertEquals(listOf(6000L, 4000L), result.getOrThrow().paidFor.map { it.shares })
    }

    @Test
    fun byPercentageNotSummingTo10000Fails() {
        val result = buildExpenseRequest(
            form(
                splitMode = SplitMode.BY_PERCENTAGE,
                participants = listOf(input("p1", percentBasisPoints = 6000), input("p2", percentBasisPoints = 3000)),
            ),
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun byAmountSummingToTotalSucceeds() {
        val result = buildExpenseRequest(
            form(
                amountMinor = 4200,
                splitMode = SplitMode.BY_AMOUNT,
                participants = listOf(input("p1", amountMinor = 2000), input("p2", amountMinor = 2200)),
            ),
        )
        assertEquals(listOf(2000L, 2200L), result.getOrThrow().paidFor.map { it.shares })
    }

    @Test
    fun byAmountNotSummingToTotalFails() {
        val result = buildExpenseRequest(
            form(
                amountMinor = 4200,
                splitMode = SplitMode.BY_AMOUNT,
                participants = listOf(input("p1", amountMinor = 2000), input("p2", amountMinor = 2000)),
            ),
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun zeroAmountFails() = assertTrue(buildExpenseRequest(form(amountMinor = 0)).isFailure)

    @Test
    fun negativeAmountFails() = assertTrue(buildExpenseRequest(form(amountMinor = -100)).isFailure)

    @Test
    fun amountOverOneBillionFails() = assertTrue(buildExpenseRequest(form(amountMinor = 1_000_000_001L)).isFailure)

    @Test
    fun amountAtLimitSucceeds() = assertTrue(buildExpenseRequest(form(amountMinor = 1_000_000_000L)).isSuccess)

    @Test
    fun noSelectedParticipantsFails() {
        val result = buildExpenseRequest(
            form(participants = listOf(input("p1", selected = false), input("p2", selected = false))),
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun blankTitleFails() = assertTrue(buildExpenseRequest(form(title = "   ")).isFailure)

    @Test
    fun bySharesNonPositiveWeightFails() {
        val result = buildExpenseRequest(
            form(splitMode = SplitMode.BY_SHARES, participants = listOf(input("p1", weight = 0), input("p2", weight = 1))),
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun onlySelectedParticipantsAreEncoded() {
        val result = buildExpenseRequest(
            form(
                splitMode = SplitMode.EVENLY,
                participants = listOf(input("p1", selected = true), input("p2", selected = false), input("p3", selected = true)),
            ),
        )
        assertEquals(listOf("p1", "p3"), result.getOrThrow().paidFor.map { it.participantId })
    }

    @Test
    fun parseAmountToMinorHandlesDecimals() {
        assertEquals(4200L, parseAmountToMinor("42"))
        assertEquals(4250L, parseAmountToMinor("42.5"))
        assertEquals(4250L, parseAmountToMinor("42.50"))
        assertEquals(5L, parseAmountToMinor("0.05"))
        assertEquals(0L, parseAmountToMinor("0"))
    }

    @Test
    fun parseAmountToMinorRejectsInvalidInput() {
        assertNull(parseAmountToMinor("abc"))
        assertNull(parseAmountToMinor(""))
        assertNull(parseAmountToMinor("42."))
        assertNull(parseAmountToMinor("1.234"))
        assertNull(parseAmountToMinor("-5"))
    }
}
