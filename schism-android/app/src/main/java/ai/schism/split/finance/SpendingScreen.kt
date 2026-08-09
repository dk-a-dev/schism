@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ai.schism.split.finance

import ai.schism.split.R
import ai.schism.split.core.ads.InlineAdaptiveAd
import ai.schism.split.core.money.formatMinor
import ai.schism.split.core.theme.MoneyDisplay
import ai.schism.split.core.ui.InitialAvatar
import ai.schism.split.core.ui.SchismSecondaryButton
import ai.schism.split.core.ui.UiState
import ai.schism.split.plus.PlusSheet
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import ai.schism.split.core.ui.MorphLoader
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun SpendingScreen(
    viewModel: SpendingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val plus by viewModel.plus.collectAsState()
    val insights by viewModel.insights.collectAsState()
    var showPlusSheet by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    if (showPlusSheet) {
        PlusSheet(onDismiss = { showPlusSheet = false })
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Spending") },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is UiState.Loading -> Centered { MorphLoader() }
                is UiState.Empty -> EmptyState(
                    Icons.Filled.PieChart,
                    "No spending yet",
                    "Once your bank SMS and receipts land in the inbox, your spending insights show up here.",
                )
                is UiState.Error -> EmptyState(
                    Icons.Filled.PieChart,
                    "Couldn't load your spending",
                    s.message,
                )
                is UiState.Data -> SpendingContent(
                    summary = s.value,
                    plus = plus,
                    insights = insights,
                    onUnlockPlus = { showPlusSheet = true },
                )
            }
        }
    }
}

@Composable
private fun SpendingContent(
    summary: SpendingSummary,
    plus: Boolean,
    insights: PlusInsightsData?,
    onUnlockPlus: () -> Unit,
) {
    val maxMerchant = summary.byMerchant.maxOfOrNull { it.totalMinor }?.takeIf { it > 0L } ?: 1L
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { MonthHeroCard(summary.monthTotalMinor, summary.currency) }

        if (summary.byMerchant.isNotEmpty()) {
            item { SectionHeader("By merchant") }
            items(summary.byMerchant, key = { it.merchant }) { merchant ->
                MerchantRow(merchant, summary.currency, maxMerchant)
            }
        }

        if (summary.byMonth.isNotEmpty()) {
            item { SectionHeader("Monthly trend") }
            item { MonthlyTrendCard(summary.byMonth, summary.currency) }
        }

        item { SectionHeader(stringResource(R.string.plus_insights_title)) }
        if (plus) {
            items(insights?.byCurrency.orEmpty(), key = { it.currency }) { CurrencyInsightsCard(it) }
        } else {
            item { PlusInsightsTeaser(onUnlockPlus) }
        }

        // The app's only ad: after every insight card, visually separated, free accounts only.
        item { InlineAdaptiveAd() }
    }
}

@Composable
private fun CurrencyInsightsCard(insights: CurrencyInsights) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                formatMinor(insights.thisMonthMinor, insights.currency),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            val change = insights.changeMinor
            Text(
                when {
                    !insights.hasLastMonth -> stringResource(R.string.plus_insights_no_comparison)
                    change > 0 -> stringResource(
                        R.string.plus_insights_change_up,
                        formatMinor(change, insights.currency),
                    )
                    change < 0 -> stringResource(
                        R.string.plus_insights_change_down,
                        formatMinor(-change, insights.currency),
                    )
                    else -> stringResource(R.string.plus_insights_change_flat)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            insights.byMonth.forEach { month ->
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text(month.month, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        formatMinor(month.totalMinor, insights.currency),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlusInsightsTeaser(onUnlockPlus: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.plus_insights_locked),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SchismSecondaryButton(onClick = onUnlockPlus, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.plus_insights_unlock))
            }
        }
    }
}

@Composable
private fun MonthHeroCard(monthTotalMinor: Long, currency: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Spent this month",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatMinor(monthTotalMinor, currency),
                style = MoneyDisplay,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun MerchantRow(merchant: MerchantSpend, currency: String, maxMinor: Long) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                InitialAvatar(
                    name = merchant.merchant.ifBlank { "?" },
                    key = merchant.merchant,
                    size = 44.dp,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        merchant.merchant.ifBlank { "Unknown" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (merchant.count == 1) "1 transaction" else "${merchant.count} transactions",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    formatMinor(merchant.totalMinor, currency),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            ProportionBar(merchant.totalMinor.toFloat() / maxMinor.toFloat())
        }
    }
}

@Composable
private fun ProportionBar(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun MonthlyTrendCard(months: List<MonthSpend>, currency: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            months.forEach { month ->
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text(
                        month.month,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatMinor(month.totalMinor, currency),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, body: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(88.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
