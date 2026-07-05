package ai.schism.split.groups.detail

import ai.schism.split.core.ui.UiState
import ai.schism.split.groups.detail.tabs.ActivityTab
import ai.schism.split.groups.detail.tabs.BalancesTab
import ai.schism.split.groups.detail.tabs.ExpensesTab
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

private enum class DetailTab(val label: String) { Expenses("Expenses"), Balances("Balances"), Activity("Activity") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    onBack: () -> Unit,
    onAddExpense: (groupId: String) -> Unit,
    onEditExpense: (groupId: String, expenseId: String) -> Unit,
    onOpenDashboard: (groupId: String) -> Unit,
    viewModel: GroupDetailViewModel = hiltViewModel(),
) {
    val group by viewModel.group.collectAsState()
    val expenses by viewModel.expenses.collectAsState()
    val balances by viewModel.balances.collectAsState()
    val activities by viewModel.activities.collectAsState()
    var selected by remember { mutableIntStateOf(0) }

    val g = group
    val groupId = g?.id
    val currency = g?.currency ?: ""
    val participantNames = remember(g) { g?.participants?.associate { it.id to it.name } ?: emptyMap() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(g?.name ?: "Group") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (groupId != null) {
                        IconButton(onClick = { onOpenDashboard(groupId) }) {
                            Icon(Icons.Filled.BarChart, contentDescription = "Insights")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (groupId != null && selected == DetailTab.Expenses.ordinal) {
                FloatingActionButton(onClick = { onAddExpense(groupId) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add expense")
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selected) {
                DetailTab.entries.forEachIndexed { i, tab ->
                    Tab(selected = selected == i, onClick = { selected = i }, text = { Text(tab.label) })
                }
            }
            when (DetailTab.entries[selected]) {
                DetailTab.Expenses -> ExpensesTab(
                    state = expenses,
                    currency = currency,
                    participantNames = participantNames,
                    onEditExpense = { expenseId -> groupId?.let { onEditExpense(it, expenseId) } },
                )
                DetailTab.Balances -> BalancesTab(
                    state = balances,
                    currency = currency,
                    participantNames = participantNames,
                    youParticipantId = g?.activeParticipantId,
                )
                DetailTab.Activity -> ActivityTab(state = activities, participantNames = participantNames)
            }
        }
    }
}

/** Shared Loading/Empty/Error rendering for the detail tabs; [content] renders the loaded value. */
@Composable
fun <T> StateSlice(
    state: UiState<T>,
    emptyMessage: String,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        is UiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        is UiState.Empty -> CenteredMessage(emptyMessage)
        is UiState.Error -> CenteredMessage(state.message)
        is UiState.Data -> content(state.value)
    }
}

@Composable
private fun CenteredMessage(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
