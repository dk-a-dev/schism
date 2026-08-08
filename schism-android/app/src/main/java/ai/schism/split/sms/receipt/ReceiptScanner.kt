package ai.schism.split.sms.receipt

import ai.schism.split.sms.receipt.engine.Row
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Fully on-device receipt OCR using the tiny PP-OCRv6 detector and recognizer through ONNX
 * Runtime. Images and recognized text never leave the device.
 *
 * Paddle returns a quadrilateral for every recognized text line. Those boxes are retained and
 * reconstructed into visual rows so item names and prices keep their left-to-right relationship.
 */
@Singleton
class ReceiptScanner @Inject constructor() {
    private val engineMutex = Mutex()
    private var engine: PaddleOCR? = null

    suspend fun recognizeCells(context: Context, uri: Uri): List<Row> {
        val bitmap = decodeBitmap(context, uri)
        engineMutex.lock()
        return try {
            val result = getOrCreateEngine(context).recognize(bitmap)
            val detections = result.results.mapNotNull { recognized ->
                val points = recognized.box.points
                if (points.size != 4 || points.any { !it.x.isFinite() || !it.y.isFinite() }) {
                    return@mapNotNull null
                }
                DetectedLine(
                    text = recognized.text,
                    left = points.minOf { it.x }.toInt(),
                    right = points.maxOf { it.x }.toInt(),
                    top = points.minOf { it.y }.toInt(),
                    bottom = points.maxOf { it.y }.toInt(),
                )
            }
            detectedLinesToRows(detections)
        } finally {
            engineMutex.unlock()
            bitmap.recycle()
        }
    }

    suspend fun recognizeLines(context: Context, uri: Uri): List<String> =
        recognizeCells(context, uri).map { it.text }

    private suspend fun getOrCreateEngine(context: Context): PaddleOCR {
        engine?.let { return it }
        check(OpenCVUtils.init(context.applicationContext)) { "Unable to initialize OpenCV for PaddleOCR" }
        return PaddleOCR.create(
            context = context.applicationContext,
            config = PaddleOCRConfig(
                detLimitSideLen = DETECTION_EDGE_PX,
                detLimitType = "max",
                detMaxSideLimit = DETECTION_EDGE_PX,
                recScoreThresh = MIN_RECOGNITION_CONFIDENCE,
                recBatchSize = 1,
            ),
            engineConfig = EngineConfig(
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
            ),
        ).also { engine = it }
    }

    private suspend fun decodeBitmap(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Unable to open receipt image")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Unable to decode receipt image")
        }

        var sampleSize = 1
        val longestEdge = maxOf(bounds.outWidth, bounds.outHeight)
        while (longestEdge / sampleSize > MAX_DECODED_EDGE_PX) sampleSize *= 2

        val bitmap = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: throw IOException("Unable to decode receipt image")

        val exif = runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull()
        transformForExif(bitmap, exif)
    }

    private fun transformForExif(bitmap: Bitmap, exif: ExifInterface?): Bitmap {
        val rotation = exif?.rotationDegrees ?: 0
        val flipped = exif?.isFlipped ?: false
        if (rotation == 0 && !flipped) return bitmap

        val matrix = Matrix().apply {
            if (flipped) postScale(-1f, 1f)
            if (rotation != 0) postRotate(rotation.toFloat())
        }
        val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (transformed !== bitmap) bitmap.recycle()
        return transformed
    }

    private companion object {
        const val MAX_DECODED_EDGE_PX = 2400
        const val DETECTION_EDGE_PX = 1280
        const val MIN_RECOGNITION_CONFIDENCE = 0.35f
    }
}
