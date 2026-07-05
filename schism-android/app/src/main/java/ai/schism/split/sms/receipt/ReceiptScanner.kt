package ai.schism.split.sms.receipt

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
 * leave the device — there is no cloud vision call. Returns the recognized lines for [parseReceipt].
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
        return text.textBlocks.flatMap { block -> block.lines }.map { it.text }
    }
}
