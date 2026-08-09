package ai.schism.split.finance

import ai.schism.split.core.billing.EntitlementRepository
import ai.schism.split.core.billing.isPlus
import ai.schism.split.core.ui.UiState
import ai.schism.split.sms.data.TransactionDao
import ai.schism.split.sms.data.TransactionEntity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Spending insights computed entirely on-device from the local transaction ledger. Read-only: it
 * observes every stored transaction, maps it to the pure [SpendTxn] domain, and hands it to
 * [summarize]. No network. Empty until the ledger has at least one transaction.
 */
@HiltViewModel
class SpendingViewModel @Inject constructor(
    private val dao: TransactionDao,
    entitlements: EntitlementRepository,
) : ViewModel() {

    val state: StateFlow<UiState<SpendingSummary>> =
        dao.observeAll()
            .map { entities ->
                if (entities.isEmpty()) {
                    UiState.Empty
                } else {
                    UiState.Data(summarize(entities.map { it.toSpendTxn() }, System.currentTimeMillis()))
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    /** Backend-verified Plus. Gates only the *extra* insight views — the free summary above stays. */
    val plus: StateFlow<Boolean> = entitlements.state
        .map { it.isPlus }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Computed for everyone (it's the device's own data either way) but only rendered for Plus;
     * losing Plus therefore hides a *view*, never a record.
     */
    val insights: StateFlow<PlusInsightsData?> = dao.observeAll()
        .map { entities -> plusInsights(entities.map { it.toSpendTxn() }, System.currentTimeMillis()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

private fun TransactionEntity.toSpendTxn(): SpendTxn = SpendTxn(
    amountMinor = amountMinor,
    currency = currency,
    merchant = merchant,
    timestamp = timestamp,
)
