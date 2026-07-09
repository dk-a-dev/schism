package ai.schism.split.sms.inbox

import ai.schism.split.core.ui.UiState
import ai.schism.split.sms.data.SmsRepository
import ai.schism.split.sms.data.Transaction
import ai.schism.split.sms.data.TransactionStatus
import ai.schism.split.sms.ingest.SmsScanWorker
import ai.schism.split.sms.itemized.PendingReceipt
import ai.schism.split.sms.receipt.ReceiptScanner
import ai.schism.split.sms.receipt.engine.buildLlmHandoff
import ai.schism.split.sms.receipt.engine.parseBill
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which slice of the inbox is shown. */
enum class InboxFilter(val label: String, val status: String) {
    ToSplit("To split", TransactionStatus.UNASSIGNED),
    Personal("Personal", TransactionStatus.PERSONAL),
    Added("Added", TransactionStatus.PUSHED),
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val repo: SmsRepository,
    private val receiptScanner: ReceiptScanner,
    private val pendingReceipt: PendingReceipt,
    private val llmParser: ai.schism.split.core.ai.LlmExpenseParser,
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

    /** True while a receipt image is being OCR'd + parsed (drives a progress dialog). */
    private val _scanningReceipt = MutableStateFlow(false)
    val scanningReceipt: StateFlow<Boolean> = _scanningReceipt.asStateFlow()

    private val _filter = MutableStateFlow(InboxFilter.ToSplit)
    val filter: StateFlow<InboxFilter> = _filter.asStateFlow()

    val state: StateFlow<UiState<List<Transaction>>> =
        _filter
            .flatMapLatest { f -> repo.observeByStatus(f.status) }
            .map { txns -> if (txns.isEmpty()) UiState.Empty else UiState.Data(txns) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    fun setFilter(f: InboxFilter) {
        _filter.value = f
    }

    /** Inline-edit a transaction's merchant/title and amount. */
    fun edit(id: String, merchant: String, amountMinor: Long) {
        viewModelScope.launch { repo.edit(id, merchant, amountMinor) }
    }

    /** Move a kept-personal transaction back to "To split". */
    fun restoreToInbox(id: String) {
        viewModelScope.launch { repo.restoreToInbox(id) }
    }

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
            _scanningReceipt.value = true
            runCatching {
                val rows = receiptScanner.recognizeCells(appContext, uri)
                // Deterministic engine is primary (fast, no model). Only when it can't produce a
                // draft, or produced one it couldn't verify, try the on-device LLM as a fallback —
                // it already no-ops (returns null) when the AI toggle is off or the model's absent.
                var draft = parseBill(rows)
                if (draft == null || draft.verified == false) {
                    // Hand the model column-structured OCR (and what the engine already read, if
                    // anything) instead of flattened lines, so it repairs against structure.
                    val handoff = buildLlmHandoff(rows, draft)
                    val aiDraft = runCatching { llmParser.parseReceipt(rows.map { it.text }, handoff) }
                        .getOrNull()
                        ?.takeIf { it.lineItems.isNotEmpty() }
                    if (aiDraft != null) draft = aiDraft
                }
                draft
            }.onSuccess { draft ->
                _scanningReceipt.value = false
                if (draft == null) {
                    _messages.tryEmit("Couldn't read a total from that receipt")
                } else if (draft.lineItems.isNotEmpty()) {
                    // Itemised receipt: hand it off to the split flow to assign per person.
                    pendingReceipt.draft = draft
                    _navigateItemized.tryEmit(Unit)
                } else {
                    repo.addReceipt(draft)
                    _messages.tryEmit("Added ${draft.merchant} — split it below")
                }
            }.onFailure {
                _scanningReceipt.value = false
                _messages.tryEmit("Couldn't scan that image")
            }
        }
    }
}
