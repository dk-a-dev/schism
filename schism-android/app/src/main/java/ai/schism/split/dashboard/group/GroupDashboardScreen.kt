package ai.schism.split.dashboard.group

import ai.schism.split.core.ui.UiState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDashboardScreen(
    onBack: () -> Unit,
    viewModel: GroupDashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Insights") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val s = state) {
                is UiState.Loading -> Centered { CircularProgressIndicator() }
                is UiState.Empty -> Centered {
                    Message("Nothing to show yet", "Add an expense to see this group's insights.")
                }
                is UiState.Error -> Centered { Message("Couldn't load insights", s.message) }
                is UiState.Data -> DashboardContent(s.value)
            }
        }
    }
}

@Composable
private fun DashboardContent(ui: GroupDashboardUi) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text(ui.name, style = MaterialTheme.typography.headlineSmall) }
        item { SummaryCards(ui) }
        if (ui.byCategory.isNotEmpty()) item { CategoryBreakdown(ui.byCategory) }
        if (ui.byMonth.isNotEmpty()) item { MonthlyTrend(ui.byMonth) }
        if (ui.topExpenses.isNotEmpty()) item { TopExpenses(ui.topExpenses) }
        if (ui.byParticipant.isNotEmpty()) item { ParticipantNets(ui.byParticipant) }
        ui.personal?.let { personal -> item { PersonalSection(personal) } }
    }
}

@Composable
private fun SummaryCards(ui: GroupDashboardUi) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard(Modifier.weight(1f), "Total spending", ui.totalSpendingFormatted)
        StatCard(Modifier.weight(1f), "Expenses", ui.expenseCount.toString())
        StatCard(Modifier.weight(1f), "Average", ui.averageExpenseFormatted)
    }
}

@Composable
private fun StatCard(modifier: Modifier, label: String, value: String) {
    Card(modifier = modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CategoryBreakdown(categories: List<CategoryUi>) {
    SectionCard("By category") {
        categories.forEach { category ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text(category.name.ifBlank { "Uncategorized" }, style = MaterialTheme.typography.bodyMedium)
                    Text(category.amountFormatted, style = MaterialTheme.typography.bodyMedium)
                }
                ProportionBar(category.fraction)
            }
        }
    }
}

@Composable
private fun ProportionBar(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun MonthlyTrend(months: List<MonthUi>) {
    SectionCard("Monthly trend") { months.forEach { LabelValueRow(it.month, it.amountFormatted) } }
}

@Composable
private fun TopExpenses(expenses: List<TopExpenseUi>) {
    SectionCard("Top expenses") {
        expenses.forEach { LabelValueRow(it.title.ifBlank { "Untitled" }, it.amountFormatted) }
    }
}

@Composable
private fun ParticipantNets(participants: List<ParticipantUi>) {
    SectionCard("Per participant") {
        participants.forEach { participant ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text(participant.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        participant.netFormatted,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    "Paid ${participant.paidFormatted} · Share ${participant.shareFormatted}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PersonalSection(personal: PersonalUi) {
    SectionCard("Your share") {
        LabelValueRow("Paid", personal.paidFormatted)
        LabelValueRow("Your share", personal.shareFormatted)
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text("Net", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(personal.netFormatted, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun LabelValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Message(title: String, body: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(24.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
