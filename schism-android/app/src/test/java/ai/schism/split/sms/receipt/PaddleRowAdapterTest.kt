package ai.schism.split.sms.receipt

import ai.schism.split.ocr.api.OcrLine
import ai.schism.split.ocr.api.OcrPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class PaddleRowAdapterTest {
    @Test
    fun `groups Paddle detections into visual receipt rows`() {
        val detections = listOf(
            line("Dosa", 12f, 144f, 10f, 34f),
            line("120.00", 310f, 386f, 12f, 36f),
            line("Coffee", 12f, 151f, 62f, 88f),
            line("80.00", 318f, 386f, 64f, 90f),
        )

        val rows = ocrLinesToRows(detections)

        assertEquals(listOf("Dosa 120.00", "Coffee 80.00"), rows.map { it.text })
        assertEquals(listOf(12, 310), rows.first().cells.map { it.xLeft })
    }

    @Test
    fun `ignores blank and malformed recognitions`() {
        assertEquals(emptyList<Any>(), ocrLinesToRows(emptyList()))
        assertEquals(
            emptyList<Any>(),
            ocrLinesToRows(
                listOf(
                    line("  ", 0f, 10f, 0f, 10f),
                    OcrLine("three points", 0.9f, listOf(OcrPoint(0f, 0f), OcrPoint(1f, 0f), OcrPoint(1f, 1f))),
                    OcrLine(
                        "non-finite",
                        0.9f,
                        listOf(OcrPoint(0f, 0f), OcrPoint(Float.POSITIVE_INFINITY, 0f), OcrPoint(1f, 1f), OcrPoint(0f, 1f)),
                    ),
                ),
            ),
        )
    }

    private fun line(text: String, left: Float, right: Float, top: Float, bottom: Float) = OcrLine(
        text = text,
        confidence = 0.9f,
        points = listOf(
            OcrPoint(left, top),
            OcrPoint(right, top),
            OcrPoint(right, bottom),
            OcrPoint(left, bottom),
        ),
    )
}
