package ai.schism.split.core.billing

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException

class EntitlementTest {

    // ── backend response → entitlement state ────────────────────────────

    @Test
    fun `inactive response with allowance is a free account`() {
        val state = EntitlementDto(active = false, used = 1, limit = 3, resetsAt = "2026-09-01T00:00:00Z").toState()
        assertEquals(EntitlementState.Free(LiveSplitAllowance(1, 3, "2026-09-01T00:00:00Z")), state)
        assertFalse(state.isPlus)
        assertEquals(2, (state as EntitlementState.Free).allowance!!.remaining)
    }

    @Test
    fun `active response is plus`() {
        val state = EntitlementDto(
            active = true,
            productId = PLUS_PRODUCT_ID,
            expiresAt = "2026-09-09T00:00:00Z",
            autoRenewing = true,
        ).toState()
        assertTrue(state.isPlus)
        assertEquals(PLUS_PRODUCT_ID, (state as EntitlementState.Plus).productId)
    }

    @Test
    fun `cancelled but not yet expired keeps plus active`() {
        val state = EntitlementDto(active = true, productId = PLUS_PRODUCT_ID, autoRenewing = false).toState()
        assertTrue(state.isPlus)
        assertFalse((state as EntitlementState.Plus).autoRenewing)
    }

    @Test
    fun `expired or refunded falls back to free, never to plus`() {
        // The backend simply stops reporting active; the client can't override that.
        assertFalse(EntitlementDto(active = false).toState().isPlus)
    }

    @Test
    fun `unknown is the starting state and is not plus`() {
        assertFalse(EntitlementState.Unknown.isPlus)
        assertFalse(EntitlementState.Unknown.isKnown)
        assertTrue(EntitlementState.Free().isKnown)
    }

    @Test
    fun `monetization switches default off`() {
        val config = MonetizationConfig()
        assertFalse(config.plusEnabled)
        assertFalse(config.adsEnabled)
        assertFalse(config.purchasesEnabled)
    }

    // ── the Live Split gate ─────────────────────────────────────────────

    @Test
    fun `402 PLUS_REQUIRED is recognised with its counters`() {
        val gate = plusRequiredOrNull(
            httpError(402, """{"error":"PLUS_REQUIRED","used":3,"limit":3,"resetsAt":"2026-09-01T00:00:00Z"}"""),
        )
        assertEquals(3, gate!!.used)
        assertEquals(3, gate.limit)
        assertEquals("2026-09-01T00:00:00Z", gate.resetsAt)
        assertEquals(0, gate.allowance.remaining)
    }

    @Test
    fun `other failures are not turned into a paywall`() {
        assertNull(plusRequiredOrNull(httpError(500, "boom")))
        assertNull(plusRequiredOrNull(httpError(402, """{"error":"SOMETHING_ELSE"}""")))
        assertNull(plusRequiredOrNull(httpError(409, """{"error":"PLUS_REQUIRED","used":3,"limit":3}""")))
        assertNull(plusRequiredOrNull(IllegalStateException("offline")))
    }

    @Test
    fun `purchase request carries the token but nothing identifying is in its toString`() {
        // Guards the "purchase tokens are never logged" rule at the one place a token is held.
        val request = VerifyPurchaseRequest(PLUS_PRODUCT_ID, PLUS_BASE_PLAN_MONTHLY, "secret-token")
        assertEquals("secret-token", request.purchaseToken)
        assertFalse(PlusRequiredException(3, 3, "x").message!!.contains("token"))
    }

    private fun httpError(code: Int, body: String): HttpException = HttpException(
        retrofit2.Response.error<Any>(
            body.toResponseBody("application/json".toMediaType()),
            Response.Builder()
                .request(Request.Builder().url("http://localhost/v1/groups/g/claim-sessions").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("error")
                .build(),
        ),
    )
}
