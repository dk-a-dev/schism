package ai.schism.split.finance

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
}

private fun TransactionEntity.toSpendTxn(): SpendTxn = SpendTxn(
    amountMinor = amountMinor,
    currency = currency,
    merchant = merchant,
    timestamp = timestamp,
)
