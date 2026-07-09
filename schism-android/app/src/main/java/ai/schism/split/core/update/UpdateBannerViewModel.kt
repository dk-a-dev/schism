package ai.schism.split.core.update

import ai.schism.split.BuildConfig
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Checks GitHub Releases once on launch and, if a newer build exists, exposes it for a dismissible
 * app-wide "Update available" banner. Silent on failure (no network / offline) — it just stays null.
 */
@HiltViewModel
class UpdateBannerViewModel @Inject constructor(
    private val updateChecker: UpdateChecker,
) : ViewModel() {

    private val _available = MutableStateFlow<ReleaseInfo?>(null)
    val available: StateFlow<ReleaseInfo?> = _available.asStateFlow()

    private var dismissed = false

    init {
        viewModelScope.launch {
            val latest = updateChecker.latestRelease() ?: return@launch
            if (!dismissed && isNewer(latest.versionName, BuildConfig.VERSION_NAME)) {
                _available.value = latest
            }
        }
    }

    /** Hide the banner for this session (until the app is relaunched). */
    fun dismiss() {
        dismissed = true
        _available.value = null
    }
}
