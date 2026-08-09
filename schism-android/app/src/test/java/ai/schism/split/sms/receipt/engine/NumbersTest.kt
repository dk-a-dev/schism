package ai.schism.split.sms.receipt.engine

import org.junit.Assert.*
import org.junit.Test

class NumbersTest {
    @Test fun largeWholeRupeeTotalParses() {
        assertEquals(12345600L, parseMinor("123456"))
        assertNull(parseMinor("9555713188"))
    }

    /**
     * A dot followed by EXACTLY 3 digits (repeated) is thousands grouping, not a fraction — that is
     * how Indonesia, Germany, Brazil, Spain, Italy and Turkey print an amount, and commas are
     * already stripped as grouping unconditionally. This token previously parsed as nothing at all,
     * which made every amount on such a bill invisible to the engine.
     *
     * Any other over-long fraction is still rejected: it is neither a money fraction nor a grouping.
     */
    @Test fun dotGroupedThousandsParseAndOtherLongFractionsReject() {
        assertEquals(1234500L, parseMinor("12.345"))
        assertEquals(159160000L, parseMinor("1.591.600"))
        assertNull(parseMinor("12.3456"))
        assertNull(parseMinor("12.34567"))
        // A genuine 1-2 digit fraction is untouched.
        assertEquals(1234L, parseMinor("12.34"))
        assertEquals(1200L, parseMinor("12.0"))
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

    @Test fun labelWithDigitsIsNotMoney() {
        // Stripping is only meant to drop currency/grouping noise. A label that happens to carry
        // digits must NOT survive as its leftover digits, or a merchant line ends in a "money" cell
        // (and is skipped as a would-be item) and a tax id becomes an amount.
        assertNull(parseMinor("SYNTHETIC CURRENCY 1"))
        assertNull(parseMinor("GSTIN:29AAFCP1234M1ZK"))
        assertNull(parseMinor("Bill No.: 3241"))
        assertFalse(isMoneyToken("Total Qty: 13"))
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
