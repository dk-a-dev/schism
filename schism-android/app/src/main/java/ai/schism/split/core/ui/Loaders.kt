package ai.schism.split.core.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * A small kit of distinct loading indicators so different surfaces feel varied and alive (instead of
 * one spinner everywhere): shimmer skeletons for lists, bouncing dots for compact waits, and a
 * contained circular for hero/summary screens. The circular Material-3-Expressive "splitting"
 * loaders (pie wedges, morphing blob, orbiting dots) live in ExpressiveLoaders.kt.
 */

/** An animated shimmer brush that sweeps a highlight across a muted base — for skeleton placeholders. */
@Composable
private fun shimmerBrush(): Brush {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.surfaceContainerHighest
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "progress",
    )
    val w = 700f
    val x = progress * (2f * w) - w
    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(x, 0f),
        end = Offset(x + w, 0f),
    )
}

/** A single shimmering block (rounded). Compose several into a skeleton row/card. */
@Composable
fun ShimmerBox(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(8.dp)) {
    Box(modifier.clip(shape).background(shimmerBrush()))
}

/** Skeleton list of avatar + two-line rows — used while a list screen loads (Groups, Inbox). */
@Composable
fun ListSkeleton(modifier: Modifier = Modifier.fillMaxWidth(), rows: Int = 6) {
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(rows) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShimmerBox(Modifier.size(48.dp), CircleShape)
                Spacer(Modifier.size(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShimmerBox(Modifier.fillMaxWidth(0.55f).height(16.dp))
                    ShimmerBox(Modifier.fillMaxWidth(0.35f).height(12.dp))
                }
                ShimmerBox(Modifier.size(60.dp, 18.dp))
            }
        }
    }
}

/** Three dots that bounce in sequence — a compact, playful wait for detail/summary panels. */
@Composable
fun DotsLoader(
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    dot: Dp = 10.dp,
) {
    val transition = rememberInfiniteTransition(label = "dots")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val scale by transition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(460, delayMillis = i * 140, easing = LinearEasing),
                    RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(Modifier.size(dot).scale(scale).clip(CircleShape).background(color))
        }
    }
}

/** A circular spinner sitting in a soft tinted disc — for hero/summary screens. */
@Composable
fun ContainedLoader(modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.size(64.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                strokeWidth = 3.dp,
                modifier = Modifier.size(30.dp),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
