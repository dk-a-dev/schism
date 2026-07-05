package ai.schism.split.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Schism mark: a "split coin" of two half-discs sheared apart with an open seam between them —
 * the visual pun on splitting a shared cost. Drawn in Compose so it scales crisply anywhere (nav
 * headers, onboarding hero, empty states). Colors default to the brand mint/cream on a green disc.
 */
@Composable
fun SchismLogo(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    background: Color = Color(0xFF14874F),
    leftHalf: Color = Color(0xFFB6ECCE),
    rightHalf: Color = Color(0xFFFBFAF4),
    showBackground: Boolean = true,
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        if (showBackground) {
            drawCircle(color = background, radius = s / 2f, center = Offset(cx, cy))
        }
        val r = s * 0.26f
        val gap = s * 0.035f
        val shear = s * 0.05f
        // Left half-disc: filled semicircle, flat (vertical) edge just left of centre, bulging left.
        drawArc(
            color = leftHalf,
            startAngle = 90f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset((cx - gap) - 2f * r, (cy - shear) - r),
            size = Size(2f * r, 2f * r),
        )
        // Right half-disc: sheared slightly down, flat edge just right of centre, bulging right.
        drawArc(
            color = rightHalf,
            startAngle = -90f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(cx + gap, (cy + shear) - r),
            size = Size(2f * r, 2f * r),
        )
    }
}
