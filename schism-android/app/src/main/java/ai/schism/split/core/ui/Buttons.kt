package ai.schism.split.core.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SelectableChipColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Shared expressive styling for Schism's primary/secondary call-to-action buttons and filter
 * chips: bold pill shapes, a comfortable touch target, and a confident label style, so the same
 * "big rounded button" language shows up everywhere instead of drifting screen to screen.
 */
private val CtaMinHeight = 56.dp
private val CtaContentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)

/** The one bold action a screen wants you to take: Create group, Save, Log in, Finalize, ... */
@Composable
fun SchismPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.large,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = CtaMinHeight),
        enabled = enabled,
        shape = shape,
        colors = colors,
        contentPadding = CtaContentPadding,
    ) {
        ProvideTextStyle(MaterialTheme.typography.titleMedium) { content() }
    }
}

/** A harmonised secondary action (Cancel, Add participant, Source code, ...) next to a primary CTA. */
@Composable
fun SchismSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.large,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = CtaMinHeight),
        enabled = enabled,
        shape = shape,
        colors = colors,
        contentPadding = CtaContentPadding,
    ) {
        ProvideTextStyle(MaterialTheme.typography.titleMedium) { content() }
    }
}

/** Expressive filter chip: fuller pill shape with a tonal secondary-container selected state. */
@Composable
fun SchismFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    colors: SelectableChipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ),
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        leadingIcon = leadingIcon,
        shape = MaterialTheme.shapes.large,
        colors = colors,
    )
}
