package ai.schism.split.core.money

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyTest {
    @Test
    fun formatsMinorUnitsWithSymbolAndTwoDecimals() {
        assertEquals("₹42.00", formatMinor(4200, "₹"))
        assertEquals("$0.00", formatMinor(0, "$"))
        assertEquals("$0.05", formatMinor(5, "$"))
    }

    @Test
    fun formatsNegativesAndThousandsGrouping() {
        assertEquals("-₹1.50", formatMinor(-150, "₹"))
        assertEquals("$1,234,567.89", formatMinor(123456789, "$"))
        assertEquals("₹1,000.00", formatMinor(100000, "₹"))
    }
}
