package ai.schism.split.core.ai

import ai.schism.split.expense.edit.voice.SpokenExpenseDraft
import ai.schism.split.groups.data.Participant
import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
    private val settings: ai.schism.split.core.settings.SettingsRepository,
) {
    @Volatile private var engine: LlmInference? = null

    val isAvailable: Boolean get() = modelManager.isReady()

    private fun engine(): LlmInference? {
        if (!modelManager.isReady()) return null
        engine?.let { return it }
        return runCatching {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelManager.modelFile.absolutePath)
                // maxTokens covers input AND output: a full receipt prompt is ~700-1000 tokens, so a
                // small cap makes inference fail silently and the app falls back to the weak regex
                // parser. Needs a >=2k-context model (the backend serves an ekv4096 build).
                .setMaxTokens(2048)
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
        if (!settings.aiEnabled.first()) return@withContext null
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

    /**
     * Parse OCR text from a restaurant/shop receipt into a [ai.schism.split.sms.receipt.ReceiptDraft]
     * with per-item quantities and the tax, using the on-device model. Returns null when no model is
     * loaded or the output can't be understood — the caller falls back to the regex receipt parser.
     */
    suspend fun parseReceipt(
        ocrLines: List<String>,
    ): ai.schism.split.sms.receipt.ReceiptDraft? = withContext(Dispatchers.Default) {
        if (!settings.aiEnabled.first()) return@withContext null
        val llm = engine() ?: return@withContext null
        // Cap the OCR fed to the model so prompt + JSON output stay inside the 2048-token context.
        val ocr = ocrLines.map { it.take(64) }.take(45).joinToString("\n").take(2400)
        val prompt = buildReceiptPrompt(ocr)
        val raw = runCatching { llm.generateResponse(prompt) }.getOrNull() ?: return@withContext null
        val json = extractJson(raw) ?: return@withContext null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return@withContext null

        fun money(v: Double): Long = (v * 100).roundToLong()

        val itemsArr = obj.optJSONArray("items") ?: return@withContext null
        val items = (0 until itemsArr.length()).mapNotNull { i ->
            val it = itemsArr.optJSONObject(i) ?: return@mapNotNull null
            val name = it.optString("name").trim().ifBlank { return@mapNotNull null }
            val amount = it.optDouble("amount", Double.NaN)
            if (amount.isNaN() || amount <= 0) return@mapNotNull null
            ai.schism.split.sms.receipt.ReceiptLineItem(
                name = name.take(60),
                amountMinor = money(amount),
                qty = it.optInt("qty", 1).coerceAtLeast(1),
            )
        }
        if (items.isEmpty()) return@withContext null

        val subtotal = obj.optDouble("subtotal", Double.NaN).takeIf { !it.isNaN() }?.let(::money)
            ?: items.sumOf { it.amountMinor }
        val tax = obj.optDouble("tax", Double.NaN).takeIf { !it.isNaN() && it >= 0 }?.let(::money) ?: 0L
        val total = obj.optDouble("total", Double.NaN).takeIf { !it.isNaN() }?.let(::money) ?: (subtotal + tax)
        val date = obj.optString("date").trim().takeIf { it.length == 10 && it[4] == '-' }

        ai.schism.split.sms.receipt.ReceiptDraft(
            merchant = obj.optString("merchant").trim().ifBlank { "Receipt" }.take(60),
            totalMinor = total,
            currency = "₹",
            date = date,
            lineItems = items,
            taxMinor = tax,
            subtotalMinor = subtotal,
            parsedByAi = true,
        )
    }

    private fun buildReceiptPrompt(ocr: String): String = """
        You are a precise restaurant-receipt parser. From the OCR text below, extract structured JSON.
        Rules:
        - "items" = ONLY the purchased dishes/products. NEVER include phone numbers, mobile numbers,
          bill/invoice numbers, dates, times, covers, table numbers, GST/CGST/SGST/tax rows,
          subtotal/total/pay rows, or customer details as items.
        - Item names often wrap across two lines (e.g. "Buff Oklahoma" then "Smash") — join them into one name.
        - "qty" is the quantity column for that item; "amount" is that line's total price in currency units.
        - Sanity check: the sum of item amounts should be close to the subtotal. Drop anything that
          doesn't look like a dish price.
        - "tax" = all taxes and charges combined (GST/CGST/SGST/service), "total" = the final payable.
        Reply with ONLY minified JSON, no prose, exactly:
        {"merchant": string, "date": "YYYY-MM-DD" or null, "items": [{"name": string, "qty": number, "amount": number}], "subtotal": number, "tax": number, "total": number}
        OCR:
        ${ocr.replace("\"", "'")}
    """.trimIndent()

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
