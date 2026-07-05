package ai.schism.split.core.ai

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the on-device LLM used to parse voice/receipt text. The model (a MediaPipe `.task` file)
 * is downloaded on demand to app-private storage, so the app ships small and the heavy model is
 * optional. Fully local once downloaded; nothing about the download or inference leaves the device
 * except fetching the model file itself. Exposes [state] so Settings can show progress / manage it.
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    sealed interface State {
        data object Absent : State
        data class Downloading(val percent: Int) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val client = OkHttpClient()
    private val dir = File(context.filesDir, "models").apply { mkdirs() }

    /** The downloaded model file (present only once a download completes). */
    val modelFile: File = File(dir, "expense-llm.task")

    private val _state = MutableStateFlow<State>(if (modelFile.exists()) State.Ready else State.Absent)
    val state: StateFlow<State> = _state.asStateFlow()

    fun isReady(): Boolean = modelFile.exists()

    /** Human-readable size of the downloaded model, or null if absent. */
    fun sizeBytes(): Long? = modelFile.takeIf { it.exists() }?.length()

    /** Stream [url] to the model file, publishing progress. Safe to call repeatedly (re-download). */
    suspend fun download(url: String) = withContext(Dispatchers.IO) {
        if (url.isBlank()) {
            _state.value = State.Failed("Set a model URL first")
            return@withContext
        }
        _state.value = State.Downloading(0)
        val part = File(dir, "expense-llm.task.part")
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    _state.value = State.Failed("Download failed (HTTP ${resp.code})")
                    return@withContext
                }
                val body = resp.body ?: run {
                    _state.value = State.Failed("Empty response")
                    return@withContext
                }
                val total = body.contentLength()
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buf = ByteArray(1 shl 16)
                        var downloaded = 0L
                        var n: Int
                        while (input.read(buf).also { n = it } >= 0) {
                            output.write(buf, 0, n)
                            downloaded += n
                            if (total > 0) {
                                _state.value = State.Downloading((downloaded * 100 / total).toInt())
                            }
                        }
                    }
                }
            }
            modelFile.delete()
            if (part.renameTo(modelFile)) {
                _state.value = State.Ready
            } else {
                part.delete()
                _state.value = State.Failed("Couldn't save the model")
            }
        } catch (e: Exception) {
            part.delete()
            _state.value = State.Failed(e.message ?: "Download failed")
        }
    }

    fun delete() {
        modelFile.delete()
        _state.value = State.Absent
    }
}
