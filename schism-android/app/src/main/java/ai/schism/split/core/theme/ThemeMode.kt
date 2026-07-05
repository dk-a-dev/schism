package ai.schism.split.core.theme

/** User-selectable UI theme. [SYSTEM] follows the device setting. */
enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark");

    companion object {
        fun from(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}
