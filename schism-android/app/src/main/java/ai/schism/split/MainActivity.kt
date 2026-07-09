package ai.schism.split

import ai.schism.split.core.nav.AppNav
import ai.schism.split.core.net.AuthEvents
import ai.schism.split.core.settings.SettingsRepository
import ai.schism.split.core.theme.SchismTheme
import ai.schism.split.core.theme.ThemeMode
import ai.schism.split.onboarding.OnboardingScreen
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var authEvents: AuthEvents

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val mode by settings.themeMode.collectAsState(initial = SettingsRepository.DEFAULT_THEME_MODE)
            val dark = when (ThemeMode.from(mode)) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            SchismTheme(darkTheme = dark) {
                // Gate the app on first-run onboarding; null = not yet loaded (avoid flashing either UI).
                val onboarded by settings.onboarded.collectAsState(initial = null)

                // Set (not toggled off automatically) when the backend reports our session ended. While
                // non-null, it takes over the root gate — replacing AppNav (and its whole back stack)
                // with the sign-in form — regardless of the (still-true) onboarded flag. Cleared only
                // when the user successfully signs back in, so this can't loop.
                var sessionExpiredMessage by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    authEvents.sessionExpired.collect {
                        sessionExpiredMessage = "Your session ended — please sign in again."
                    }
                }

                when {
                    onboarded == null -> Unit
                    sessionExpiredMessage != null -> OnboardingScreen(
                        onDone = { sessionExpiredMessage = null },
                        startAtLogin = true,
                        message = sessionExpiredMessage,
                    )
                    onboarded == false -> OnboardingScreen(onDone = {})
                    else -> AppNav()
                }
            }
        }
    }
}
