package ai.schism.split.sms.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetectCurrencyTest {

    @Test
    fun `symbols and whole-word codes are detected`() {
        assertEquals("₹", detectCurrency(listOf("Total ₹ 540.00")))
        assertEquals("₹", detectCurrency(listOf("Total Rs. 540.00")))
        assertEquals("₹", detectCurrency(listOf("Amount in INR 540.00")))
        assertEquals("$", detectCurrency(listOf("Total $ 12.00")))
        assertEquals("€", detectCurrency(listOf("Total EUR 12.00")))
    }

    @Test
    fun `a food word containing rs does not make a dollar bill rupees`() {
        // "Burgers" contains "rs"; as a bare substring match this reported ₹ on a $ receipt.
        assertEquals("$", detectCurrency(listOf("Cheeseburgers 2", "Crackers 1", "Total $ 24.00")))
    }

    @Test
    fun `no hint means no currency`() {
        assertNull(detectCurrency(listOf("Cheeseburgers 2", "Total 24.00")))
    }
}
