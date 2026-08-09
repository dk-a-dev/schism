package ai.schism.split.ocr

sealed interface OcrAvailability {
    data class ConsentRequired(
        val modelBytes: Long,
        val featureBytes: Long?,
    ) : OcrAvailability

    data object Queued : OcrAvailability
    data object WaitingForWifi : OcrAvailability
    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val stage: String,
    ) : OcrAvailability

    data object Ready : OcrAvailability
    data class Failed(val reason: OcrFailure, val canRetry: Boolean) : OcrAvailability
}

enum class OcrFailure {
    Network,
    NoSpace,
    Integrity,
    IncompatibleApp,
    Unknown,
}

sealed interface OcrDownloadState {
    data object Idle : OcrDownloadState
    data object Queued : OcrDownloadState
    data object WaitingForWifi : OcrDownloadState
    data class Running(val downloadedBytes: Long, val totalBytes: Long, val stage: String) : OcrDownloadState
    data object Complete : OcrDownloadState
    data class Failed(val reason: OcrFailure, val canRetry: Boolean) : OcrDownloadState
}

