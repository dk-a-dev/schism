package ai.schism.split.settings

import ai.schism.split.core.ai.ModelManager
import ai.schism.split.core.settings.SettingsRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs the "On-device AI" settings: the downloadable LLM used to parse voice/receipts. */
@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val modelManager: ModelManager,
    private val settings: SettingsRepository,
) : ViewModel() {

    val modelState: StateFlow<ModelManager.State> = modelManager.state

    val modelUrl: StateFlow<String> = settings.aiModelUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun sizeMb(): Long? = modelManager.sizeBytes()?.let { it / (1024 * 1024) }

    fun setUrl(url: String) {
        viewModelScope.launch { settings.setAiModelUrl(url) }
    }

    fun download() {
        viewModelScope.launch { modelManager.download(settings.aiModelUrl.first()) }
    }

    fun delete() {
        modelManager.delete()
    }
}
