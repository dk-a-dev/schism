package ai.schism.split.export

import ai.schism.split.R
import ai.schism.split.core.billing.EntitlementRepository
import ai.schism.split.core.billing.isPlus
import ai.schism.split.sms.data.TransactionDao
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * User-initiated CSV/PDF export of the on-device ledger. A Plus benefit, but the *data* is never
 * gated: the records stay visible in the app whatever the entitlement says — only this convenience
 * is Plus-only. Files are written to the app cache and handed to a chooser; nothing is uploaded.
 */
@HiltViewModel
class ExportViewModel @Inject constructor(
    private val dao: TransactionDao,
    entitlements: EntitlementRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val plus: StateFlow<Boolean> = entitlements.state
        .map { it.isPlus }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _share = MutableStateFlow<Intent?>(null)

    /** Set when an export is ready; the screen launches it and then calls [consumeShare]. */
    val share: StateFlow<Intent?> = _share.asStateFlow()

    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    fun exportCsv() = export("text/csv") { rows -> writeCsvExport(context, rows) }

    fun exportPdf() = export("application/pdf") { rows -> writePdfExport(context, rows) }

    fun consumeShare() {
        _share.value = null
        _failed.value = false
    }

    private fun export(mimeType: String, write: (List<ExportRow>) -> android.net.Uri) {
        viewModelScope.launch {
            val rows = dao.observeAll().first().map {
                ExportRow(it.timestamp, it.merchant, it.amountMinor, it.currency)
            }
            val result = withContext(Dispatchers.IO) {
                // Previous exports are cleared first, so a shared temp file never outlives its share.
                clearExports(context)
                runCatching { write(rows) }
            }
            result.fold(
                onSuccess = {
                    _share.value = shareExportIntent(it, mimeType, context.getString(R.string.plus_export_share))
                },
                onFailure = { _failed.value = true },
            )
        }
    }
}
