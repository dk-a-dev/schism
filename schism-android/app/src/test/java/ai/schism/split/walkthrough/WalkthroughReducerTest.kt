package ai.schism.split.walkthrough

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkthroughReducerTest {
    @Test
    fun offerAcceptAndSkipHaveExplicitStates() {
        val offered = reduce(WalkthroughState(), WalkthroughAction.Offer)
        assertEquals(WalkthroughStatus.OFFERED, offered.status)
        assertNull(offered.currentStep)

        val accepted = reduce(offered, WalkthroughAction.Accept)
        assertEquals(WalkthroughStatus.ACTIVE, accepted.status)
        assertEquals(WalkthroughStep.GROUP, accepted.currentStep)

        val skipped = reduce(accepted, WalkthroughAction.Skip)
        assertEquals(WalkthroughStatus.SKIPPED, skipped.status)
        assertNull(skipped.currentStep)
    }

    @Test
    fun stepsHaveTheRequiredStableOrder() {
        assertEquals(
            listOf(
                WalkthroughStep.GROUP,
                WalkthroughStep.RECEIPT,
                WalkthroughStep.ASSIGN,
                WalkthroughStep.BALANCES,
                WalkthroughStep.LIVE_SPLIT,
            ),
            WalkthroughStep.entries,
        )
    }

    @Test
    fun continueAndBackMoveWithinOrderedSteps() {
        val group = activeAt(WalkthroughStep.GROUP)
        assertEquals(WalkthroughStep.GROUP, reduce(group, WalkthroughAction.Back).currentStep)

        val receipt = reduce(group, WalkthroughAction.Continue)
        assertEquals(WalkthroughStep.RECEIPT, receipt.currentStep)
        assertEquals(WalkthroughStep.GROUP, reduce(receipt, WalkthroughAction.Back).currentStep)
    }

    @Test
    fun realActionOnlyCompletesItsCurrentStep() {
        val receipt = activeAt(WalkthroughStep.RECEIPT)

        assertEquals(
            receipt,
            reduce(receipt, WalkthroughAction.RealActionCompleted(WalkthroughStep.ASSIGN)),
        )
        assertEquals(
            WalkthroughStep.ASSIGN,
            reduce(receipt, WalkthroughAction.RealActionCompleted(WalkthroughStep.RECEIPT)).currentStep,
        )
    }

    @Test
    fun finalStepCompletionFinishesTheWalkthrough() {
        val result = reduce(
            activeAt(WalkthroughStep.LIVE_SPLIT),
            WalkthroughAction.RealActionCompleted(WalkthroughStep.LIVE_SPLIT),
        )

        assertEquals(WalkthroughStatus.COMPLETED, result.status)
        assertNull(result.currentStep)
    }

    @Test
    fun restoreKeepsAnActiveProcessRestoredStepAndHints() {
        val restored = WalkthroughState(
            status = WalkthroughStatus.ACTIVE,
            currentStep = WalkthroughStep.ASSIGN,
            dismissedHintIds = setOf("sms_opt_in"),
        )

        assertEquals(restored, reduce(WalkthroughState(), WalkthroughAction.Restore(restored)))
    }

    @Test
    fun replayRestartsAndResetTipsOnlyClearsDismissals() {
        val completed = WalkthroughState(
            status = WalkthroughStatus.COMPLETED,
            dismissedHintIds = setOf("sms_opt_in", "ocr_download"),
        )
        val replayed = reduce(completed, WalkthroughAction.Replay)
        assertEquals(WalkthroughStatus.ACTIVE, replayed.status)
        assertEquals(WalkthroughStep.GROUP, replayed.currentStep)
        assertEquals(completed.dismissedHintIds, replayed.dismissedHintIds)

        val reset = reduce(replayed, WalkthroughAction.ResetTips)
        assertTrue(reset.dismissedHintIds.isEmpty())
        assertEquals(WalkthroughStatus.ACTIVE, reset.status)
        assertEquals(WalkthroughStep.GROUP, reset.currentStep)
    }

    @Test
    fun signOutClearsTransientAndAccountScopedState() {
        val state = activeAt(WalkthroughStep.BALANCES).copy(dismissedHintIds = setOf("sms_opt_in"))

        assertEquals(WalkthroughState(), reduce(state, WalkthroughAction.SignOut))
    }

    @Test
    fun restoringAnOlderSchemaOffersTheCurrentTour() {
        val old = WalkthroughState(
            version = 0,
            status = WalkthroughStatus.COMPLETED,
            dismissedHintIds = setOf("old_hint"),
        )

        val upgraded = reduce(WalkthroughState(), WalkthroughAction.Restore(old))

        assertEquals(CURRENT_WALKTHROUGH_SCHEMA_VERSION, upgraded.version)
        assertEquals(WalkthroughStatus.OFFERED, upgraded.status)
        assertNull(upgraded.currentStep)
        assertTrue(upgraded.dismissedHintIds.isEmpty())
    }

    private fun activeAt(step: WalkthroughStep) = WalkthroughState(
        status = WalkthroughStatus.ACTIVE,
        currentStep = step,
    )
}
