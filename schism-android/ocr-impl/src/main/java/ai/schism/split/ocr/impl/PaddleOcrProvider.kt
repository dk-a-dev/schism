package ai.schism.split.ocr.impl

import ai.schism.split.ocr.api.OcrLine
import ai.schism.split.ocr.api.OcrModelFiles
import ai.schism.split.ocr.api.OcrOutput
import ai.schism.split.ocr.api.OcrPoint
import ai.schism.split.ocr.api.OcrProvider
import ai.schism.split.ocr.api.OcrTiming
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.model.ModelSource
import com.paddle.ocr.model.OCRResult
import com.paddle.ocr.model.OCRRunResult
import com.paddle.ocr.util.OpenCVUtils
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PaddleOcrProvider : OcrProvider {
    private val engineMutex = Mutex()
    private var engine: PaddleOCR? = null
    private var loadedModels: OcrModelFiles? = null

    override suspend fun recognize(context: Context, uri: Uri, models: OcrModelFiles): OcrOutput {
        val bitmap = decodeBitmap(context, uri)
        return try {
            engineMutex.withLock {
                getOrCreateEngine(context.applicationContext, models).recognize(bitmap).toOcrOutput()
            }
        } finally {
            bitmap.recycle()
        }
    }

    override suspend fun close() {
        engineMutex.withLock {
            engine?.release()
            engine = null
            loadedModels = null
        }
    }

    private suspend fun getOrCreateEngine(context: Context, models: OcrModelFiles): PaddleOCR {
        if (models == loadedModels) {
            engine?.let { return it }
        }
        engine?.release()
        check(OpenCVUtils.init(context)) { "Unable to initialize OpenCV for PaddleOCR" }
        return PaddleOCR.create(
            context = context,
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
            detModel = ModelSource.FilePath(models.detection),
            recModel = ModelSource.FilePath(models.recognition),
            recConfig = ModelSource.FilePath(models.recognitionConfig),
        ).also {
            engine = it
            loadedModels = models
        }
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

internal fun OCRResult.toOcrLineOrNull(): OcrLine? {
    val sourcePoints = box.points
    if (sourcePoints.size != 4 || sourcePoints.any { !it.x.isFinite() || !it.y.isFinite() }) {
        return null
    }
    return OcrLine(
        text = text,
        confidence = confidence,
        points = sourcePoints.map { OcrPoint(it.x, it.y) },
    )
}

internal fun OCRRunResult.toOcrOutput() = OcrOutput(
    lines = results.mapNotNull(OCRResult::toOcrLineOrNull),
    timing = OcrTiming(
        detectionTimeMs = detectionTimeMs,
        recognitionTimeMs = recognitionTimeMs,
        totalTimeMs = totalTimeMs,
        detPreprocessMs = detPreprocessMs,
        detInferenceMs = detInferenceMs,
        detPostprocessMs = detPostprocessMs,
        recPreprocessMs = recPreprocessMs,
        recInferenceMs = recInferenceMs,
        recPostprocessMs = recPostprocessMs,
        pipelineOverheadMs = pipelineOverheadMs,
        coldLoadTimeMs = coldLoadTimeMs,
    ),
)
