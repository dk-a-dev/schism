package ai.schism.split.sms.inbox

import ai.schism.split.core.ui.UiState
import ai.schism.split.sms.data.SmsRepository
import ai.schism.split.sms.data.Transaction
import ai.schism.split.sms.ingest.SmsScanWorker
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val repo: SmsRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    /** True until the screen reports that SMS permission has been granted. Drives the empty state. */
    private val _permissionNeeded = MutableStateFlow(true)
    val permissionNeeded: StateFlow<Boolean> = _permissionNeeded.asStateFlow()

    val state: StateFlow<UiState<List<Transaction>>> =
        repo.observeInbox()
            .map { txns -> if (txns.isEmpty()) UiState.Empty else UiState.Data(txns) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    /** Called by the screen once it knows whether SMS permission is held. */
    fun setPermissionGranted(granted: Boolean) {
        _permissionNeeded.value = !granted
    }

    /** Backfill the inbox from the device SMS store (requires READ_SMS already granted). */
    fun scan() {
        SmsScanWorker.enqueue(appContext)
    }

    /** Keep a transaction as a personal expense; it leaves the inbox stream. */
    fun keepPersonal(id: String) {
        viewModelScope.launch { repo.keepPersonal(id) }
    }
}
