package ai.schism.split.sms.receipt.engine

import org.junit.Assert.*
import org.junit.Test

class NumbersTest {
    @Test fun largeWholeRupeeTotalParses() {
        assertEquals(12345600L, parseMinor("123456"))
        assertNull(parseMinor("9555713188"))
    }

    @Test fun plainNumberRejectsMoreThanTwoDecimalDigits() {
        assertNull(parseMinor("12.345"))
        assertEquals(1234L, parseMinor("12.34"))
    }

    @Test fun percentTokenIsNotMoney() {
        // A tax/discount RATE like "2.5%" or "18%" must never parse as a money amount, so a
        // percentage cell can't be mistaken for an amount when detecting money columns/regions.
        assertNull(parseMinor("2.5%"))
        assertNull(parseMinor("18%"))
        assertFalse(isMoneyToken("2.5%"))
        assertFalse(isMoneyToken("18%"))
        // The paired amount on the same GST line is still a normal money token.
        assertEquals(2360L, parseMinor("23.60"))
        assertTrue(isMoneyToken("23.60"))
    }

    @Test fun rupeeSlashDashAndMidDotStyles() {
        // "149/-" (Indian "₹149 flat" notation) parses as ₹149, not null.
        assertEquals(14900L, parseMinor("149/-"))
        assertEquals(14900L, parseMinor("₹149"))
        // Mid-dot decimal separator normalizes to a real decimal point.
        assertEquals(14900L, parseMinor("149·00"))
        // A genuine leading-minus negative is preserved.
        assertEquals(-20L, parseMinor("-0.20"))
        // Comma thousands still parse.
        assertEquals(123400L, parseMinor("1,234.00"))
    }
}
