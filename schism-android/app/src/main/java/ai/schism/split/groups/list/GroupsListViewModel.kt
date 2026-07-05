package ai.schism.split.groups.list

import ai.schism.split.core.settings.SettingsRepository
import ai.schism.split.core.ui.UiState
import ai.schism.split.groups.data.Group
import ai.schism.split.groups.data.GroupRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A group as shown in the list: favorites are pinned first, then alphabetical. */
data class GroupSummary(
    val id: String,
    val name: String,
    val currency: String,
    val memberCount: Int,
)

@HiltViewModel
class GroupsListViewModel @Inject constructor(
    private val repo: GroupRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    /** One-shot refresh failures (a snackbar), kept out of [state] so cached data survives. */
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    val state: StateFlow<UiState<List<GroupSummary>>> =
        repo.observeGroups()
            .map { groups ->
                val summaries = groups.toSummaries()
                if (summaries.isEmpty()) UiState.Empty else UiState.Data(summaries)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    init {
        refresh()
    }

    /** Refetch every group this device knows about; leaves the cache (and [state]) intact on failure. */
    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                val ids = settings.knownGroupIds.first().toList()
                repo.refreshGroups(ids).onFailure {
                    _errors.tryEmit(it.message ?: "Couldn't refresh groups")
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private fun List<Group>.toSummaries(): List<GroupSummary> =
        sortedWith(compareByDescending<Group> { it.isFavorite }.thenBy { it.name.lowercase() })
            .map { GroupSummary(it.id, it.name, it.currency, it.participants.size) }
}
