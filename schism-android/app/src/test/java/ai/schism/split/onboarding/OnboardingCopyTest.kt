package ai.schism.split.onboarding

import ai.schism.split.R
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OnboardingCopyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun valueTourHasFourTruthfulPagesInJourneyOrder() {
        assertEquals(4, walkPages.size)

        val copy = walkPages.map { page ->
            "${context.getString(page.titleRes)} ${context.getString(page.bodyRes)}".lowercase()
        }
        assertTrue(copy[0].contains("group") && copy[0].contains("balance"))
        assertTrue(copy[1].contains("receipt") && copy[1].contains("sms"))
        assertTrue(copy[2].contains("live split"))
        assertTrue(copy[3].contains("on-device") && copy[3].contains("private"))

        val receiptCopy = copy[1]
        assertFalse(receiptCopy.contains("split by ai"))
        assertFalse(receiptCopy.contains("reads each line item"))
        assertTrue(receiptCopy.contains("review"))
    }

    @Test
    fun everyTourIllustrationHasAContentDescription() {
        walkPages.forEach { page ->
            assertTrue(context.getString(page.illustrationContentDescriptionRes).isNotBlank())
        }
    }

    @Test
    fun skipCopyRemainsAvailable() {
        assertEquals("Skip", context.getString(R.string.onboarding_skip))
    }

    @Test
    fun passwordRequiresEightCharacters() {
        assertFalse(isPasswordValid("1234567"))
        assertTrue(isPasswordValid("12345678"))
    }

    @Test
    fun phoneIsOptionalWithoutClaimingAutomaticLinking() {
        assertTrue(context.getString(R.string.onboarding_phone_label).contains("optional", ignoreCase = true))
        val supportingCopy = context.getString(R.string.onboarding_phone_supporting).lowercase()
        assertFalse(supportingCopy.contains("automatically"))
        assertFalse(supportingCopy.contains("auto-link"))
    }
}
