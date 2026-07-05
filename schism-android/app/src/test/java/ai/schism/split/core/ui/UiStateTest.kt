package ai.schism.split.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStateTest {
    @Test
    fun dataHoldsValue() {
        val state: UiState<List<String>> = UiState.Data(listOf("a", "b"))
        assertTrue(state is UiState.Data)
        assertEquals(listOf("a", "b"), (state as UiState.Data).value)
    }

    @Test
    fun statesAreDistinct() {
        val states = setOf<UiState<Nothing>>(UiState.Loading, UiState.Empty, UiState.Error("boom"))
        assertEquals(3, states.size)
        assertEquals("boom", UiState.Error("boom").message)
    }
}
