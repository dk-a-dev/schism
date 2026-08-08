package ai.schism.split.sms.receipt

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiptScannerInstrumentedTest {
    @Test
    fun recognizesReferenceImageThroughAppScanner() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val appContext = instrumentation.targetContext
        val resourceId = testContext.resources.getIdentifier(
            "paddle_ocr_reference",
            "raw",
            testContext.packageName,
        )
        check(resourceId != 0) { "Missing PaddleOCR reference image" }

        val imageFile = File(appContext.cacheDir, "paddle_ocr_reference.jpg")
        testContext.resources.openRawResource(resourceId).use { source ->
            imageFile.outputStream().use { target -> source.copyTo(target) }
        }

        val rows = ReceiptScanner().recognizeCells(appContext, Uri.fromFile(imageFile))

        assertTrue("PaddleOCR should recognize text", rows.isNotEmpty())
        assertTrue("Recognized rows should contain text", rows.all { it.text.isNotBlank() })
    }
}
