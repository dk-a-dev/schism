package ai.schism.split.walkthrough

import ai.schism.split.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The single coach mark on screen: a scrim, a highlight cut out around the registered target, and a
 * card with the instruction plus Back / Skip / Continue.
 *
 * ponytail: no animation at all, so "no infinite animation under reduced motion" holds by
 * construction. Add an entrance fade behind an animator-duration-scale check if it ever feels abrupt.
 */
@Composable
fun WalkthroughOverlay(
    targetId: WalkthroughTargetId?,
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    stepIndex: Int = -1,
    stepCount: Int = 0,
    onBack: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null,
) {
    val registry = LocalWalkthroughTargets.current
    val target = targetId?.let { registry[it] }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val insets = WindowInsets.safeDrawing

    var root by remember { mutableStateOf(Rect.Zero) }
    var cardHeight by remember { mutableFloatStateOf(0f) }

    val safeArea = Rect(
        left = root.left + insets.getLeft(density, layoutDirection),
        top = root.top + insets.getTop(density),
        right = root.right - insets.getRight(density, layoutDirection),
        bottom = root.bottom - insets.getBottom(density),
    )
    val gap = with(density) { 12.dp.toPx() }
    val layout = coachMarkLayout(target, safeArea, cardHeight, gap)

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(title) { runCatching { focusRequester.requestFocus() } }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Swallows every touch: the highlight is a window onto the app, never a live control.
            .pointerInput(Unit) { detectTapGestures { } }
            .onGloballyPositioned { root = it.boundsInWindow() }
            .semantics { isTraversalGroup = true; traversalIndex = -1f },
    ) {
        val ring = MaterialTheme.colorScheme.primary
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
        ) {
            drawRect(Color.Black.copy(alpha = 0.72f))
            val highlight = layout.highlight?.translate(-root.left, -root.top)?.inflate(gap / 2f)
                ?: return@Canvas
            val radius = CornerRadius(16.dp.toPx())
            drawRoundRect(
                color = Color.Black,
                topLeft = highlight.topLeft,
                size = highlight.size,
                cornerRadius = radius,
                blendMode = BlendMode.Clear,
            )
            drawRoundRect(
                color = ring,
                topLeft = highlight.topLeft,
                size = highlight.size,
                cornerRadius = radius,
                style = Stroke(width = 2.dp.toPx()),
            )
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, (layout.cardTop - root.top).roundToInt()) }
                .padding(horizontal = 16.dp)
                .onGloballyPositioned { cardHeight = it.size.height.toFloat() }
                .focusRequester(focusRequester)
                .focusable(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (stepCount > 0 && stepIndex >= 0) {
                    Text(
                        stringResource(R.string.walkthrough_step_counter, stepIndex + 1, stepCount),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.walkthrough_back))
                        }
                    }
                    if (onSkip != null) {
                        TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.walkthrough_skip))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(confirmLabel, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
