package ai.schism.split.core.billing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

@Serializable
data class MonetizationConfigDto(
    val plusEnabled: Boolean = false,
    val adsEnabled: Boolean = false,
    val purchasesEnabled: Boolean = false,
)

@Serializable
data class EntitlementDto(
    val active: Boolean = false,
    val productId: String? = null,
    val expiresAt: String? = null,
    val autoRenewing: Boolean = false,
    /** Live Split allowance for the current UTC month; absent for Plus. */
    val used: Int = 0,
    val limit: Int = 0,
    val resetsAt: String? = null,
)

/** Sent to `POST /v1/billing/verify`. The token is write-only: never cached, never logged. */
@Serializable
data class VerifyPurchaseRequest(
    val productId: String,
    val basePlanId: String? = null,
    val purchaseToken: String,
)

@Serializable
data class RestorePurchasesRequest(val purchaseTokens: List<String>)

@Serializable
private data class PlusRequiredDto(
    @SerialName("error") val error: String = "",
    val used: Int = 0,
    val limit: Int = 0,
    val resetsAt: String = "",
)

/**
 * Monetization endpoints. Kept as its own Retrofit interface (rather than folded into `ApiService`)
 * so the billing boundary owns its own DTOs; it rides the same authenticated OkHttp client.
 */
interface BillingApi {

    @GET("v1/monetization/config")
    suspend fun monetizationConfig(): MonetizationConfigDto

    @GET("v1/entitlement")
    suspend fun entitlement(): EntitlementDto

    @POST("v1/billing/verify")
    suspend fun verifyPurchase(@Body body: VerifyPurchaseRequest): EntitlementDto

    @POST("v1/billing/restore")
    suspend fun restorePurchases(@Body body: RestorePurchasesRequest): EntitlementDto
}

/** Maps a backend DTO to the client-facing state. Only `active` grants Plus. */
fun EntitlementDto.toState(): EntitlementState = when {
    active -> EntitlementState.Plus(
        productId = productId.orEmpty(),
        expiresAt = expiresAt.orEmpty(),
        autoRenewing = autoRenewing,
    )
    limit > 0 -> EntitlementState.Free(LiveSplitAllowance(used, limit, resetsAt.orEmpty()))
    else -> EntitlementState.Free()
}

private val plusRequiredJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

/**
 * Recognises the create-Live-Split gate: HTTP 402 with `{"error":"PLUS_REQUIRED",...}`. Returns null
 * for anything else so ordinary failures stay ordinary failures instead of turning into a paywall.
 */
fun plusRequiredOrNull(error: Throwable): PlusRequiredException? {
    val http = error as? HttpException ?: return null
    if (http.code() != 402) return null
    val body = runCatching { http.response()?.errorBody()?.string() }.getOrNull().orEmpty()
    val dto = runCatching { plusRequiredJson.decodeFromString<PlusRequiredDto>(body) }.getOrNull()
        ?: return null
    if (dto.error != "PLUS_REQUIRED") return null
    return PlusRequiredException(dto.used, dto.limit, dto.resetsAt)
}
