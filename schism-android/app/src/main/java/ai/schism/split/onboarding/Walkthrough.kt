package ai.schism.split.onboarding

import ai.schism.split.core.ui.SchismLogo
import ai.schism.split.core.ui.SchismPrimaryButton
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch

private class WalkPage(
    val title: String,
    val body: String,
    val illustration: @Composable (Modifier, Color) -> Unit,
)

private class Feature(val icon: ImageVector, val label: String)

/**
 * First-run walkthrough: a swipeable, animated tour of what Schism does before we ask for the
 * user's details. Three illustrated hero pages lead into a grid that surfaces every feature, so
 * nothing stays hidden. Illustrations parallax-scale on swipe; the page dots animate into a pill.
 */
@Composable
fun Walkthrough(onFinish: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val pages = remember(cs) {
        listOf(
            WalkPage(
                "Split, sorted",
                "Share costs with any group — flatmates, trips, dinners — and always know who owes what.",
            ) { m, t -> SplitIllustration(m, t) },
            WalkPage(
                "Snap a bill, split by AI",
                "Photograph a receipt and Schism reads each line item, then splits it across the group for you.",
            ) { m, t -> ScanIllustration(m, t) },
            WalkPage(
                "Just say it",
                "Add an expense by voice — “I paid 400 for lunch, split with Aisha” — parsed on your device.",
            ) { m, t -> VoiceIllustration(m, t) },
        )
    }
    val features = remember {
        listOf(
            Feature(Icons.Filled.Groups, "Groups & fair splits"),
            Feature(Icons.Filled.DocumentScanner, "Scan a bill · AI split"),
            Feature(Icons.Filled.Mic, "Voice quick-add"),
            Feature(Icons.Filled.Sms, "Bank SMS auto-import"),
            Feature(Icons.Filled.Payments, "Settle up via UPI"),
            Feature(Icons.Filled.PieChart, "Spending insights"),
            Feature(Icons.Filled.QrCode2, "QR & link invites"),
            Feature(Icons.Filled.Contacts, "Add from contacts"),
        )
    }
    val tints = listOf(cs.secondaryContainer, cs.tertiaryContainer, cs.primaryContainer)
    val pageCount = pages.size + 1 // + feature grid
    val pagerState = rememberPagerState { pageCount }
    val scope = rememberCoroutineScope()
    val isLast = pagerState.currentPage == pageCount - 1

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
        ) {
            // Brand + Skip (always available).
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SchismLogo(size = 30.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("Schism", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onFinish) { Text("Skip") }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) { page ->
                val offset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                val absOffset = offset.absoluteValue.coerceIn(0f, 1f)
                if (page < pages.size) {
                    IllustratedPage(pages[page], tints[page], offset, absOffset)
                } else {
                    FeatureGridPage(features, absOffset)
                }
            }

            // Page indicator.
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(pageCount) { i ->
                    val active = pagerState.currentPage == i
                    val width by animateDpAsState(if (active) 26.dp else 8.dp, label = "dotW")
                    val color by animateColorAsState(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        label = "dotC",
                    )
                    Box(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .height(8.dp)
                            .width(width)
                            .clip(CircleShape)
                            .background(color),
                    )
                }
            }

            SchismPrimaryButton(
                onClick = {
                    if (isLast) onFinish() else scope.launch {
                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                    }
                },
                shape = RoundedCornerShape(100),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 20.dp),
            ) {
                Text(if (isLast) "Get started" else "Next")
            }
        }
    }
}

@Composable
private fun IllustratedPage(page: WalkPage, tint: Color, offset: Float, absOffset: Float) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        page.illustration(
            Modifier.graphicsLayer {
                val s = 1f - absOffset * 0.25f
                scaleX = s; scaleY = s
                translationX = -offset * size.width * 0.15f
                alpha = 1f - absOffset * 0.5f
            },
            tint,
        )
        Spacer(Modifier.height(40.dp))
        Text(
            page.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.alpha(1f - absOffset),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            page.body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.alpha(1f - absOffset),
        )
    }
}

@Composable
private fun FeatureGridPage(features: List<Feature>, absOffset: Float) {
    Column(
        modifier = Modifier.fillMaxSize().alpha(1f - absOffset),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Everything in one place",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "One private, on-device app for every way you spend and split.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        features.chunked(2).forEach { rowFeatures ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowFeatures.forEach { f -> FeatureTile(f, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun FeatureTile(feature: Feature, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        feature.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Text(
                feature.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
