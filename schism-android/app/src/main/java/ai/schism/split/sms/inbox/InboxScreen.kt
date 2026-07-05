@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ai.schism.split.sms.inbox

import ai.schism.split.core.money.formatMinor
import ai.schism.split.core.ui.InitialAvatar
import ai.schism.split.core.ui.UiState
import ai.schism.split.sms.data.Transaction
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ai.schism.split.core.ui.ListSkeleton
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SMS_PERMISSIONS = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)

@Composable
fun InboxScreen(
    onSplit: (String) -> Unit,
    onScanItemized: () -> Unit,
    viewModel: InboxViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val permissionNeeded by viewModel.permissionNeeded.collectAsState()
    val scanningReceipt by viewModel.scanningReceipt.collectAsState()
    if (scanningReceipt) {
        ai.schism.split.sms.itemized.BillScanProgressDialog()
    }
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    fun hasSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    val launcher = rememberLauncherForActivityResult(RequestMultiplePermissions()) { grants ->
        val granted = grants[Manifest.permission.READ_SMS] == true
        viewModel.setPermissionGranted(granted)
        if (granted) viewModel.scan()
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val pickImage = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) viewModel.scanReceipt(uri)
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(viewModel) {
        viewModel.navigateItemized.collect { onScanItemized() }
    }

    LaunchedEffect(Unit) {
        val granted = hasSmsPermission()
        viewModel.setPermissionGranted(granted)
        if (granted) viewModel.scan()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Inbox") },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Scan receipt") },
                icon = { Icon(Icons.Filled.DocumentScanner, contentDescription = null) },
                onClick = { pickImage.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!permissionNeeded) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    InboxFilter.entries.forEach { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { viewModel.setFilter(f) },
                            label = { Text(f.label) },
                        )
                    }
                }
            }
            Box(Modifier.fillMaxSize()) {
                when {
                    permissionNeeded -> PermissionRequest(onAllow = { launcher.launch(SMS_PERMISSIONS) })
                    else -> when (val s = state) {
                        is UiState.Loading -> ListSkeleton()
                        is UiState.Empty -> EmptyInbox(filter)
                        is UiState.Error -> Centered { Text(s.message) }
                        is UiState.Data -> TransactionList(
                            transactions = s.value,
                            filter = filter,
                            onKeepPersonal = viewModel::keepPersonal,
                            onSplit = onSplit,
                            onRestore = viewModel::restoreToInbox,
                            onEdit = viewModel::edit,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionList(
    transactions: List<Transaction>,
    filter: InboxFilter,
    onKeepPersonal: (String) -> Unit,
    onSplit: (String) -> Unit,
    onRestore: (String) -> Unit,
    onEdit: (String, String, Long) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(transactions, key = { it.id }) { txn ->
            TransactionCard(txn, filter, onKeepPersonal, onSplit, onRestore, onEdit)
        }
    }
}

@Composable
private fun TransactionCard(
    txn: Transaction,
    filter: InboxFilter,
    onKeepPersonal: (String) -> Unit,
    onSplit: (String) -> Unit,
    onRestore: (String) -> Unit,
    onEdit: (String, String, Long) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                InitialAvatar(name = txn.merchant, key = txn.id, size = 48.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        txn.merchant,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        listOf(txn.bankName, formatDate(txn.timestamp))
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    formatMinor(txn.amountMinor, txn.currency),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = { editing = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit")
                }
            }
            when (filter) {
                InboxFilter.ToSplit -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { onKeepPersonal(txn.id) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Keep personal") }
                    Button(
                        onClick = { onSplit(txn.id) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Split to group") }
                }
                InboxFilter.Personal -> OutlinedButton(
                    onClick = { onRestore(txn.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Move to split") }
                InboxFilter.Added -> Text(
                    "Added to a group",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (editing) {
        EditTransactionDialog(
            txn = txn,
            onDismiss = { editing = false },
            onSave = { merchant, amountMinor ->
                onEdit(txn.id, merchant, amountMinor)
                editing = false
            },
        )
    }
}

@Composable
private fun EditTransactionDialog(
    txn: Transaction,
    onDismiss: () -> Unit,
    onSave: (String, Long) -> Unit,
) {
    var merchant by remember { mutableStateOf(txn.merchant) }
    var amount by remember { mutableStateOf(String.format("%.2f", txn.amountMinor / 100.0)) }
    val amountMinor = amount.trim().toDoubleOrNull()?.let { (it * 100).toLong() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("Merchant / title") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (merchant.isNotBlank() && amountMinor != null) onSave(merchant, amountMinor) },
                enabled = merchant.isNotBlank() && amountMinor != null && amountMinor > 0,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PermissionRequest(onAllow: () -> Unit) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconBubble()
            Text("Read bank SMS", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Schism reads transaction texts on your device to suggest expenses. " +
                    "Your messages are parsed locally and never leave your phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onAllow) { Text("Allow SMS access") }
            Text(
                "If the prompt doesn't appear, open Settings → Permissions → SMS and turn it on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(onClick = { context.openAppSettings() }) { Text("Open app settings") }
        }
    }
}

/** Deep-link to this app's system settings page so SMS permission can be granted manually. */
private fun android.content.Context.openAppSettings() {
    val intent = android.content.Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.fromParts("package", packageName, null),
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}

@Composable
private fun EmptyInbox(filter: InboxFilter) {
    val (title, body) = when (filter) {
        InboxFilter.ToSplit -> "Inbox zero" to
            "New bank transactions show up here to keep personal or split with a group."
        InboxFilter.Personal -> "Nothing personal yet" to
            "Transactions you keep personal will collect here."
        InboxFilter.Added -> "Nothing added yet" to
            "Transactions you split into a group will appear here."
    }
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconBubble()
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun IconBubble() {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(88.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Inbox,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

private val DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy")

private fun formatDate(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().format(DATE_FORMAT)
