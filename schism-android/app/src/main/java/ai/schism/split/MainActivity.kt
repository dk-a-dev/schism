package ai.schism.split

import ai.schism.split.core.nav.AppNav
import ai.schism.split.core.settings.SettingsRepository
import ai.schism.split.core.theme.SchismTheme
import ai.schism.split.core.theme.ThemeMode
import ai.schism.split.onboarding.OnboardingScreen
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var settings: SettingsRepository

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
                when (onboarded) {
                    null -> Unit
                    false -> OnboardingScreen(onDone = {})
                    true -> AppNav()
                }
            }
        }
    }
}
