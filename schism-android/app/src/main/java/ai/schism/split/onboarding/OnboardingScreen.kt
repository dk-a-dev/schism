@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ai.schism.split.onboarding

import ai.schism.split.core.ui.SchismLogo
import ai.schism.split.core.ui.SchismPrimaryButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * First-run experience: a swipeable walkthrough of what Schism does, then account auth (create
 * account or log in with email + password). Session token is stored on success and the app's root
 * gate switches to the main app.
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    var showAuth by rememberSaveable { mutableStateOf(false) }
    if (!showAuth) {
        Walkthrough(onFinish = { showAuth = true })
    } else {
        AuthForm(onDone = onDone, viewModel = viewModel)
    }
}

@Composable
private fun AuthForm(
    onDone: () -> Unit,
    viewModel: OnboardingViewModel,
) {
    val state by viewModel.state.collectAsState()
    var register by rememberSaveable { mutableStateOf(true) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    val emailValid = email.contains("@")
    val passValid = password.length >= 6
    val nameValid = !register || name.trim().length >= 2
    val canSubmit = emailValid && passValid && nameValid && !state.submitting

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
            Text(
                if (register) "Create your account" else "Welcome back",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (register) {
                    "Split expenses with friends and keep everyone square — your account syncs your groups across devices."
                } else {
                    "Log in to pick up your groups where you left off."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (register) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Your name") },
                    singleLine = true,
                    isError = name.isNotEmpty() && !nameValid,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                isError = email.isNotEmpty() && !emailValid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            if (register) {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone (optional)") },
                    singleLine = true,
                    supportingText = { Text("Friends who added you by number get linked automatically") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = password.isNotEmpty() && !passValid,
                supportingText = if (register) { { Text("At least 6 characters") } } else null,
                modifier = Modifier.fillMaxWidth(),
            )

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            SchismPrimaryButton(
                onClick = {
                    if (register) viewModel.register(name, email, password, phone, onDone)
                    else viewModel.login(email, password, onDone)
                },
                enabled = canSubmit,
                shape = RoundedCornerShape(100),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.submitting) {
                    LoadingIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(if (register) "Create account" else "Log in")
                }
            }
            TextButton(onClick = { register = !register }, modifier = Modifier.fillMaxWidth()) {
                Text(if (register) "Already have an account? Log in" else "New here? Create an account")
            }
        }
    }
}
