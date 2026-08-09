package ai.schism.split.ocr

import ai.schism.split.ocr.api.OcrModelFiles
import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class BundledOcrModels {
    suspend fun materialize(context: Context): OcrModelFiles = withContext(Dispatchers.IO) {
        val root = File(context.noBackupFilesDir, "ocr/bundled-v1")
        OcrModelFiles(
            detection = copyAsset(context, DETECTION, File(root, DETECTION.fileName)),
            recognition = copyAsset(context, RECOGNITION, File(root, RECOGNITION.fileName)),
            recognitionConfig = copyAsset(context, RECOGNITION_CONFIG, File(root, RECOGNITION_CONFIG.fileName)),
        )
    }

    private fun copyAsset(context: Context, spec: AssetSpec, destination: File): File {
        if (destination.isFile && destination.length() == spec.size && destination.sha256() == spec.sha256) {
            return destination
        }

        destination.parentFile?.mkdirs()
        val part = File(destination.parentFile, "${destination.name}.part")
        context.assets.open(spec.assetPath).use { input ->
            FileOutputStream(part).use { output ->
                input.copyTo(output, DEFAULT_BUFFER_SIZE)
                output.fd.sync()
            }
        }
        check(part.length() == spec.size && part.sha256() == spec.sha256) {
            part.delete()
            "Bundled OCR asset failed verification: ${spec.assetPath}"
        }
        Files.move(
            part.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        return destination
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class AssetSpec(
        val assetPath: String,
        val fileName: String,
        val size: Long,
        val sha256: String,
    )

    private companion object {
        val DETECTION = AssetSpec(
            assetPath = "models/det/inference.onnx",
            fileName = "det.onnx",
            size = 1_780_590L,
            sha256 = "193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8",
        )
        val RECOGNITION = AssetSpec(
            assetPath = "models/rec/inference.onnx",
            fileName = "rec.onnx",
            size = 4_462_639L,
            sha256 = "9ef676d6ed3c88256a2d92c640c44f25b0c40947e111b14b8be8f594091563e6",
        )
        val RECOGNITION_CONFIG = AssetSpec(
            assetPath = "models/rec/inference.yml",
            fileName = "rec.yml",
            size = 55_571L,
            sha256 = "66170210bad538e83fff3c4a3867e547d6bf20b50d64b20347c4b913f3034ea1",
        )
    }
}
