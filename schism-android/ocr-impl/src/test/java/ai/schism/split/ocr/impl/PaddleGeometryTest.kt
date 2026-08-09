package ai.schism.split.ocr.impl

import ai.schism.split.ocr.api.OcrPoint
import android.graphics.PointF
import com.paddle.ocr.model.OCRBox
import com.paddle.ocr.model.OCRResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PaddleGeometryTest {
    @Test
    fun `maps Paddle text confidence and four finite points without loss`() {
        val result = OCRResult(
            box = OCRBox(
                listOf(
                    PointF(10.5f, 11.5f),
                    PointF(90.5f, 12.5f),
                    PointF(89.5f, 31.5f),
                    PointF(9.5f, 30.5f),
                ),
            ),
            text = "Masala dosa",
            confidence = 0.875f,
        )

        val line = result.toOcrLineOrNull()

        requireNotNull(line)
        assertEquals("Masala dosa", line.text)
        assertEquals(0.875f, line.confidence)
        assertEquals(
            listOf(
                OcrPoint(10.5f, 11.5f),
                OcrPoint(90.5f, 12.5f),
                OcrPoint(89.5f, 31.5f),
                OcrPoint(9.5f, 30.5f),
            ),
            line.points,
        )
    }

    @Test
    fun `rejects non-finite Paddle geometry`() {
        val result = OCRResult(
            box = OCRBox(
                listOf(
                    PointF(0f, 0f),
                    PointF(Float.NaN, 0f),
                    PointF(1f, 1f),
                    PointF(0f, 1f),
                ),
            ),
            text = "unsafe",
            confidence = 0.5f,
        )

        assertNull(result.toOcrLineOrNull())
    }
}
