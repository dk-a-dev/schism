package ai.schism.split.sms.itemized

import ai.schism.split.sms.receipt.ReceiptScanner
import ai.schism.split.sms.receipt.parseReceipt
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Shared "scan a bill" entry point so the OCR → itemised-AI-split flow is reachable from anywhere,
 * not just the Inbox. Picks an image, reads it on-device, and hands the parsed receipt to the
 * itemised split screen via [PendingReceipt].
 */
@HiltViewModel
class BillScanViewModel @Inject constructor(
    private val receiptScanner: ReceiptScanner,
    private val pending: PendingReceipt,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {
    fun scan(uri: Uri, onItemized: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                parseReceipt(receiptScanner.recognizeLines(appContext, uri))
            }.onSuccess { draft ->
                if (draft == null || draft.lineItems.isEmpty()) {
                    Toast.makeText(appContext, "Couldn't read items off that bill", Toast.LENGTH_SHORT).show()
                } else {
                    pending.draft = draft
                    onItemized()
                }
            }.onFailure {
                Toast.makeText(appContext, "Couldn't scan that image", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/**
 * Remembers a "scan a bill" trigger: invoking the returned lambda opens the photo picker; once an
 * image is chosen it is OCR'd and, if line items are found, [onItemized] is called to navigate to
 * the itemised split screen.
 */
@Composable
fun rememberBillScan(onItemized: () -> Unit): () -> Unit {
    val viewModel: BillScanViewModel = hiltViewModel()
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) viewModel.scan(uri, onItemized)
    }
    return { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }
}
