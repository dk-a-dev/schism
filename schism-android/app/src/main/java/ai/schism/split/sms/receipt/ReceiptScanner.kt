package ai.schism.split.sms.receipt

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device receipt OCR via ML Kit's bundled Latin text recognizer. The image and its text never
 * leave the device — there is no cloud vision call.
 *
 * ML Kit returns text grouped in *blocks*, which on a two-column receipt (item names left, amounts
 * right) often yields all the names first and all the amounts after — destroying the name↔price
 * pairing. So instead of trusting block order, we reconstruct **visual rows**: every recognized line
 * is placed by its bounding box, lines whose vertical centers overlap are merged into one row, and
 * each row is joined left→right. That gives [parseReceipt] lines like "Sober Picante  1  248.00".
 */
@Singleton
class ReceiptScanner @Inject constructor() {
    // Lazy so merely constructing the scanner (e.g. in tests / DI) doesn't touch the ML Kit runtime;
    // the recognizer is created on first real scan.
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun recognizeLines(context: Context, uri: Uri): List<String> {
        val image = InputImage.fromFilePath(context, uri)
        val text = suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        val positioned = text.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line -> line.boundingBox?.let { box -> PositionedLine(box, line.text) } }
        if (positioned.isEmpty()) {
            return text.textBlocks.flatMap { block -> block.lines }.map { it.text }
        }
        return mergeIntoRows(positioned)
    }

    private data class PositionedLine(val box: Rect, val text: String) {
        val centerY: Int get() = box.centerY()
    }

    /** Merge OCR fragments into visual rows: same row when vertical centers overlap; sort rows by X. */
    private fun mergeIntoRows(lines: List<PositionedLine>): List<String> {
        val sorted = lines.sortedBy { it.centerY }
        val rows = mutableListOf<MutableList<PositionedLine>>()
        for (line in sorted) {
            val row = rows.lastOrNull()
            // Same visual row when this fragment's center falls within the row's vertical band.
            val rowCenter = row?.let { r -> r.sumOf { it.centerY } / r.size }
            val threshold = row?.let { r -> (r.sumOf { it.box.height() } / r.size) * 0.6 } ?: 0.0
            if (row != null && rowCenter != null && kotlin.math.abs(line.centerY - rowCenter) <= threshold) {
                row.add(line)
            } else {
                rows.add(mutableListOf(line))
            }
        }
        return rows.map { row -> row.sortedBy { it.box.left }.joinToString("  ") { it.text.trim() } }
    }
}
