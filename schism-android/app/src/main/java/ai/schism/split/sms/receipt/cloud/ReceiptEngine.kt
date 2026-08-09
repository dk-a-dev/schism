package ai.schism.split.sms.receipt.cloud

/**
 * Which engine reads a receipt photo. [ON_DEVICE] is the default and the app's shipped promise: the
 * photo never leaves the phone. The other two send the image off the device and are therefore gated
 * behind an explicit, per-engine consent (see [ai.schism.split.core.settings.SettingsRepository.receiptCloudConsents]).
 */
enum class ReceiptEngine {
    /** PP-OCR + the deterministic bill engine, entirely on this device. Nothing is uploaded. */
    ON_DEVICE,

    /** Posts the photo to Schism's own backend. Only offered when the backend switches it on. */
    SCHISM_CLOUD,

    /** Posts the photo straight from this device to the user's own Gemini/Groq account. */
    OWN_KEY,
    ;

    /** True when choosing this engine means a receipt photo leaves the device. */
    val leavesDevice: Boolean get() = this != ON_DEVICE
}

/** The third-party vision providers a user can point their own API key at. */
enum class ReceiptProvider { GEMINI, GROQ }

/**
 * Why a cloud read didn't produce a draft. Every one of these falls back to [ReceiptEngine.ON_DEVICE]
 * with the message below — the user is mid-bill, so a dead end is never an acceptable outcome.
 */
sealed interface CloudReceiptFailure {
    data object Offline : CloudReceiptFailure
    data object NoKey : CloudReceiptFailure
    data object InvalidKey : CloudReceiptFailure
    data object Timeout : CloudReceiptFailure
    data object Unreadable : CloudReceiptFailure
    data object ServerError : CloudReceiptFailure

    /** HTTP 429. [retryAfterSeconds] comes from the Retry-After header (0 when absent/unparseable). */
    data class RateLimited(val retryAfterSeconds: Long) : CloudReceiptFailure
}

/** "in 45 minutes" / "in 2 hours" — how a [CloudReceiptFailure.RateLimited] wait is spoken to a user. */
fun formatRetryAfter(seconds: Long): String = when {
    seconds <= 0 -> "shortly"
    seconds < 60 -> "in less than a minute"
    seconds < 3600 -> "in ${(seconds + 59) / 60} min"
    else -> "in ${(seconds + 3599) / 3600} h"
}
