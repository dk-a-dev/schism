package ai.schism.split.core.billing

import ai.schism.split.core.settings.SettingsRepository
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.entitlementStore by preferencesDataStore("entitlement")

/**
 * The single source of truth for "is this account Plus, and how much free Live Split allowance is
 * left" — always the backend's answer, never the client's. A Play purchase only ever *asks* the
 * backend to verify (see [verifyPurchase]); it can't grant entitlement on its own.
 *
 * The DataStore cache holds display-safe fields only (active flag, product id, expiry, allowance
 * counters) so the UI is honest while offline. Purchase tokens are never persisted or logged.
 */
@Singleton
class EntitlementRepository @Inject constructor(
    private val api: BillingApi,
    private val settings: SettingsRepository,
    @ApplicationContext context: Context,
    scope: CoroutineScope,
) {
    private val ds = context.entitlementStore

    private val _state = MutableStateFlow<EntitlementState>(EntitlementState.Unknown)
    val state: StateFlow<EntitlementState> = _state.asStateFlow()

    private val _config = MutableStateFlow(MonetizationConfig())
    val config: StateFlow<MonetizationConfig> = _config.asStateFlow()

    init {
        scope.launch {
            restoreCache()
            // The token arrives after launch, so refresh when it appears rather than once at start.
            // Losing it (sign-out, deleted account, expired session) drops straight back to Unknown,
            // which switches ads and purchase UI off until the backend speaks again.
            settings.authToken
                .map { it.isNotBlank() }
                .distinctUntilChanged()
                .collect { signedIn ->
                    if (signedIn) {
                        refresh()
                    } else {
                        _state.value = EntitlementState.Unknown
                        _config.value = MonetizationConfig()
                        cache(EntitlementState.Unknown)
                    }
                }
        }
    }

    /** Re-asks the backend for the feature switches and this account's entitlement. Safe to spam. */
    suspend fun refresh() {
        if (settings.authToken.first().isBlank()) return
        runCatching { api.monetizationConfig() }.onSuccess {
            _config.value = MonetizationConfig(it.plusEnabled, it.adsEnabled, it.purchasesEnabled, it.receiptCloudEnabled)
        }
        runCatching { api.entitlement() }.onSuccess { apply(it) }
    }

    /**
     * Hands a Play purchase token to the backend for server-side verification. The returned state is
     * whatever the *backend* decided — a purchase the backend rejects leaves the account free.
     */
    suspend fun verifyPurchase(productId: String, basePlanId: String?, purchaseToken: String): Result<EntitlementState> =
        runCatching { api.verifyPurchase(VerifyPurchaseRequest(productId, basePlanId, purchaseToken)) }
            .map { apply(it) }

    /** Re-verifies purchases Play still knows about (reinstall, account switch, "Restore" tap). */
    suspend fun restore(purchaseTokens: List<String>): Result<EntitlementState> =
        runCatching { api.restorePurchases(RestorePurchasesRequest(purchaseTokens)) }
            .map { apply(it) }

    /**
     * Records the allowance the backend reported alongside a `402 PLUS_REQUIRED`, so the UI can show
     * "0 of 3 left" straight away instead of waiting for the next refresh.
     */
    suspend fun noteAllowance(allowance: LiveSplitAllowance) {
        if (_state.value.isPlus) return
        _state.value = EntitlementState.Free(allowance)
        cache(_state.value)
    }

    private suspend fun apply(dto: EntitlementDto): EntitlementState {
        val next = dto.toState()
        _state.value = next
        cache(next)
        return next
    }

    private suspend fun cache(state: EntitlementState) {
        ds.edit { prefs ->
            when (state) {
                is EntitlementState.Plus -> {
                    prefs[KEY_ACTIVE] = true
                    prefs[KEY_PRODUCT] = state.productId
                    prefs[KEY_EXPIRES] = state.expiresAt
                    prefs[KEY_AUTO_RENEW] = state.autoRenewing
                }
                is EntitlementState.Free -> {
                    prefs[KEY_ACTIVE] = false
                    prefs[KEY_USED] = state.allowance?.used ?: 0
                    prefs[KEY_LIMIT] = state.allowance?.limit ?: 0
                    prefs[KEY_RESETS] = state.allowance?.resetsAt.orEmpty()
                }
                EntitlementState.Unknown -> prefs.clear()
            }
        }
    }

    private suspend fun restoreCache() {
        val prefs = ds.data.first()
        val active = prefs[KEY_ACTIVE] ?: return
        _state.value = if (active) {
            EntitlementState.Plus(
                productId = prefs[KEY_PRODUCT].orEmpty(),
                expiresAt = prefs[KEY_EXPIRES].orEmpty(),
                autoRenewing = prefs[KEY_AUTO_RENEW] ?: false,
            )
        } else {
            val limit = prefs[KEY_LIMIT] ?: 0
            EntitlementState.Free(
                if (limit > 0) {
                    LiveSplitAllowance(prefs[KEY_USED] ?: 0, limit, prefs[KEY_RESETS].orEmpty())
                } else {
                    null
                },
            )
        }
    }

    private companion object {
        val KEY_ACTIVE = booleanPreferencesKey("plus_active")
        val KEY_PRODUCT = stringPreferencesKey("plus_product_id")
        val KEY_EXPIRES = stringPreferencesKey("plus_expires_at")
        val KEY_AUTO_RENEW = booleanPreferencesKey("plus_auto_renewing")
        val KEY_USED = intPreferencesKey("live_split_used")
        val KEY_LIMIT = intPreferencesKey("live_split_limit")
        val KEY_RESETS = stringPreferencesKey("live_split_resets_at")
    }
}
