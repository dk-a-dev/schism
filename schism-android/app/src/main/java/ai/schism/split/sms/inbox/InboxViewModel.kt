package ai.schism.split.sms.inbox

import ai.schism.split.core.ui.UiState
import ai.schism.split.sms.data.SmsRepository
import ai.schism.split.sms.data.Transaction
import ai.schism.split.sms.ingest.SmsScanWorker
import ai.schism.split.sms.itemized.PendingReceipt
import ai.schism.split.sms.receipt.ReceiptScanner
import ai.schism.split.sms.receipt.parseReceipt
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val repo: SmsRepository,
    private val receiptScanner: ReceiptScanner,
    private val pendingReceipt: PendingReceipt,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    /** One-shot messages for a snackbar (receipt scan result / error). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** One-shot signal to navigate to the itemised split screen after a multi-item receipt scan. */
    private val _navigateItemized = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateItemized: SharedFlow<Unit> = _navigateItemized.asSharedFlow()

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

    /** Scan a receipt image on-device (ML Kit OCR → parse) and add it to the inbox to split/keep. */
    fun scanReceipt(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val lines = receiptScanner.recognizeLines(appContext, uri)
                parseReceipt(lines)
            }.onSuccess { draft ->
                if (draft == null) {
                    _messages.tryEmit("Couldn't read a total from that receipt")
                } else if (draft.lineItems.size >= 2) {
                    // Multi-item receipt: hand it off to the itemised split flow to assign per person.
                    pendingReceipt.draft = draft
                    _navigateItemized.tryEmit(Unit)
                } else {
                    repo.addReceipt(draft)
                    _messages.tryEmit("Added ${draft.merchant} — split it below")
                }
            }.onFailure {
                _messages.tryEmit("Couldn't scan that image")
            }
        }
    }
}
