package ai.schism.split.walkthrough

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoRepositoryTest {
    @Test
    fun weekendDinnerFixtureIsDeterministicAcrossRepositories() {
        val first = DemoRepository().snapshot
        val second = DemoRepository().snapshot

        assertEquals(first, second)
        assertNotSame(first, second)
        assertEquals("Weekend dinner", first.group.name)
        assertEquals(listOf("You", "Maya", "Kabir"), first.group.participants.map { it.name })
        assertEquals("The Green Table", first.receipt.merchant)
        assertEquals(132_300L, first.receipt.totalMinor)
        assertEquals(4, first.receipt.items.size)
    }

    @Test
    fun itemAssignmentsTotalsAndBalancesReconcileExactly() {
        val demo = DemoRepository().snapshot

        assertTrue(demo.receipt.items.all { it.assignedParticipantIds.isNotEmpty() })
        assertEquals(demo.receipt.totalMinor, demo.participantTotalsMinor.values.sum())
        assertEquals(
            mapOf("demo-you" to 43_050L, "demo-maya" to 52_500L, "demo-kabir" to 36_750L),
            demo.participantTotalsMinor,
        )
        assertEquals(89_250L, demo.balances.perParticipant.getValue("demo-you").total)
        assertEquals(-52_500L, demo.balances.perParticipant.getValue("demo-maya").total)
        assertEquals(-36_750L, demo.balances.perParticipant.getValue("demo-kabir").total)
        assertEquals(demo.receipt.totalMinor, demo.balances.perParticipant.values.sumOf { it.paid })
        assertEquals(89_250L, demo.balances.reimbursements.sumOf { it.amount })
    }

    @Test
    fun assignmentMutationStaysInMemoryAndResetRestoresTheFixture() {
        val repository: DemoDataSource = DemoRepository()
        val original = repository.snapshot

        val changed = repository.assign("paneer-tikka", setOf("demo-kabir"))
        assertEquals(setOf("demo-kabir"), changed.receipt.items.first().assignedParticipantIds)
        assertFalse(changed.participantTotalsMinor == original.participantTotalsMinor)

        assertEquals(original, repository.reset())
        assertEquals(original, repository.snapshot)
    }

    @Test
    fun demoLayerHasNoInjectableProductionDependenciesOrSerializableDtos() {
        val constructor = DemoRepository::class.java.declaredConstructors.single()
        assertEquals(0, constructor.parameterCount)

        val exposedTypes = listOf(
            DemoSnapshot::class.java,
            DemoGroup::class.java,
            DemoParticipant::class.java,
            DemoReceipt::class.java,
            DemoReceiptItem::class.java,
            DemoBalances::class.java,
        )
        assertTrue(exposedTypes.all { it.packageName == "ai.schism.split.walkthrough" })
        assertTrue(exposedTypes.none { java.io.Serializable::class.java.isAssignableFrom(it) })
    }
}
