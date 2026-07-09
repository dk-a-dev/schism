package ai.schism.split.claim

import ai.schism.split.core.net.ClaimDto
import ai.schism.split.core.net.ClaimItemDto
import ai.schism.split.sms.itemized.claim.previewOwes
import org.junit.Assert.assertEquals
import org.junit.Test

class ClaimMathTest {

    @Test
    fun weightedWithTax() {
        val items = listOf(ClaimItemDto(0, "Dish", 3, 30000))
        val claims = listOf(ClaimDto(0, "dev", 2.0), ClaimDto(0, "ru", 1.0))
        val owes = previewOwes(items, claims, taxMinor = 3000, 0, 0, 0)
        assertEquals(22000L, owes["dev"])
        assertEquals(11000L, owes["ru"])
    }

    @Test
    fun remainderPenniesGoToLastClaimantAndSplitIsExact() {
        // 100 minor split 3 ways (weight 1 each) = 33/33/34; last claimant absorbs rounding.
        val items = listOf(ClaimItemDto(0, "Snack", 1, 100))
        val claims = listOf(
            ClaimDto(0, "a", 1.0),
            ClaimDto(0, "b", 1.0),
            ClaimDto(0, "c", 1.0),
        )
        val owes = previewOwes(items, claims)
        assertEquals(33L, owes["a"])
        assertEquals(33L, owes["b"])
        assertEquals(34L, owes["c"])
        assertEquals(100L, owes.values.sum())
    }

    @Test
    fun halfWeightIsSupported() {
        val items = listOf(ClaimItemDto(0, "Dish", 1, 300))
        val claims = listOf(ClaimDto(0, "a", 0.5), ClaimDto(0, "b", 0.5))
        val owes = previewOwes(items, claims)
        assertEquals(150L, owes["a"])
        assertEquals(150L, owes["b"])
    }

    @Test
    fun itemsWithNoClaimsContributeNothing() {
        val items = listOf(
            ClaimItemDto(0, "Claimed", 1, 1000),
            ClaimItemDto(1, "Unclaimed", 1, 500),
        )
        val claims = listOf(ClaimDto(0, "a", 1.0))
        val owes = previewOwes(items, claims)
        assertEquals(1000L, owes["a"])
        assertEquals(1, owes.size)
    }

    @Test
    fun chargePotCombinesFeesDiscountAndRoundoff() {
        // net charge pot = tax + fees - discount + roundoff = 1000 + 500 - 300 + 1 = 1201
        val items = listOf(ClaimItemDto(0, "Dish", 1, 10000))
        val claims = listOf(ClaimDto(0, "a", 1.0))
        val owes = previewOwes(items, claims, taxMinor = 1000, feesMinor = 500, discountMinor = 300, roundoffMinor = 1)
        assertEquals(10000L + 1201L, owes["a"])
    }

    @Test
    fun multipleItemsAndClaimantsSplitChargePotProportionally() {
        val items = listOf(
            ClaimItemDto(0, "Dish A", 1, 20000),
            ClaimItemDto(1, "Dish B", 1, 10000),
        )
        val claims = listOf(
            ClaimDto(0, "dev", 1.0),
            ClaimDto(1, "ru", 1.0),
        )
        // dev's subtotal 20000, ru's subtotal 10000; tax 3000 split 2:1 -> dev 2000, ru 1000.
        val owes = previewOwes(items, claims, taxMinor = 3000)
        assertEquals(22000L, owes["dev"])
        assertEquals(11000L, owes["ru"])
    }
}
