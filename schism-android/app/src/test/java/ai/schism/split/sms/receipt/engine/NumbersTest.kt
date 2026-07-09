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
}
