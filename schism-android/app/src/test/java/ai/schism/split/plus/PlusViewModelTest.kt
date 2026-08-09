package ai.schism.split.plus

import ai.schism.split.core.billing.EntitlementState
import ai.schism.split.core.billing.LiveSplitAllowance
import ai.schism.split.core.billing.MonetizationConfig
import ai.schism.split.core.billing.PLUS_BASE_PLAN_ANNUAL
import ai.schism.split.core.billing.PLUS_BASE_PLAN_MONTHLY
import ai.schism.split.core.billing.PLUS_PRODUCT_ID
import ai.schism.split.core.billing.PlusPlan
import ai.schism.split.core.billing.PurchasePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Plus sheet's state machine, exercised as the pure fold the ViewModel uses. */
class PlusViewModelTest {

    private val monthly = PlusPlan(PLUS_BASE_PLAN_MONTHLY, "₹99.00", "P1M", "token-m")
    private val annual = PlusPlan(PLUS_BASE_PLAN_ANNUAL, "₹899.00", "P1Y", "token-a")
    private val allEnabled = MonetizationConfig(plusEnabled = true, adsEnabled = true, purchasesEnabled = true)

    @Test
    fun `defaults are off so no purchase UI appears before the backend says so`() {
        val ui = plusUi(EntitlementState.Unknown, MonetizationConfig(), listOf(monthly, annual), PurchasePhase.Idle)
        assertFalse(ui.plusEnabled)
        assertFalse(ui.purchasesEnabled)
        assertTrue(ui.plans.isEmpty())
        assertFalse(ui.isPlus)
    }

    @Test
    fun `free account shows its remaining allowance`() {
        val ui = plusUi(
            EntitlementState.Free(LiveSplitAllowance(used = 2, limit = 3, resetsAt = "2026-09-01T00:00:00Z")),
            allEnabled,
            listOf(monthly, annual),
            PurchasePhase.Idle,
        )
        assertEquals(1, ui.allowance!!.remaining)
        assertFalse(ui.isPlus)
    }

    @Test
    fun `plans keep Play's own localized prices and periods, in Play's order`() {
        val ui = plusUi(EntitlementState.Free(), allEnabled, listOf(monthly, annual), PurchasePhase.Idle)
        assertEquals(listOf("₹99.00", "₹899.00"), ui.plans.map { it.formattedPrice })
        assertEquals(listOf("P1M", "P1Y"), ui.plans.map { it.billingPeriod })
        // Nothing in the state marks a plan as chosen, and annual is not floated to the top.
        assertEquals(PLUS_BASE_PLAN_MONTHLY, ui.plans.first().basePlanId)
    }

    @Test
    fun `verified plus hides the allowance counter`() {
        val ui = plusUi(
            EntitlementState.Plus(PLUS_PRODUCT_ID, "2026-09-09T00:00:00Z", autoRenewing = true),
            allEnabled,
            listOf(monthly),
            PurchasePhase.Idle,
        )
        assertTrue(ui.isPlus)
        assertNull(ui.allowance)
        assertFalse(ui.cancelledButActive)
    }

    @Test
    fun `cancelled subscription keeps every benefit until it expires`() {
        val ui = plusUi(
            EntitlementState.Plus(PLUS_PRODUCT_ID, "2026-09-09T00:00:00Z", autoRenewing = false),
            allEnabled,
            emptyList(),
            PurchasePhase.Idle,
        )
        assertTrue(ui.isPlus)
        assertTrue(ui.cancelledButActive)
        assertEquals("2026-09-09T00:00:00Z", ui.expiresAt)
    }

    @Test
    fun `pending purchase is reported as pending, not as failure`() {
        val ui = plusUi(EntitlementState.Free(), allEnabled, listOf(monthly), PurchasePhase.Pending)
        assertTrue(ui.pending)
        assertFalse(ui.isPlus)
    }

    @Test
    fun `verification in flight busies the buttons but grants nothing`() {
        val ui = plusUi(EntitlementState.Free(), allEnabled, listOf(monthly), PurchasePhase.Verifying)
        assertTrue(ui.busy)
        assertFalse(ui.isPlus)
    }

    @Test
    fun `a declined or cancelled purchase leaves the account free`() {
        assertFalse(plusUi(EntitlementState.Free(), allEnabled, listOf(monthly), PurchasePhase.Failed).isPlus)
        assertFalse(plusUi(EntitlementState.Free(), allEnabled, listOf(monthly), PurchasePhase.Cancelled).isPlus)
    }

    @Test
    fun `purchases switched off hides plans even when Play has them`() {
        val ui = plusUi(
            EntitlementState.Free(),
            MonetizationConfig(plusEnabled = true, purchasesEnabled = false),
            listOf(monthly, annual),
            PurchasePhase.Idle,
        )
        assertTrue(ui.plans.isEmpty())
    }
}
