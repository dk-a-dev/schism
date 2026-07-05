package ai.schism.split.sms.data

import com.pennywiseai.parser.core.bank.BankParserFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Pure parse -> entity mapping: no Android/Room needed. */
class TransactionMappingTest {

    @Test
    fun knownBankSmsMapsToUnassignedEntityWithMinorUnits() {
        val parsed = BankParserFactory.parse(HDFC_SWIGGY_SMS, "HDFCBK", 1_720_000_000_000L)
        assertNotNull("fixture must be parseable", parsed)

        val entity = parsed!!.toEntity()

        // 450.00 -> 45000 minor units
        assertEquals(45_000L, entity.amountMinor)
        assertEquals("SWIGGY", entity.merchant)
        assertEquals("INR", entity.currency)
        assertEquals("HDFC Bank", entity.bankName)
        assertEquals(TransactionStatus.UNASSIGNED, entity.status)
        // id is the parser's stable dedup hash
        assertEquals(parsed.generateTransactionId(), entity.id)
    }

    @Test
    fun missingMerchantFallsBackToUnknown() {
        // A hand-built parsed txn with no merchant maps to "Unknown".
        val parsed = com.pennywiseai.parser.core.ParsedTransaction(
            amount = java.math.BigDecimal("12.50"),
            type = com.pennywiseai.parser.core.TransactionType.EXPENSE,
            merchant = null,
            reference = null,
            accountLast4 = null,
            balance = null,
            smsBody = "debit 12.50",
            sender = "HDFCBK",
            timestamp = 1L,
            bankName = "HDFC Bank",
        )
        assertEquals("Unknown", parsed.toEntity().merchant)
        assertEquals(1_250L, parsed.toEntity().amountMinor)
    }

    companion object {
        const val HDFC_SWIGGY_SMS =
            "Sent Rs.450.00 From HDFC Bank A/C x1234 To SWIGGY On 05-07-26 Ref 501234567890 " +
                "Not You? Call 18002586161/SMS BLOCK UPI to 7308080808"
    }
}
