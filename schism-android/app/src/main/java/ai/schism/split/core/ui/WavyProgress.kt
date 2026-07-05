package ai.schism.split.core.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Material 3 Expressive-style **wavy** progress indicator: an indeterminate squiggle that flows
 * left-to-right. Used app-wide instead of the plain circular spinner so loading states carry the
 * same playful, expressive character as the rest of the design system.
 */
@Composable
fun WavyProgress(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    width: Dp = 120.dp,
    height: Dp = 16.dp,
) {
    val transition = rememberInfiniteTransition(label = "wavy")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = 1000, easing = LinearEasing)),
        label = "phase",
    )
    Canvas(modifier = modifier.width(width).height(height)) {
        val midY = size.height / 2f
        val amp = size.height * 0.30f
        val wavelength = size.width / 2.4f
        val k = (2f * PI / wavelength).toFloat()
        val stroke = 4.dp.toPx()

        // Faint straight track so the loading region reads even at rest.
        drawLine(
            color = trackColor,
            start = Offset(0f, midY),
            end = Offset(size.width, midY),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )

        // Flowing squiggle.
        val path = Path()
        var x = 0f
        path.moveTo(0f, midY + amp * sin(-phase))
        while (x <= size.width) {
            path.lineTo(x, midY + amp * sin(k * x - phase))
            x += 2f
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** Centered full-slice loader with the wavy indicator and an optional caption. */
@Composable
fun SchismLoader(
    modifier: Modifier = Modifier.fillMaxSize(),
    label: String? = null,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WavyProgress()
            if (label != null) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }
}
