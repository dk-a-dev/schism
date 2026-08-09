@file:OptIn(ExperimentalMaterial3Api::class)

package ai.schism.split.groups.invite

import ai.schism.split.R
import ai.schism.split.core.ui.SchismPrimaryButton
import ai.schism.split.core.ui.SchismSecondaryButton
import ai.schism.split.groups.qr.scanQrCode
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Accepts an invite the user pastes or scans and hands the token to the redeem screen. Replaces the
 * old "join by group link or ID" form: a group id is not a capability, so a legacy link only ever
 * produces an explanation — never a group fetch or a cached group.
 *
 * [legacy] pre-seeds that explanation, which is how the retired `schism://group/<id>` deep link lands
 * here.
 */
@Composable
fun EnterInviteScreen(
    onBack: () -> Unit,
    onToken: (String) -> Unit,
    onGroupToken: (String) -> Unit,
    legacy: Boolean = false,
) {
    val context = LocalContext.current
    val legacyMessage = stringResource(R.string.enter_invite_legacy)
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(if (legacy) legacyMessage else null) }

    fun accept(value: String?) {
        val raw = value.orEmpty()
        // Group links are checked first: `/i/g/<token>` also contains "/g/", which is how a v1.2
        // group id used to look.
        val groupToken = parseGroupInviteToken(raw)
        if (groupToken.isNotBlank()) {
            error = null
            onGroupToken(groupToken)
            return
        }
        error = when {
            raw.isBlank() -> context.getString(R.string.enter_invite_empty)
            isLegacyGroupLink(raw) -> legacyMessage
            else -> {
                val token = parseInviteToken(raw)
                if (token.isBlank()) context.getString(R.string.invite_error_not_found) else null
            }
        }
        if (error == null) onToken(parseInviteToken(raw))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.enter_invite_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.enter_invite_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; error = null },
                label = { Text(stringResource(R.string.enter_invite_label)) },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
            SchismPrimaryButton(
                onClick = { accept(input) },
                enabled = input.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.enter_invite_continue))
            }
            SchismSecondaryButton(
                onClick = {
                    scanQrCode(context) { scanned ->
                        if (scanned == null) {
                            error = context.getString(R.string.enter_invite_scan_failed)
                        } else {
                            accept(scanned)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                Text("  " + stringResource(R.string.enter_invite_scan))
            }
        }
    }
}

/** Share the group's link through the system share sheet — no participant name, anyone can use it. */
fun shareGroupInviteLink(context: Context, link: String, groupName: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.invite_group_share_subject, groupName))
        putExtra(Intent.EXTRA_TEXT, context.getString(R.string.invite_group_share_body, groupName, link))
    }
    context.startActivity(
        Intent.createChooser(send, context.getString(R.string.invite_group_share)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
}

/** Share one participant's invite link through the system share sheet. */
fun shareInviteLink(context: Context, link: String, groupName: String, participantName: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.invite_share_subject, groupName))
        putExtra(
            Intent.EXTRA_TEXT,
            context.getString(R.string.invite_share_body, groupName, participantName, link),
        )
    }
    context.startActivity(
        Intent.createChooser(send, context.getString(R.string.invite_share)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
}
