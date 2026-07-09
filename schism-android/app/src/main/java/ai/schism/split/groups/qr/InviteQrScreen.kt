@file:OptIn(ExperimentalMaterial3Api::class)

package ai.schism.split.groups.qr

import ai.schism.split.core.ui.SchismPrimaryButton
import ai.schism.split.groups.join.JoinGroupViewModel
import ai.schism.split.groups.join.shareGroupInvite
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun InviteQrScreen(
    onBack: () -> Unit,
    viewModel: InviteQrViewModel = hiltViewModel(),
) {
    val group by viewModel.group.collectAsState()
    val context = LocalContext.current
    val groupId = viewModel.groupId
    val groupName = group?.name ?: "group"
    val link = remember(groupId) { JoinGroupViewModel.shareLink(groupId) }
    val qr = remember(link) { qrBitmap(link).asImageBitmap() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Invite to group") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                group?.name ?: "Group",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                "Have others scan this code to join.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                Image(
                    bitmap = qr,
                    contentDescription = "Group invite QR code",
                    modifier = Modifier
                        .padding(20.dp)
                        .size(240.dp),
                )
            }
            Text(
                link,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            SchismPrimaryButton(
                onClick = { shareGroupInvite(context, groupId, groupName) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Text("  Share link")
            }
        }
    }
}
