package ai.schism.split.sms.receipt

import ai.schism.split.ocr.OcrAvailability
import ai.schism.split.ocr.OcrCoordinator
import ai.schism.split.ocr.impl.PaddleOcrProvider
import ai.schism.split.sms.receipt.engine.Row
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Fully on-device receipt OCR using the tiny PP-OCRv6 detector and recognizer through ONNX
 * Runtime. Images and recognized text never leave the device.
 *
 * Paddle returns a quadrilateral for every recognized text line. Those boxes are retained and
 * reconstructed into visual rows so item names and prices keep their left-to-right relationship.
 */
class ReceiptScanner(
    private val coordinator: OcrCoordinator? = null,
) {
    private val provider = PaddleOcrProvider()

    suspend fun recognizeCells(context: Context, uri: Uri): List<Row> {
        val models = coordinator?.currentModels() ?: throw OcrNotReadyException()
        return ocrLinesToRows(provider.recognize(context.applicationContext, uri, models).lines)
    }

    suspend fun recognizeLines(context: Context, uri: Uri): List<String> =
        recognizeCells(context, uri).map { it.text }

    fun observeAvailability(): Flow<OcrAvailability> =
        coordinator?.observe() ?: flowOf(OcrAvailability.Ready)

    fun prepare(allowCellular: Boolean): Flow<OcrAvailability> =
        coordinator?.prepare(allowCellular) ?: flowOf(OcrAvailability.Ready)

    fun cancelDownload() {
        coordinator?.cancelDownload()
    }
}

class OcrNotReadyException : IllegalStateException("Receipt scanning models are not ready")
