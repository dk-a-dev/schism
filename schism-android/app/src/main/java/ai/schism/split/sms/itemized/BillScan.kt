package ai.schism.split.sms.itemized

import ai.schism.split.core.ui.WavyProgress
import ai.schism.split.sms.receipt.ReceiptScanner
import ai.schism.split.sms.receipt.engine.parseBill
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Shared "scan a bill" entry point so the OCR → itemised-AI-split flow is reachable from anywhere,
 * not just the Inbox. Picks an image, reads it on-device (with a visible progress dialog — LLM
 * parsing can take a while), and hands the parsed receipt to the itemised split screen.
 */
@HiltViewModel
class BillScanViewModel @Inject constructor(
    private val receiptScanner: ReceiptScanner,
    private val pending: PendingReceipt,
    private val llmParser: ai.schism.split.core.ai.LlmExpenseParser,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    fun scan(uri: Uri, onItemized: () -> Unit) {
        viewModelScope.launch {
            _scanning.value = true
            runCatching {
                val rows = receiptScanner.recognizeCells(appContext, uri)
                // Deterministic engine is primary (fast, no model). Only when it can't produce a
                // draft, or produced one it couldn't verify, try the on-device LLM as a fallback —
                // it already no-ops (returns null) when the AI toggle is off or the model's absent.
                var draft = parseBill(rows)
                if (draft == null || draft.verified == false) {
                    val ai = runCatching { llmParser.parseReceipt(rows.map { it.text }) }
                        .getOrNull()
                        ?.takeIf { it.lineItems.isNotEmpty() }
                    if (ai != null) draft = ai
                }
                draft
            }.onSuccess { draft ->
                _scanning.value = false
                if (draft == null) {
                    Toast.makeText(appContext, "Couldn't read that bill", Toast.LENGTH_SHORT).show()
                } else {
                    // Even an item-less draft opens the screen: the user can add dishes by hand.
                    pending.draft = draft
                    onItemized()
                }
            }.onFailure {
                _scanning.value = false
                Toast.makeText(appContext, "Couldn't scan that image", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/**
 * Remembers a "scan a bill" trigger: invoking the returned lambda opens the photo picker; once an
 * image is chosen it is OCR'd + parsed (progress dialog shown) and [onItemized] navigates to the
 * itemised split screen.
 */
@Composable
fun rememberBillScan(onItemized: () -> Unit): () -> Unit {
    val viewModel: BillScanViewModel = hiltViewModel()
    val scanning by viewModel.scanning.collectAsState()
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) viewModel.scan(uri, onItemized)
    }
    if (scanning) {
        BillScanProgressDialog()
    }
    return { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }
}

/** Blocking progress while a bill is OCR'd and parsed (the on-device LLM can take a little while). */
@Composable
fun BillScanProgressDialog() {
    AlertDialog(
        onDismissRequest = { },
        confirmButton = { },
        title = { Text("Reading your bill…") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier,
            ) {
                WavyProgress()
                Text(
                    "Extracting dishes, quantities and tax on your device.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}
