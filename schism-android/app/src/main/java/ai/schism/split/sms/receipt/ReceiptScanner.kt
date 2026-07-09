package ai.schism.split.sms.receipt

import ai.schism.split.sms.receipt.engine.Cell
import ai.schism.split.sms.receipt.engine.Row
import ai.schism.split.sms.receipt.engine.groupIntoRows
import android.content.Context
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
 * is placed by its bounding box as a [Cell], cells whose vertical centers overlap are grouped into
 * one [Row] by [groupIntoRows], and each row is joined left→right. That gives [parseReceipt] lines
 * like "Sober Picante  1  248.00".
 */
@Singleton
class ReceiptScanner @Inject constructor() {
    // Lazy so merely constructing the scanner (e.g. in tests / DI) doesn't touch the ML Kit runtime;
    // the recognizer is created on first real scan.
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun recognizeCells(context: Context, uri: Uri): List<Row> {
        val image = InputImage.fromFilePath(context, uri)
        val text = suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        val cells = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            line.boundingBox?.let { b -> Cell(line.text.trim(), b.left, b.right, b.centerY()) }
        }
        if (cells.isEmpty()) return emptyList()
        val medianH = text.textBlocks.flatMap { it.lines }.mapNotNull { it.boundingBox?.height() }
            .sorted().let { if (it.isEmpty()) 30 else it[it.size / 2] }
        return groupIntoRows(cells, medianH)
    }

    suspend fun recognizeLines(context: Context, uri: Uri): List<String> =
        recognizeCells(context, uri).map { it.text }
}
