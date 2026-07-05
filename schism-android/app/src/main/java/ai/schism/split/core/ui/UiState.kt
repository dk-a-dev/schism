package ai.schism.split.core.ui

/**
 * Unidirectional UI state for a screen backed by a single data source.
 * Loading → first load; Empty → loaded but nothing to show; Error → load failed;
 * Data → content available (may still be refreshing).
 */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data object Empty : UiState<Nothing>
    data class Error(val message: String) : UiState<Nothing>
    data class Data<T>(val value: T) : UiState<T>
}
