package ai.schism.split.core.ui

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/*
 * Material-3-Expressive-style CIRCULAR loaders that thematically evoke "splitting a bill" — a pie
 * being divided into shares, a shape morphing between forms, and dots orbiting a shared centre.
 * All are self-contained, theme-aware (use MaterialTheme.colorScheme), and driven purely by
 * rememberInfiniteTransition (no Date.now()/Math.random() in composition).
 */

/**
 * Flagship "splitting" loader: a circle divided into pie WEDGES separated by gaps, continuously
 * rotating, with each wedge gently pulsing its own radius (staggered) so it reads as a bill being
 * divided into shares. A small coin sits in the centre.
 */
@Composable
fun SplitLoader(modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val coinColor = MaterialTheme.colorScheme.surfaceVariant

    val transition = rememberInfiniteTransition(label = "split-loader")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing)),
        label = "spin",
    )
    // A single looping phase drives each wedge's pulse, staggered by index below.
    val pulsePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = EaseInOutCubic), RepeatMode.Reverse),
        label = "pulse",
    )

    val wedgeColors = listOf(primary, tertiary, secondary)
    val wedgeCount = wedgeColors.size
    val sweep = 360f / wedgeCount
    val gap = 10f

    Canvas(modifier = modifier.size(size)) {
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val baseR = this.size.minDimension / 2f * 0.92f

        rotate(spin, pivot = c) {
            wedgeColors.forEachIndexed { i, color ->
                // Stagger each wedge's pulse by offsetting where it sits in the shared phase.
                val local = (pulsePhase + i / wedgeCount.toFloat()) % 1f
                val bump = 1f - 0.10f * kotlin.math.abs(local - 0.5f) * 2f
                val r = baseR * (0.86f + 0.14f * bump)
                val box = Rect(c.x - r, c.y - r, c.x + r, c.y + r)
                drawArc(
                    color = color,
                    startAngle = i * sweep + gap / 2f,
                    sweepAngle = sweep - gap,
                    useCenter = true,
                    topLeft = box.topLeft,
                    size = box.size,
                )
            }
        }
        // Coin in the centre.
        val coinR = baseR * 0.34f
        drawCircle(coinColor, radius = coinR, center = c)
    }
}

/**
 * A single filled blob that morphs its shape between a circle and a rounded 3/4-lobed form while
 * slowly rotating — the "expressive morphing shape" feel, approximated with an animated Path
 * whose radius wobbles per-angle with a small integer lobe count.
 */
@Composable
fun MorphLoader(modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val color = MaterialTheme.colorScheme.primary

    val transition = rememberInfiniteTransition(label = "morph-loader")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing)),
        label = "spin",
    )
    // Morph amount cycles 0 -> 1 -> 0: 0 is a plain circle, 1 is a lobed blob.
    val morph by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = EaseInOutCubic), RepeatMode.Reverse),
        label = "morph",
    )
    // Slowly alternate between a 3-lobed and 4-lobed silhouette for extra liveliness.
    val lobeMix by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5200, easing = EaseInOutCubic), RepeatMode.Reverse),
        label = "lobeMix",
    )

    Canvas(modifier = modifier.size(size)) {
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val baseR = this.size.minDimension / 2f * 0.86f
        val lobes3 = 3
        val lobes4 = 4
        val steps = 64
        val path = Path()

        rotate(spin, pivot = c) {
            for (step in 0..steps) {
                val t = step.toFloat() / steps
                val theta = t * 2f * PI.toFloat()
                val wobble3 = sin(theta * lobes3) * 0.16f
                val wobble4 = sin(theta * lobes4) * 0.16f
                val wobble = wobble3 * (1f - lobeMix) + wobble4 * lobeMix
                val r = baseR * (1f + morph * wobble)
                val x = c.x + r * cos(theta)
                val y = c.y + r * sin(theta)
                if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, color = color)
        }
    }
}

/**
 * Three small dots orbiting a shared centre at different phases — people gathering around a split.
 */
@Composable
fun OrbitLoader(modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val centerColor = MaterialTheme.colorScheme.surfaceVariant

    val transition = rememberInfiniteTransition(label = "orbit-loader")
    val orbit by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "orbit",
    )

    val dotColors = listOf(primary, secondary, tertiary)

    Canvas(modifier = modifier.size(size)) {
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val radius = this.size.minDimension / 2f * 0.68f
        val dotR = this.size.minDimension * 0.09f

        drawCircle(centerColor, radius = this.size.minDimension * 0.12f, center = c)

        dotColors.forEachIndexed { i, color ->
            val angle = Math.toRadians((orbit + i * (360f / dotColors.size)).toDouble())
            val x = c.x + radius * cos(angle).toFloat()
            val y = c.y + radius * sin(angle).toFloat()
            drawCircle(color, radius = dotR, center = Offset(x, y))
        }
    }
}
