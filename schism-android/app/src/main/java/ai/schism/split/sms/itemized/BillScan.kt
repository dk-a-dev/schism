package ai.schism.split.sms.itemized

import ai.schism.split.core.ui.SplitLoader
import ai.schism.split.sms.receipt.ReceiptScanner
import ai.schism.split.sms.receipt.engine.parseBill
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
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

/** Walks up [ContextWrapper]s (as Compose's [LocalContext] is often one) to find the host Activity. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Remembers a "add a bill" trigger: invoking the returned lambda offers a choice between the ML Kit
 * Document Scanner (auto crop/deskew/denoise — a much cleaner OCR read) and the plain gallery/Photo
 * Picker. Either way, once an image is chosen it is OCR'd + parsed (progress dialog shown) and
 * [onItemized] navigates to the itemised split screen.
 *
 * The document scanner needs Google Play services and the host Activity; if either is unavailable
 * (no Activity in [LocalContext], or the scanner fails to launch) the flow degrades gracefully to the
 * plain picker instead of dead-ending.
 */
@Composable
fun rememberBillScan(onItemized: () -> Unit): () -> Unit {
    val viewModel: BillScanViewModel = hiltViewModel()
    val scanning by viewModel.scanning.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var showChooser by remember { mutableStateOf(false) }

    val galleryPicker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) viewModel.scan(uri, onItemized)
    }
    fun launchGallery() = galleryPicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))

    val docScanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val uri = if (result.resultCode == Activity.RESULT_OK) {
            GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages?.firstOrNull()?.imageUri
        } else {
            null
        }
        if (uri != null) viewModel.scan(uri, onItemized)
    }

    fun launchDocumentScanner() {
        val host = activity
        if (host == null) {
            launchGallery()
            return
        }
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        runCatching {
            GmsDocumentScanning.getClient(options).getStartScanIntent(host)
                .addOnSuccessListener { sender ->
                    runCatching {
                        docScanLauncher.launch(IntentSenderRequest.Builder(sender).build())
                    }.onFailure { launchGallery() }
                }
                .addOnFailureListener { launchGallery() }
        }.onFailure { launchGallery() }
    }

    if (showChooser) {
        BillEntryChooserDialog(
            onDismiss = { showChooser = false },
            onScan = { showChooser = false; launchDocumentScanner() },
            onUpload = { showChooser = false; launchGallery() },
        )
    }
    if (scanning) {
        BillScanProgressDialog()
    }
    return { showChooser = true }
}

/** Lets the user pick how to bring in a bill: camera scan (cleaner OCR) or an existing gallery photo. */
@Composable
private fun BillEntryChooserDialog(
    onDismiss: () -> Unit,
    onScan: () -> Unit,
    onUpload: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a bill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("Scan with camera") }
                TextButton(onClick = onUpload, modifier = Modifier.fillMaxWidth()) { Text("Upload from gallery") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
                SplitLoader()
                Text(
                    "Extracting dishes, quantities and tax on your device.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}
