package ai.schism.split.core.ai

import ai.schism.split.expense.edit.voice.SpokenExpenseDraft
import ai.schism.split.groups.data.Participant
import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * On-device LLM parser (MediaPipe LLM Inference) that turns free-form voice/receipt text into a
 * structured [SpokenExpenseDraft] — far more forgiving than the regex parser. Used only when a model
 * has been downloaded (see [ModelManager]); every entry point falls back to the regex parser when
 * the model is absent or inference fails, so the feature degrades gracefully and stays fully offline.
 */
@Singleton
class LlmExpenseParser @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager,
) {
    @Volatile private var engine: LlmInference? = null

    val isAvailable: Boolean get() = modelManager.isReady()

    private fun engine(): LlmInference? {
        if (!modelManager.isReady()) return null
        engine?.let { return it }
        return runCatching {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelManager.modelFile.absolutePath)
                .setMaxTokens(512)
                .build()
            LlmInference.createFromOptions(context, options)
        }.getOrNull()?.also { engine = it }
    }

    /**
     * Parse [text] into a draft using the on-device model, mapping named people to [participants].
     * Returns null when no model is loaded or the model's output can't be understood — the caller
     * then falls back to [ai.schism.split.expense.edit.voice.parseSpokenExpense].
     */
    suspend fun parseSpoken(
        text: String,
        participants: List<Participant>,
        youParticipantId: String?,
    ): SpokenExpenseDraft? = withContext(Dispatchers.Default) {
        val llm = engine() ?: return@withContext null
        val prompt = buildPrompt(text, participants.map { it.name })
        val raw = runCatching { llm.generateResponse(prompt) }.getOrNull() ?: return@withContext null
        val json = extractJson(raw) ?: return@withContext null

        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return@withContext null
        val amountMinor = obj.optDouble("amount", Double.NaN)
            .takeIf { !it.isNaN() && it > 0 }?.let { (it * 100).roundToLong() }
        val title = obj.optString("title").trim().ifBlank { null }
        val personal = obj.optBoolean("personal", false)

        fun idOf(name: String): String? {
            val n = name.trim().lowercase()
            if (n.isEmpty()) return null
            if (n == "me" || n == "i" || n == "myself") return youParticipantId
            return participants.firstOrNull { it.name.lowercase() == n }?.id
                ?: participants.firstOrNull { it.name.lowercase().startsWith(n) || n.startsWith(it.name.lowercase()) }?.id
        }

        val payer = obj.optString("payer").takeIf { it.isNotBlank() }?.let(::idOf) ?: youParticipantId
        val shared = obj.optJSONArray("sharedWith")?.let { arr ->
            (0 until arr.length()).mapNotNull { idOf(arr.optString(it)) }
        }.orEmpty()
        val paidFor = when {
            personal -> listOfNotNull(youParticipantId)
            shared.isNotEmpty() -> (shared + listOfNotNull(youParticipantId)).distinct()
            else -> null
        }

        SpokenExpenseDraft(
            title = title,
            amountMinor = amountMinor,
            payerParticipantId = payer,
            paidForParticipantIds = paidFor,
            isPersonal = personal,
        )
    }

    private fun buildPrompt(text: String, names: List<String>): String = """
        You extract a shared expense from a sentence. Known participants: ${names.joinToString(", ").ifBlank { "(none)" }}.
        Reply with ONLY minified JSON, no prose, in exactly this shape:
        {"amount": <number in currency units or null>, "title": <short string or null>, "payer": <one participant name or "me" or null>, "sharedWith": [<participant names>], "personal": <true or false>}
        Sentence: "${text.replace("\"", "'")}"
    """.trimIndent()

    /** Pull the first {...} block out of the model's response. */
    private fun extractJson(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start in 0 until end) raw.substring(start, end + 1) else null
    }

    fun close() {
        engine?.close()
        engine = null
    }
}
