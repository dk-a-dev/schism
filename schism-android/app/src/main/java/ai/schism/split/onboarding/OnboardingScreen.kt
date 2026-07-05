package ai.schism.split.onboarding

import ai.schism.split.core.ui.SchismLogo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * First-run experience: a swipeable walkthrough of what Schism does, then a short identity form so
 * groups can recognise the user and send invites.
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    var showForm by rememberSaveable { mutableStateOf(false) }
    if (!showForm) {
        Walkthrough(onFinish = { showForm = true })
    } else {
        IdentityForm(onDone = onDone, viewModel = viewModel)
    }
}

@Composable
private fun IdentityForm(
    onDone: () -> Unit,
    viewModel: OnboardingViewModel,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }

    val nameValid = name.trim().length >= 2
    val emailValid = email.isBlank() || email.contains("@")

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SchismLogo(size = 72.dp)
            Text("Welcome to Schism", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Tell us who you are — this is how groups recognize you and send invites.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Your name") },
                singleLine = true,
                isError = name.isNotEmpty() && !nameValid,
                supportingText = if (name.isNotEmpty() && !nameValid) {
                    { Text("At least 2 characters") }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                isError = !emailValid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Phone") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    viewModel.complete(name, email, phone)
                    onDone()
                },
                enabled = nameValid && emailValid,
                shape = RoundedCornerShape(100),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text("Get started", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
