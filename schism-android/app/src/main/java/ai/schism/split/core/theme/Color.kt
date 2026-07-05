package ai.schism.split.core.theme

import androidx.compose.ui.graphics.Color

// Schism design system — "quiet luxury": a warm cream paper canvas, a deep-green brand with a mint
// accent, terracotta for money owed / errors, and amber for highlights. Full light + dark parity.
// See docs/design.md. A complete set of M3 slots is defined so no baseline-purple leaks anywhere.

// ---- Light ----
val LightPrimary = Color(0xFF14874F) // deep green
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFB6ECCE) // mint
val LightOnPrimaryContainer = Color(0xFF00351E)
val LightSecondary = Color(0xFF3E8F6A) // muted green
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFDCEFE2)
val LightOnSecondaryContainer = Color(0xFF0C2419)
val LightTertiary = Color(0xFF9A7A2E) // amber
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFF0E6CE)
val LightOnTertiaryContainer = Color(0xFF2C1D00)
val LightBackground = Color(0xFFFBFAF4) // warm cream
val LightOnBackground = Color(0xFF1A1A16)
val LightSurface = Color(0xFFFBFAF4)
val LightOnSurface = Color(0xFF1A1A16)
val LightSurfaceVariant = Color(0xFFECEBE6)
val LightOnSurfaceVariant = Color(0xFF605F58)
val LightOutline = Color(0xFF9A998E)
val LightOutlineVariant = Color(0xFFE3E0D5) // hairline
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF5F3EC)
val LightSurfaceContainer = Color(0xFFF1EFE7)
val LightSurfaceContainerHigh = Color(0xFFECEBE6)
val LightSurfaceContainerHighest = Color(0xFFE6E4DB)
val LightError = Color(0xFFBC5533) // terracotta
val LightErrorContainer = Color(0xFFF6E1D7)
val LightOnErrorContainer = Color(0xFF5C2B2E)

// ---- Dark ----
val DarkPrimary = Color(0xFF6FE0A6) // bright mint-green
val DarkOnPrimary = Color(0xFF00371F)
val DarkPrimaryContainer = Color(0xFF14572F)
val DarkOnPrimaryContainer = Color(0xFFC9F5DD)
val DarkSecondary = Color(0xFF9FD3B6)
val DarkOnSecondary = Color(0xFF08281B) // dark green
val DarkSecondaryContainer = Color(0xFF25493A)
val DarkOnSecondaryContainer = Color(0xFFDCEFE2)
val DarkTertiary = Color(0xFFD8BC84) // amber
val DarkOnTertiary = Color(0xFF3D2E00)
val DarkTertiaryContainer = Color(0xFF5A4718)
val DarkOnTertiaryContainer = Color(0xFFF0E6CE)
val DarkBackground = Color(0xFF0F0F0E)
val DarkOnBackground = Color(0xFFECEBE6)
val DarkSurface = Color(0xFF0F0F0E)
val DarkOnSurface = Color(0xFFECEBE6)
val DarkSurfaceVariant = Color(0xFF2A2926)
val DarkOnSurfaceVariant = Color(0xFFB7B6AC)
val DarkOutline = Color(0xFF8C8B84)
val DarkOutlineVariant = Color(0xFF2A2926)
val DarkSurfaceContainerLowest = Color(0xFF0C0C0A)
val DarkSurfaceContainerLow = Color(0xFF17170F)
val DarkSurfaceContainer = Color(0xFF1A1A16)
val DarkSurfaceContainerHigh = Color(0xFF201F1D)
val DarkSurfaceContainerHighest = Color(0xFF242320)
val DarkError = Color(0xFFE8987A) // soft terracotta
val DarkErrorContainer = Color(0xFF5C2B2E)
val DarkOnErrorContainer = Color(0xFFF6E1D7)

/** Accent palette for deterministic group/participant avatars — drawn from the design-system hues. */
val AvatarColors = listOf(
    Color(0xFF14874F), // green
    Color(0xFF9A7A2E), // amber
    Color(0xFFBC5533), // terracotta
    Color(0xFF3A5BA0), // indigo
    Color(0xFF2F8F8A), // teal
    Color(0xFF6C5677), // plum
    Color(0xFF5E7B2E), // olive
    Color(0xFFA33B3B), // clay
)
