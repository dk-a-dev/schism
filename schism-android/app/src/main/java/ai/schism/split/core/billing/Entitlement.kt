package ai.schism.split.core.billing

/**
 * Monetization switches as reported by the backend. **Every switch defaults off** — until an
 * authenticated `GET /v1/monetization/config` says otherwise there is no purchase UI and no ad.
 */
data class MonetizationConfig(
    val plusEnabled: Boolean = false,
    val adsEnabled: Boolean = false,
    val purchasesEnabled: Boolean = false,
    /** Backend switch for the Schism-cloud receipt engine (Settings › Receipt reading). */
    val receiptCloudEnabled: Boolean = false,
)

/** Free hosted Live Splits used in the current UTC calendar month. Joining is always free. */
data class LiveSplitAllowance(val used: Int, val limit: Int, val resetsAt: String) {
    val remaining: Int get() = (limit - used).coerceAtLeast(0)
}

/**
 * What this device is entitled to, as decided by the backend. A client claim never produces
 * [Plus] — only a verified `GET /v1/entitlement` or `POST /v1/billing/verify` response does.
 */
sealed interface EntitlementState {
    /** Not asked yet (fresh launch, offline with no cache). Treated as "not Plus" but also "no ads". */
    data object Unknown : EntitlementState

    /** Free account. [allowance] is null until the backend has reported this month's usage. */
    data class Free(val allowance: LiveSplitAllowance? = null) : EntitlementState

    /** Verified Plus. Stays active through the paid period even when auto-renew is off. */
    data class Plus(
        val productId: String,
        val expiresAt: String,
        val autoRenewing: Boolean,
    ) : EntitlementState
}

val EntitlementState.isPlus: Boolean get() = this is EntitlementState.Plus

/** True once the backend has actually told us which side of the fence we're on. */
val EntitlementState.isKnown: Boolean get() = this != EntitlementState.Unknown

/**
 * The backend's `402 {"error":"PLUS_REQUIRED", ...}` from create-Live-Split, and *only* from there.
 * Every other feature — OCR, SMS import, manual entry, invitations, balances, settle-up, and every
 * operation on an existing session — is never gated.
 */
class PlusRequiredException(
    val used: Int,
    val limit: Int,
    val resetsAt: String,
) : Exception("Free Live Splits used ($used/$limit)") {
    val allowance: LiveSplitAllowance get() = LiveSplitAllowance(used, limit, resetsAt)
}
