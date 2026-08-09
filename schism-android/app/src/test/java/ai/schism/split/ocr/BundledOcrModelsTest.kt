package ai.schism.split.ocr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BundledOcrModelsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `materializes exact bundled model bytes into private files`() = runBlocking {
        val models = BundledOcrModels().materialize(context)

        val expected = mapOf(
            models.detection to "models/det/inference.onnx",
            models.recognition to "models/rec/inference.onnx",
            models.recognitionConfig to "models/rec/inference.yml",
        )
        expected.forEach { (file, assetPath) ->
            assertTrue(file.isFile)
            assertEquals(assetSha256(assetPath), fileSha256(file))
        }
    }

    @Test
    fun `repairs a corrupted private model from the bundled asset`() = runBlocking {
        val materializer = BundledOcrModels()
        val original = materializer.materialize(context)
        original.detection.writeText("corrupt")

        val repaired = materializer.materialize(context)

        assertEquals(assetSha256("models/det/inference.onnx"), fileSha256(repaired.detection))
    }

    private fun assetSha256(path: String): String = context.assets.open(path).use { input ->
        input.readBytes().sha256()
    }

    private fun fileSha256(file: java.io.File): String = file.readBytes().sha256()

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }
}
