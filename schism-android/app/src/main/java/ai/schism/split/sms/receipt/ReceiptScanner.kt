package ai.schism.split.sms.receipt

import ai.schism.split.ocr.BundledOcrModels
import ai.schism.split.ocr.impl.PaddleOcrProvider
import ai.schism.split.sms.receipt.engine.Row
import android.content.Context
import android.net.Uri
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fully on-device receipt OCR using the tiny PP-OCRv6 detector and recognizer through ONNX
 * Runtime. Images and recognized text never leave the device.
 *
 * Paddle returns a quadrilateral for every recognized text line. Those boxes are retained and
 * reconstructed into visual rows so item names and prices keep their left-to-right relationship.
 */
@Singleton
class ReceiptScanner @Inject constructor() {
    private val provider = PaddleOcrProvider()
    private val bundledModels = BundledOcrModels()

    suspend fun recognizeCells(context: Context, uri: Uri): List<Row> {
        val models = bundledModels.materialize(context.applicationContext)
        return ocrLinesToRows(provider.recognize(context.applicationContext, uri, models).lines)
    }

    suspend fun recognizeLines(context: Context, uri: Uri): List<String> =
        recognizeCells(context, uri).map { it.text }
}
