package ai.schism.split.core.billing

import android.app.Activity
import android.content.Intent
import kotlinx.coroutines.flow.StateFlow

/** Product and base plans sold on Play. Mirrors the backend's accepted product id. */
const val PLUS_PRODUCT_ID = "schism_plus"
const val PLUS_BASE_PLAN_MONTHLY = "monthly"
const val PLUS_BASE_PLAN_ANNUAL = "annual"

/**
 * One purchasable Schism Plus base plan, already reduced to what a screen needs. [formattedPrice]
 * and [billingPeriod] come straight from Play so the user always sees Play's own localized price and
 * renewal terms — never a hardcoded one.
 */
data class PlusPlan(
    val basePlanId: String,
    val formattedPrice: String,
    /** ISO-8601 billing period as Play reports it, e.g. `P1M` / `P1Y`. */
    val billingPeriod: String,
    val offerToken: String,
) {
    val isAnnual: Boolean get() = basePlanId == PLUS_BASE_PLAN_ANNUAL
}

/** Where a purchase attempt currently stands. Drives the sheet's button state, nothing else. */
enum class PurchasePhase {
    Idle,

    /** Play's sheet is up, or we're waiting on its callback. */
    Purchasing,

    /** Play accepted the purchase but it isn't complete yet (e.g. pending payment method). */
    Pending,

    /** Play gave us a token; the backend is verifying it. Entitlement follows only if it passes. */
    Verifying,

    /** The user backed out. Not an error. */
    Cancelled,
    Failed,
}

/**
 * The app's whole view of Play Billing. Screens depend on this — never on Billing Library types —
 * so purchase UI stays testable and the SDK stays behind one file.
 */
interface PlusBilling {
    /** Empty when billing is unavailable, disabled by the backend, or Play has no products for us. */
    val plans: StateFlow<List<PlusPlan>>
    val phase: StateFlow<PurchasePhase>

    /** Connects to Play (if needed) and loads base plans. No-op when purchases are switched off. */
    suspend fun refresh()

    suspend fun purchase(activity: Activity, plan: PlusPlan)

    /** Re-sends purchases Play still knows about for backend verification (reinstall / restore tap). */
    suspend fun restore()

    /** Deep link to Play's subscription management for this product. */
    fun manageSubscriptionIntent(): Intent
}
