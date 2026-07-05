package ai.schism.split.expense.edit.voice

import ai.schism.split.groups.data.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenExpenseParserTest {

    private val you = Participant(id = "you", groupId = "g", name = "You")
    private val riya = Participant(id = "riya", groupId = "g", name = "Riya")
    private val sam = Participant(id = "sam", groupId = "g", name = "Sam")
    private val participants = listOf(you, riya, sam)

    @Test
    fun parsesPaidForDinnerSplitWithNames() {
        val draft = parseSpokenExpense(
            "paid 800 for dinner split with Riya and Sam",
            participants,
            youParticipantId = "you",
        )
        assertEquals(80000L, draft.amountMinor)
        assertEquals("you", draft.payerParticipantId)
        assertEquals(listOf("you", "riya", "sam"), draft.paidForParticipantIds)
        assertEquals("Dinner", draft.title)
        assertFalse(draft.isPersonal)
    }

    @Test
    fun parsesNamedPayerAndTitle() {
        val draft = parseSpokenExpense(
            "Sam paid 1,200 for cab",
            participants,
            youParticipantId = "you",
        )
        assertEquals("sam", draft.payerParticipantId)
        assertEquals(120000L, draft.amountMinor)
        assertEquals("Cab", draft.title)
    }

    @Test
    fun parsesPersonalExpense() {
        val draft = parseSpokenExpense(
            "spent 50 on coffee just me",
            participants,
            youParticipantId = "you",
        )
        assertTrue(draft.isPersonal)
        assertEquals(listOf("you"), draft.paidForParticipantIds)
        assertEquals(5000L, draft.amountMinor)
    }

    @Test
    fun amountIsNullWhenNoNumberSpoken() {
        val draft = parseSpokenExpense(
            "dinner with Riya",
            participants,
            youParticipantId = "you",
        )
        assertNull(draft.amountMinor)
    }
}
