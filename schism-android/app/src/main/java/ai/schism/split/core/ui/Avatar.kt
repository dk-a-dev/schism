package ai.schism.split.core.ui

import ai.schism.split.core.theme.AvatarColors
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue

/** A circular avatar with a deterministic brand color (from [key]) and the [name]'s initials. */
@Composable
fun InitialAvatar(
    name: String,
    modifier: Modifier = Modifier,
    key: String = name,
    size: Dp = 44.dp,
) {
    val color = AvatarColors[key.hashCode().absoluteValue % AvatarColors.size]
    Surface(shape = CircleShape, color = color, modifier = modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = initialsOf(name),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

private fun initialsOf(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}
