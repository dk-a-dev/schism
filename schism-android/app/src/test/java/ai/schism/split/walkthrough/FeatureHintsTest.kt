package ai.schism.split.walkthrough

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeatureHintsTest {
    private val all = FeatureHint.entries.toSet()

    @Test
    fun hintsHaveTheRequiredPriorityOrder() {
        assertEquals(
            listOf("sms_opt_in", "ocr_download", "participant_invite", "upi_settle", "live_split_host"),
            FeatureHint.entries.map { it.id },
        )
    }

    @Test
    fun onlyTheHighestPriorityRelevantHintIsEverReturned() {
        assertEquals(FeatureHint.SMS_OPT_IN, selectHint(all, dismissed = emptySet()))
        assertEquals(
            FeatureHint.UPI_SETTLE,
            selectHint(setOf(FeatureHint.LIVE_SPLIT_HOST, FeatureHint.UPI_SETTLE), emptySet()),
        )
    }

    @Test
    fun anIrrelevantScreenShowsNothing() {
        assertNull(selectHint(relevant = emptySet(), dismissed = emptySet()))
    }

    @Test
    fun dismissingAHintPromotesTheNextOneAndNeverReturns() {
        val afterFirst = selectHint(all, dismissed = setOf("sms_opt_in"))
        assertEquals(FeatureHint.OCR_DOWNLOAD, afterFirst)

        assertNull(selectHint(all, dismissed = FeatureHint.entries.map { it.id }.toSet()))
    }

    @Test
    fun noHintIsEligibleWhileTheTourOrAnotherSurfaceOwnsTheScreen() {
        assertNull(selectHint(all, dismissed = emptySet(), suppressed = true))
    }

    @Test
    fun dismissalIsTheOnlyThingAHintCanDo() {
        // A hint carries copy and a target and nothing else — there is no enum member that could
        // flip the SMS opt-in bit, request a permission, or start the OCR download.
        assertEquals(
            setOf("id", "titleRes", "bodyRes", "target"),
            FeatureHint::class.java.declaredFields
                .filterNot { it.isSynthetic || it.isEnumConstant || it.name.startsWith("$") }
                .map { it.name }
                .toSet(),
        )
        assertEquals(WalkthroughTargetId.SMS_OPT_IN, FeatureHint.SMS_OPT_IN.target)
    }
}
