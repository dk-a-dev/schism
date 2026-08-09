package ai.schism.split.core.billing

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The only file in the app that touches Play Billing. It obtains purchase tokens and hands them
 * straight to [EntitlementRepository] for **server-side** verification — it never flips an
 * entitlement itself, and it never logs or stores a purchase token.
 *
 * Nothing here runs until the backend reports `purchasesEnabled`, so a build with the SDK linked in
 * still shows no purchase UI until the server says so.
 */
@Singleton
class PlayBilling @Inject constructor(
    @ApplicationContext private val context: Context,
    private val entitlements: EntitlementRepository,
    private val scope: CoroutineScope,
) : PlusBilling {

    private val _plans = MutableStateFlow<List<PlusPlan>>(emptyList())
    override val plans: StateFlow<List<PlusPlan>> = _plans.asStateFlow()

    private val _phase = MutableStateFlow(PurchasePhase.Idle)
    override val phase: StateFlow<PurchasePhase> = _phase.asStateFlow()

    private val connectMutex = Mutex()
    private var details: ProductDetails? = null

    private val client: BillingClient by lazy {
        BillingClient.newBuilder(context)
            .setListener { result, purchases -> onPurchasesUpdated(result, purchases.orEmpty()) }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
    }

    override suspend fun refresh() {
        if (!entitlements.config.value.purchasesEnabled) {
            _plans.value = emptyList()
            return
        }
        if (!connect()) return
        loadPlans()
        // Play may already own a subscription for this account (reinstall, account switch) — the
        // backend decides whether it still entitles anything.
        submitOwnedPurchases()
    }

    override suspend fun purchase(activity: Activity, plan: PlusPlan) {
        val product = details
        if (product == null || !connect()) {
            _phase.value = PurchasePhase.Failed
            return
        }
        _phase.value = PurchasePhase.Purchasing
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product)
                        .setOfferToken(plan.offerToken)
                        .build(),
                ),
            )
            .build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _phase.value = PurchasePhase.Failed
        }
        // Success continues asynchronously in [onPurchasesUpdated].
    }

    override suspend fun restore() {
        if (!connect()) {
            _phase.value = PurchasePhase.Failed
            return
        }
        _phase.value = PurchasePhase.Verifying
        val verified = submitOwnedPurchases()
        _phase.value = if (verified) PurchasePhase.Idle else PurchasePhase.Failed
    }

    override fun manageSubscriptionIntent(): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse(
            "https://play.google.com/store/account/subscriptions" +
                "?sku=$PLUS_PRODUCT_ID&package=${context.packageName}",
        ),
    )

    // ── Play plumbing ───────────────────────────────────────────────────────

    private suspend fun connect(): Boolean = connectMutex.withLock {
        if (client.isReady) return true
        val result = suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (cont.isActive) cont.resume(billingResult.responseCode)
                }

                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
                }
            })
        }
        result == BillingClient.BillingResponseCode.OK
    }

    private suspend fun loadPlans() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PLUS_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build(),
                ),
            )
            .build()
        val product = suspendCancellableCoroutine { cont ->
            client.queryProductDetailsAsync(params) { result, queryResult ->
                val found = if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryResult.productDetailsList.firstOrNull { it.productId == PLUS_PRODUCT_ID }
                } else {
                    null
                }
                if (cont.isActive) cont.resume(found)
            }
        }
        details = product
        _plans.value = product?.subscriptionOfferDetails.orEmpty()
            // Base plans only; a promotional offer would misreport the ongoing renewal price.
            .filter { it.offerId == null && it.basePlanId in BASE_PLANS }
            .distinctBy { it.basePlanId }
            .mapNotNull { offer ->
                val phase = offer.pricingPhases.pricingPhaseList.lastOrNull() ?: return@mapNotNull null
                PlusPlan(
                    basePlanId = offer.basePlanId,
                    formattedPrice = phase.formattedPrice,
                    billingPeriod = phase.billingPeriod,
                    offerToken = offer.offerToken,
                )
            }
            .sortedBy { BASE_PLANS.indexOf(it.basePlanId) }
    }

    /** Sends every purchase token Play reports to the backend. Returns true if any grants Plus. */
    private suspend fun submitOwnedPurchases(): Boolean {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val owned = suspendCancellableCoroutine { cont ->
            client.queryPurchasesAsync(params) { result, purchases ->
                val list = if (result.responseCode == BillingClient.BillingResponseCode.OK) purchases else emptyList()
                if (cont.isActive) cont.resume(list)
            }
        }
        val tokens = owned
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            .map { it.purchaseToken }
        if (tokens.isEmpty()) return false
        return entitlements.restore(tokens).getOrNull()?.isPlus == true
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> scope.launch { verify(purchases) }
            BillingClient.BillingResponseCode.USER_CANCELED -> _phase.value = PurchasePhase.Cancelled
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED ->
                scope.launch { _phase.value = if (submitOwnedPurchases()) PurchasePhase.Idle else PurchasePhase.Failed }
            else -> _phase.value = PurchasePhase.Failed
        }
    }

    private suspend fun verify(purchases: List<Purchase>) {
        if (purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING }) {
            _phase.value = PurchasePhase.Pending
        }
        val token = purchases
            .firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            ?.purchaseToken ?: return
        _phase.value = PurchasePhase.Verifying
        // Duplicate callbacks are harmless: the backend keys purchases by token, so re-verifying the
        // same one is idempotent. Acknowledgement is the backend's job, not ours.
        val state = entitlements.verifyPurchase(PLUS_PRODUCT_ID, null, token).getOrNull()
        _phase.value = if (state?.isPlus == true) PurchasePhase.Idle else PurchasePhase.Failed
    }

    private companion object {
        val BASE_PLANS = listOf(PLUS_BASE_PLAN_MONTHLY, PLUS_BASE_PLAN_ANNUAL)
    }
}
