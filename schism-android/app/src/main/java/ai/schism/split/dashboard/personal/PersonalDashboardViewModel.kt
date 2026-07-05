package ai.schism.split.dashboard.personal

import ai.schism.split.core.money.formatMinor
import ai.schism.split.core.net.ApiService
import ai.schism.split.core.net.CurrencyTotalDto
import ai.schism.split.core.net.PersonalDashboardDto
import ai.schism.split.core.net.PersonalGroupSliceDto
import ai.schism.split.core.settings.SettingsRepository
import ai.schism.split.core.ui.UiState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Your personal position across every group this device knows about. Money is NEVER summed across
 * currencies: the backend returns per-currency [totalsByCurrency] and each group slice carries its
 * own currency, so every figure is formatted with the symbol it belongs to.
 */
data class PersonalDashboardUi(
    val totalsByCurrency: List<CurrencyTotalUi>,
    val groups: List<GroupSliceUi>,
)

/** One currency's rolled-up position; [netRaw] is kept only so the UI can tint by sign. */
data class CurrencyTotalUi(
    val currencyCode: String,
    val net: String,
    val paid: String,
    val share: String,
    val groupCount: Int,
    val netRaw: Long,
)

/** Your position in a single group, formatted in that group's own currency. */
data class GroupSliceUi(
    val groupId: String,
    val groupName: String,
    val net: String,
    val paid: String,
    val share: String,
    val expenseCount: Int,
    val netRaw: Long,
)

@HiltViewModel
class PersonalDashboardViewModel @Inject constructor(
    private val api: ApiService,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<PersonalDashboardUi>>(UiState.Loading)
    val state: StateFlow<UiState<PersonalDashboardUi>> = _state.asStateFlow()

    init {
        refresh()
    }

    /**
     * Reload the dashboard. With no profile name or no known groups there is nothing to resolve
     * "you" against, so we emit [UiState.Empty] and skip the network entirely.
     */
    fun refresh() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            val participant = settings.profileName.first()
            val groupIds = settings.knownGroupIds.first()
            if (participant.isBlank() || groupIds.isEmpty()) {
                _state.value = UiState.Empty
                return@launch
            }
            runCatching {
                api.personalDashboard(participant = participant, groupIds = groupIds.joinToString(","))
            }.onSuccess { dto ->
                _state.value = UiState.Data(dto.toUi())
            }.onFailure {
                _state.value = UiState.Error(it.message ?: "Couldn't load your dashboard")
            }
        }
    }

    private fun PersonalDashboardDto.toUi(): PersonalDashboardUi = PersonalDashboardUi(
        totalsByCurrency = totals.map { it.toUi() },
        groups = groups.map { it.toUi() },
    )

    private fun CurrencyTotalDto.toUi(): CurrencyTotalUi = CurrencyTotalUi(
        currencyCode = currencyCode,
        net = formatMinor(net, currency),
        paid = formatMinor(paid, currency),
        share = formatMinor(share, currency),
        groupCount = groupCount,
        netRaw = net,
    )

    private fun PersonalGroupSliceDto.toUi(): GroupSliceUi = GroupSliceUi(
        groupId = groupId,
        groupName = groupName,
        net = formatMinor(net, currency),
        paid = formatMinor(paid, currency),
        share = formatMinor(share, currency),
        expenseCount = expenseCount,
        netRaw = net,
    )
}
