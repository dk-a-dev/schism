package ai.schism.split.dashboard.group

import ai.schism.split.core.money.formatMinor
import ai.schism.split.core.net.ApiService
import ai.schism.split.core.net.CategoryTotalDto
import ai.schism.split.core.net.ExpenseSummaryDto
import ai.schism.split.core.net.GroupDashboardDto
import ai.schism.split.core.net.MonthTotalDto
import ai.schism.split.core.net.ParticipantTotalDto
import ai.schism.split.core.net.PersonalInGroupDto
import ai.schism.split.core.ui.UiState
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The group dashboard with money pre-formatted for display. Money stays Long minor units everywhere
 * except here, where [formatMinor] turns it into a string using the group's currency symbol.
 */
data class GroupDashboardUi(
    val name: String,
    val totalSpendingFormatted: String,
    val expenseCount: Int,
    val reimbursementCount: Int,
    val averageExpenseFormatted: String,
    val byCategory: List<CategoryUi>,
    val byParticipant: List<ParticipantUi>,
    val byMonth: List<MonthUi>,
    val topExpenses: List<TopExpenseUi>,
    val personal: PersonalUi?,
)

/** [fraction] is this category's share of the largest category total, for a proportional bar. */
data class CategoryUi(
    val name: String,
    val amountFormatted: String,
    val count: Int,
    val fraction: Float,
)

data class ParticipantUi(
    val name: String,
    val paidFormatted: String,
    val shareFormatted: String,
    val netFormatted: String,
)

data class MonthUi(
    val month: String,
    val amountFormatted: String,
)

data class TopExpenseUi(
    val title: String,
    val amountFormatted: String,
)

data class PersonalUi(
    val name: String,
    val paidFormatted: String,
    val shareFormatted: String,
    val netFormatted: String,
)

@HiltViewModel
class GroupDashboardViewModel @Inject constructor(
    private val api: ApiService,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val groupId: String = checkNotNull(savedStateHandle["groupId"])
    private val participant: String? = savedStateHandle["participant"]

    private val _state = MutableStateFlow<UiState<GroupDashboardUi>>(UiState.Loading)
    val state: StateFlow<UiState<GroupDashboardUi>> = _state.asStateFlow()

    init {
        refresh()
    }

    /** (Re)load the dashboard; a failure surfaces as [UiState.Error] with the throwable's message. */
    fun refresh() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            runCatching { api.groupDashboard(groupId, participant) }
                .onSuccess { _state.value = UiState.Data(it.toUi()) }
                .onFailure { _state.value = UiState.Error(it.message ?: "Couldn't load insights") }
        }
    }
}

private fun GroupDashboardDto.toUi(): GroupDashboardUi {
    val maxCategory = byCategory.maxOfOrNull { it.amount } ?: 0L
    return GroupDashboardUi(
        name = name,
        totalSpendingFormatted = formatMinor(totalSpending, currency),
        expenseCount = expenseCount,
        reimbursementCount = reimbursementCount,
        averageExpenseFormatted = formatMinor(averageExpense, currency),
        byCategory = byCategory.map { it.toUi(currency, maxCategory) },
        byParticipant = byParticipant.map { it.toUi(currency) },
        byMonth = byMonth.map { it.toUi(currency) },
        topExpenses = topExpenses.map { it.toUi(currency) },
        personal = personal?.toUi(currency),
    )
}

private fun CategoryTotalDto.toUi(currency: String, maxAmount: Long) = CategoryUi(
    name = name,
    amountFormatted = formatMinor(amount, currency),
    count = count,
    fraction = if (maxAmount > 0) (amount.toFloat() / maxAmount).coerceIn(0f, 1f) else 0f,
)

private fun ParticipantTotalDto.toUi(currency: String) = ParticipantUi(
    name = name,
    paidFormatted = formatMinor(paid, currency),
    shareFormatted = formatMinor(share, currency),
    netFormatted = formatMinor(net, currency),
)

private fun MonthTotalDto.toUi(currency: String) = MonthUi(
    month = month,
    amountFormatted = formatMinor(amount, currency),
)

private fun ExpenseSummaryDto.toUi(currency: String) = TopExpenseUi(
    title = title,
    amountFormatted = formatMinor(amount, currency),
)

private fun PersonalInGroupDto.toUi(currency: String) = PersonalUi(
    name = name,
    paidFormatted = formatMinor(paid, currency),
    shareFormatted = formatMinor(share, currency),
    netFormatted = formatMinor(net, currency),
)
