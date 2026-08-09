package ai.schism.split.core.ads

/** An account must have existed this long before it is ever shown an ad. */
const val MIN_ACCOUNT_AGE_DAYS = 7

/** …and the person must have actually used Schism this many times. */
const val MIN_MEANINGFUL_ACTIONS = 3

/**
 * Everything that decides whether the single inline banner may appear. Deliberately a plain data
 * class of already-resolved facts so the rule is one pure function and fully testable.
 */
data class AdContext(
    /** Backend `ADS_ENABLED`. Defaults off until an authenticated config says otherwise. */
    val adsEnabledByBackend: Boolean = false,
    /** False until the backend has told us whether this account is Plus. Unknown ⇒ no ad. */
    val entitlementKnown: Boolean = false,
    val isPlus: Boolean = false,
    /** UMP `canRequestAds` — false when consent is unavailable, pending, or denied. */
    val consentGranted: Boolean = false,
    val accountAgeDays: Int = 0,
    val meaningfulActions: Int = 0,
    /**
     * True only on the Spending/Insights summary, after the insight cards. Onboarding, demos,
     * transactions, receipts, groups, balances, settlement, Live Split and purchase flows are all
     * false — there is exactly one ad placement in the app.
     */
    val onAdSurface: Boolean = false,
    /** False while the host is not resumed, so a backgrounded screen never requests an ad. */
    val foreground: Boolean = false,
)

/**
 * The one ad rule. Every condition must hold; anything unknown resolves to "no ad".
 */
fun AdContext.isEligible(): Boolean =
    adsEnabledByBackend &&
        entitlementKnown &&
        !isPlus &&
        consentGranted &&
        onAdSurface &&
        foreground &&
        accountAgeDays >= MIN_ACCOUNT_AGE_DAYS &&
        meaningfulActions >= MIN_MEANINGFUL_ACTIONS
