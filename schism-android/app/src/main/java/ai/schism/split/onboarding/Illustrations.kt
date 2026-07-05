package ai.schism.split.onboarding

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp

/**
 * Original, geometric onboarding illustrations drawn in Compose in the Schism palette — friendly,
 * layered flat shapes in the spirit of modern product illustration, fully offline and theme-aware.
 * Each sits on a soft tinted disc and carries one gentle animation so the walkthrough feels alive.
 */

private val Green = Color(0xFF14874F)
private val Mint = Color(0xFF6FE0A6)
private val MintSoft = Color(0xFFB6ECCE)
private val Amber = Color(0xFF9A7A2E)
private val AmberSoft = Color(0xFFE8C879)
private val Clay = Color(0xFFBC5533)
private val Cream = Color(0xFFFBFAF4)
private val Ink = Color(0xFF1A1A16)

/** People splitting a bill: a three-way "pie" split with a coin in the middle. */
@Composable
fun SplitIllustration(modifier: Modifier = Modifier, tint: Color) {
    val transition = rememberInfiniteTransition(label = "split")
    val spin by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(26000)), label = "spin",
    )
    Box(modifier.fillMaxWidth().height(240.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(tint, radius = size.minDimension * 0.42f, center = c)
            val r = size.minDimension * 0.28f
            val box = Rect(c.x - r, c.y - r, c.x + r, c.y + r)
            val wedges = listOf(Green, AmberSoft, Clay)
            val gap = 7f
            rotate(spin, pivot = c) {
                wedges.forEachIndexed { i, col ->
                    drawArc(
                        color = col,
                        startAngle = i * 120f + gap,
                        sweepAngle = 120f - gap * 2,
                        useCenter = true,
                        topLeft = box.topLeft,
                        size = box.size,
                    )
                }
            }
            // Coin in the centre.
            drawCircle(Cream, radius = r * 0.42f, center = c)
            drawCircle(Green, radius = r * 0.42f, center = c, style = Stroke(width = 4f))
        }
    }
}

/** Snap a receipt: a tilted receipt card inside a scan frame with a sparkle. */
@Composable
fun ScanIllustration(modifier: Modifier = Modifier, tint: Color) {
    val transition = rememberInfiniteTransition(label = "scan")
    val twinkle by transition.animateFloat(
        initialValue = 0.6f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "twinkle",
    )
    Box(modifier.fillMaxWidth().height(240.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(tint, radius = size.minDimension * 0.42f, center = c)
            rotate(-8f, pivot = c) {
                val w = size.minDimension * 0.34f
                val h = size.minDimension * 0.46f
                val tl = Offset(c.x - w / 2f, c.y - h / 2f)
                drawRoundRect(Cream, topLeft = tl, size = Size(w, h), cornerRadius = CornerRadius(14f, 14f))
                // receipt lines
                val pad = w * 0.16f
                val lineColor = Color(0xFFCFCcC0)
                for (i in 0..3) {
                    val y = tl.y + h * (0.24f + i * 0.16f)
                    val len = if (i == 3) w - pad * 2 else (w - pad * 2) * (0.9f - i * 0.12f)
                    drawLine(lineColor, Offset(tl.x + pad, y), Offset(tl.x + pad + len, y), strokeWidth = 5f, cap = StrokeCap.Round)
                }
                // total line (green, bold)
                val ty = tl.y + h * 0.84f
                drawLine(Green, Offset(tl.x + pad, ty), Offset(tl.x + w - pad, ty), strokeWidth = 9f, cap = StrokeCap.Round)
            }
            // scan corner brackets
            val bw = size.minDimension * 0.40f
            val bt = Offset(c.x - bw / 2f, c.y - bw / 2f)
            drawScanCorners(bt, bw, Green)
            // sparkle
            translate(left = c.x + bw * 0.42f, top = c.y - bw * 0.5f) {
                drawSparkle(radius = 16f * twinkle, color = Amber)
            }
        }
    }
}

/** Voice quick-add: a mic with pulsing sound rings and a floating amount chip. */
@Composable
fun VoiceIllustration(modifier: Modifier = Modifier, tint: Color) {
    val transition = rememberInfiniteTransition(label = "voice")
    val pulse by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800)), label = "pulse",
    )
    Box(modifier.fillMaxWidth().height(240.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(tint, radius = size.minDimension * 0.42f, center = c)
            // pulsing rings
            for (i in 0..2) {
                val p = (pulse + i / 3f) % 1f
                drawCircle(
                    color = Mint.copy(alpha = (1f - p) * 0.5f),
                    radius = size.minDimension * (0.16f + p * 0.28f),
                    center = c,
                    style = Stroke(width = 6f),
                )
            }
            // mic body
            val mw = size.minDimension * 0.13f
            val mh = size.minDimension * 0.26f
            drawCircle(Green, radius = size.minDimension * 0.16f, center = c)
            drawRoundRect(
                Cream,
                topLeft = Offset(c.x - mw / 2f, c.y - mh / 2f),
                size = Size(mw, mh),
                cornerRadius = CornerRadius(mw / 2f, mw / 2f),
            )
            // mic stand
            drawArc(
                color = Cream,
                startAngle = 20f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(c.x - mw, c.y - mw * 0.2f),
                size = Size(mw * 2f, mw * 2f),
                style = Stroke(width = 5f, cap = StrokeCap.Round),
            )
            drawLine(Cream, Offset(c.x, c.y + mw * 1.5f), Offset(c.x, c.y + mh * 0.8f), strokeWidth = 5f, cap = StrokeCap.Round)
            // floating amount chip
            val chipW = size.minDimension * 0.30f
            val chipH = size.minDimension * 0.13f
            val chipTl = Offset(c.x + size.minDimension * 0.16f, c.y - size.minDimension * 0.36f)
            drawRoundRect(Cream, topLeft = chipTl, size = Size(chipW, chipH), cornerRadius = CornerRadius(chipH / 2f, chipH / 2f))
            drawCircle(Amber, radius = chipH * 0.24f, center = Offset(chipTl.x + chipH * 0.5f, chipTl.y + chipH / 2f))
            drawLine(Ink, Offset(chipTl.x + chipH * 0.9f, chipTl.y + chipH / 2f), Offset(chipTl.x + chipW - chipH * 0.4f, chipTl.y + chipH / 2f), strokeWidth = 6f, cap = StrokeCap.Round)
        }
    }
}

private fun DrawScope.drawScanCorners(topLeft: Offset, side: Float, color: Color) {
    val len = side * 0.22f
    val sw = 7f
    val r = topLeft.x + side
    val b = topLeft.y + side
    val t = topLeft.y
    val l = topLeft.x
    fun corner(a: Offset, h: Offset, v: Offset) {
        drawLine(color, a, h, strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, a, v, strokeWidth = sw, cap = StrokeCap.Round)
    }
    corner(Offset(l, t), Offset(l + len, t), Offset(l, t + len))
    corner(Offset(r, t), Offset(r - len, t), Offset(r, t + len))
    corner(Offset(l, b), Offset(l + len, b), Offset(l, b - len))
    corner(Offset(r, b), Offset(r - len, b), Offset(r, b - len))
}

private fun DrawScope.drawSparkle(radius: Float, color: Color) {
    val path = Path().apply {
        moveTo(0f, -radius)
        cubicTo(radius * 0.25f, -radius * 0.25f, radius * 0.25f, -radius * 0.25f, radius, 0f)
        cubicTo(radius * 0.25f, radius * 0.25f, radius * 0.25f, radius * 0.25f, 0f, radius)
        cubicTo(-radius * 0.25f, radius * 0.25f, -radius * 0.25f, radius * 0.25f, -radius, 0f)
        cubicTo(-radius * 0.25f, -radius * 0.25f, -radius * 0.25f, -radius * 0.25f, 0f, -radius)
        close()
    }
    drawPath(path, color)
}
