package ai.schism.split.plus

import ai.schism.split.core.billing.EntitlementRepository
import ai.schism.split.core.billing.EntitlementState
import ai.schism.split.core.billing.LiveSplitAllowance
import ai.schism.split.core.billing.MonetizationConfig
import ai.schism.split.core.billing.PlusBilling
import ai.schism.split.core.billing.PlusPlan
import ai.schism.split.core.billing.PurchasePhase
import android.app.Activity
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything the Plus sheet and the Settings rows need, and nothing from a Google SDK. */
data class PlusUi(
    val plusEnabled: Boolean = false,
    val purchasesEnabled: Boolean = false,
    val isPlus: Boolean = false,
    val autoRenewing: Boolean = false,
    val expiresAt: String = "",
    val allowance: LiveSplitAllowance? = null,
    val plans: List<PlusPlan> = emptyList(),
    val phase: PurchasePhase = PurchasePhase.Idle,
) {
    val busy: Boolean get() = phase == PurchasePhase.Purchasing || phase == PurchasePhase.Verifying
    /** Play took the purchase but it hasn't cleared yet — say so instead of pretending it failed. */
    val pending: Boolean get() = phase == PurchasePhase.Pending
    /** Cancelled subscription still inside its paid period: keep every benefit until it expires. */
    val cancelledButActive: Boolean get() = isPlus && !autoRenewing
}

/**
 * Folds the four monetization inputs into what the UI shows. Pure, so every awkward combination
 * (unknown entitlement, cancelled-but-active, pending purchase, switches off) is directly testable.
 */
fun plusUi(
    entitlement: EntitlementState,
    config: MonetizationConfig,
    plans: List<PlusPlan>,
    phase: PurchasePhase,
): PlusUi = PlusUi(
    plusEnabled = config.plusEnabled,
    purchasesEnabled = config.purchasesEnabled,
    isPlus = entitlement is EntitlementState.Plus,
    autoRenewing = (entitlement as? EntitlementState.Plus)?.autoRenewing ?: false,
    expiresAt = (entitlement as? EntitlementState.Plus)?.expiresAt.orEmpty(),
    allowance = (entitlement as? EntitlementState.Free)?.allowance,
    // Never advertise a plan the backend hasn't switched purchases on for.
    plans = if (config.purchasesEnabled) plans else emptyList(),
    phase = phase,
)

@HiltViewModel
class PlusViewModel @Inject constructor(
    private val billing: PlusBilling,
    private val entitlements: EntitlementRepository,
) : ViewModel() {

    val state: StateFlow<PlusUi> = combine(
        entitlements.state,
        entitlements.config,
        billing.plans,
        billing.phase,
    ) { entitlement, config, plans, phase -> plusUi(entitlement, config, plans, phase) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlusUi())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            entitlements.refresh()
            billing.refresh()
        }
    }

    fun purchase(activity: Activity, plan: PlusPlan) {
        viewModelScope.launch { billing.purchase(activity, plan) }
    }

    fun restore() {
        viewModelScope.launch { billing.restore() }
    }

    fun manageSubscriptionIntent(): Intent = billing.manageSubscriptionIntent()
}
