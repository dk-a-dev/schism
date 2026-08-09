package ai.schism.split.ocr.api

import android.content.Context
import android.net.Uri
import java.io.File

data class OcrModelFiles(
    val detection: File,
    val recognition: File,
    val recognitionConfig: File,
)

data class OcrPoint(
    val x: Float,
    val y: Float,
)

data class OcrLine(
    val text: String,
    val confidence: Float,
    val points: List<OcrPoint>,
)

data class OcrTiming(
    val detectionTimeMs: Long,
    val recognitionTimeMs: Long,
    val totalTimeMs: Long,
    val detPreprocessMs: Long = 0,
    val detInferenceMs: Long = 0,
    val detPostprocessMs: Long = 0,
    val recPreprocessMs: Long = 0,
    val recInferenceMs: Long = 0,
    val recPostprocessMs: Long = 0,
    val pipelineOverheadMs: Long = 0,
    val coldLoadTimeMs: Long = 0,
)

data class OcrOutput(
    val lines: List<OcrLine>,
    val timing: OcrTiming,
)

interface OcrProvider {
    suspend fun recognize(context: Context, uri: Uri, models: OcrModelFiles): OcrOutput

    suspend fun close()
}
