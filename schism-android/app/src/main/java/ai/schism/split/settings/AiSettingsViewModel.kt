package ai.schism.split.settings

import ai.schism.split.core.ai.ModelManager
import ai.schism.split.core.settings.SettingsRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs the "On-device AI" settings: an enable toggle + the downloadable LLM (background download). */
@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val modelManager: ModelManager,
    private val settings: SettingsRepository,
) : ViewModel() {

    val modelState: StateFlow<ModelManager.State> = modelManager.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelManager.State.Absent)

    val aiEnabled: StateFlow<Boolean> = settings.aiEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun sizeMb(): Long? = modelManager.sizeBytes()?.let { it / (1024 * 1024) }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setAiEnabled(enabled) }
    }

    fun download() = modelManager.download()
    fun cancel() = modelManager.cancel()
    fun delete() = modelManager.delete()
}
