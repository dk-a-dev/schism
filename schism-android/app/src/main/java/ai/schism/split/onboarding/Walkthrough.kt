package ai.schism.split.onboarding

import ai.schism.split.R
import ai.schism.split.core.ui.SchismLogo
import ai.schism.split.core.ui.SchismPrimaryButton
import androidx.annotation.StringRes
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch

internal enum class WalkIllustration { SPLIT, SCAN, LIVE_SPLIT, PRIVACY }

internal data class WalkPage(
    @param:StringRes val titleRes: Int,
    @param:StringRes val bodyRes: Int,
    @param:StringRes val illustrationContentDescriptionRes: Int,
    val illustration: WalkIllustration,
)

/** Immutable, resource-backed page metadata so copy and ordering can be verified without rendering. */
internal val walkPages: List<WalkPage> = listOf(
    WalkPage(
        titleRes = R.string.onboarding_groups_title,
        bodyRes = R.string.onboarding_groups_body,
        illustrationContentDescriptionRes = R.string.onboarding_groups_illustration_description,
        illustration = WalkIllustration.SPLIT,
    ),
    WalkPage(
        titleRes = R.string.onboarding_capture_title,
        bodyRes = R.string.onboarding_capture_body,
        illustrationContentDescriptionRes = R.string.onboarding_capture_illustration_description,
        illustration = WalkIllustration.SCAN,
    ),
    WalkPage(
        titleRes = R.string.onboarding_live_split_title,
        bodyRes = R.string.onboarding_live_split_body,
        illustrationContentDescriptionRes = R.string.onboarding_live_split_illustration_description,
        illustration = WalkIllustration.LIVE_SPLIT,
    ),
    WalkPage(
        titleRes = R.string.onboarding_privacy_title,
        bodyRes = R.string.onboarding_privacy_body,
        illustrationContentDescriptionRes = R.string.onboarding_privacy_illustration_description,
        illustration = WalkIllustration.PRIVACY,
    ),
)

/** First-run, skippable four-page value tour. Authentication remains a separate next step. */
@Composable
fun Walkthrough(onFinish: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tints = listOf(
        colors.secondaryContainer,
        colors.tertiaryContainer,
        colors.primaryContainer,
        colors.surfaceContainerHighest,
    )
    val pagerState = rememberPagerState { walkPages.size }
    val scope = rememberCoroutineScope()
    val isLast = pagerState.currentPage == walkPages.lastIndex

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
        ) {
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
                TextButton(onClick = onFinish) { Text(stringResource(R.string.onboarding_skip)) }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) { pageIndex ->
                val offset = (pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction
                val absOffset = offset.absoluteValue.coerceIn(0f, 1f)
                IllustratedPage(walkPages[pageIndex], tints[pageIndex], offset, absOffset)
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(walkPages.size) { index ->
                    val active = pagerState.currentPage == index
                    val width by animateDpAsState(if (active) 26.dp else 8.dp, label = "dotW")
                    val color by animateColorAsState(
                        if (active) colors.primary else colors.outlineVariant,
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
                    if (isLast) {
                        onFinish()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
                shape = RoundedCornerShape(100),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 20.dp),
            ) {
                Text(
                    stringResource(
                        if (isLast) R.string.onboarding_get_started else R.string.onboarding_next,
                    ),
                )
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
        val illustrationDescription = stringResource(page.illustrationContentDescriptionRes)
        val illustrationModifier = Modifier
            .semantics { contentDescription = illustrationDescription }
            .graphicsLayer {
                val scale = 1f - absOffset * 0.25f
                scaleX = scale
                scaleY = scale
                translationX = -offset * size.width * 0.15f
                alpha = 1f - absOffset * 0.5f
            }
        when (page.illustration) {
            WalkIllustration.SPLIT -> SplitIllustration(illustrationModifier, tint)
            WalkIllustration.SCAN -> ScanIllustration(illustrationModifier, tint)
            WalkIllustration.LIVE_SPLIT -> SplitIllustration(illustrationModifier, tint)
            WalkIllustration.PRIVACY -> VoiceIllustration(illustrationModifier, tint)
        }
        Spacer(Modifier.height(40.dp))
        Text(
            stringResource(page.titleRes),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.alpha(1f - absOffset),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(page.bodyRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.alpha(1f - absOffset),
        )
    }
}
