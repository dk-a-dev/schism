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
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import ai.schism.split.core.ui.WavyProgress
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
    val permissionNeeded by viewModel.permissionNeeded.collectAsState()
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
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                permissionNeeded -> PermissionRequest(onAllow = { launcher.launch(SMS_PERMISSIONS) })
                else -> when (val s = state) {
                    is UiState.Loading -> Centered { WavyProgress() }
                    is UiState.Empty -> EmptyInbox()
                    is UiState.Error -> Centered { Text(s.message) }
                    is UiState.Data -> TransactionList(
                        transactions = s.value,
                        onKeepPersonal = viewModel::keepPersonal,
                        onSplit = onSplit,
                    )
                }
            }
        }
    }
}

@Composable
private fun TransactionList(
    transactions: List<Transaction>,
    onKeepPersonal: (String) -> Unit,
    onSplit: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(transactions, key = { it.id }) { txn ->
            TransactionCard(txn, onKeepPersonal, onSplit)
        }
    }
}

@Composable
private fun TransactionCard(
    txn: Transaction,
    onKeepPersonal: (String) -> Unit,
    onSplit: (String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
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
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { onKeepPersonal(txn.id) },
                    modifier = Modifier.weight(1f),
                ) { Text("Keep personal") }
                Button(
                    onClick = { onSplit(txn.id) },
                    modifier = Modifier.weight(1f),
                ) { Text("Split to group") }
            }
        }
    }
}

@Composable
private fun PermissionRequest(onAllow: () -> Unit) {
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
        }
    }
}

@Composable
private fun EmptyInbox() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconBubble()
            Text("Inbox zero", style = MaterialTheme.typography.headlineSmall)
            Text(
                "New bank transactions will show up here for you to keep personal or split with a group.",
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
