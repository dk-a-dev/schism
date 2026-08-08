package ai.schism.split.sms.receipt

import org.junit.Assert.assertEquals
import org.junit.Test

class PaddleRowAdapterTest {
    @Test
    fun `groups Paddle detections into visual receipt rows`() {
        val detections = listOf(
            DetectedLine("Dosa", left = 12, right = 144, top = 10, bottom = 34),
            DetectedLine("120.00", left = 310, right = 386, top = 12, bottom = 36),
            DetectedLine("Coffee", left = 12, right = 151, top = 62, bottom = 88),
            DetectedLine("80.00", left = 318, right = 386, top = 64, bottom = 90),
        )

        val rows = detectedLinesToRows(detections)

        assertEquals(listOf("Dosa 120.00", "Coffee 80.00"), rows.map { it.text })
        assertEquals(listOf(12, 310), rows.first().cells.map { it.xLeft })
    }

    @Test
    fun `ignores blank recognitions and handles empty results`() {
        assertEquals(emptyList<Any>(), detectedLinesToRows(emptyList()))
        assertEquals(
            emptyList<Any>(),
            detectedLinesToRows(listOf(DetectedLine("  ", 0, 10, 0, 10))),
        )
    }
}
