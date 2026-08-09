package ai.schism.split.core.ads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single ad rule, exhaustively. Every one of these cases must render nothing; only the fully
 * eligible free account on the Spending surface gets the one banner.
 */
class AdEligibilityTest {

    private val eligible = AdContext(
        adsEnabledByBackend = true,
        entitlementKnown = true,
        isPlus = false,
        consentGranted = true,
        accountAgeDays = 30,
        meaningfulActions = 10,
        onAdSurface = true,
        foreground = true,
    )

    @Test
    fun `fully eligible free account sees the banner`() {
        assertTrue(eligible.isEligible())
    }

    @Test
    fun `plus subscribers never see an ad`() {
        assertFalse(eligible.copy(isPlus = true).isEligible())
    }

    @Test
    fun `unknown entitlement never shows an ad`() {
        assertFalse(eligible.copy(entitlementKnown = false).isEligible())
    }

    @Test
    fun `backend ads flag defaults off and suppresses the ad`() {
        assertFalse(AdContext().isEligible())
        assertFalse(eligible.copy(adsEnabledByBackend = false).isEligible())
    }

    @Test
    fun `no ad without consent`() {
        assertFalse(eligible.copy(consentGranted = false).isEligible())
    }

    @Test
    fun `account younger than seven days sees no ad`() {
        assertFalse(eligible.copy(accountAgeDays = 6).isEligible())
        assertFalse(eligible.copy(accountAgeDays = 0).isEligible())
        assertTrue(eligible.copy(accountAgeDays = MIN_ACCOUNT_AGE_DAYS).isEligible())
    }

    @Test
    fun `fewer than three meaningful actions sees no ad`() {
        assertFalse(eligible.copy(meaningfulActions = 2).isEligible())
        assertTrue(eligible.copy(meaningfulActions = MIN_MEANINGFUL_ACTIONS).isEligible())
    }

    @Test
    fun `every surface other than spending is off limits`() {
        // Onboarding, demo, transactions, receipts, groups, balances, settlement, Live Split and
        // purchase flows all pass onAdSurface = false.
        assertFalse(eligible.copy(onAdSurface = false).isEligible())
    }

    @Test
    fun `backgrounded host never requests an ad`() {
        assertFalse(eligible.copy(foreground = false).isEligible())
    }
}
