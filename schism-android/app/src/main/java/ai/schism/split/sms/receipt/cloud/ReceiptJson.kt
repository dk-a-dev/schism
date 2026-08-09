package ai.schism.split.sms.receipt.cloud

import ai.schism.split.sms.receipt.ChargeKind
import ai.schism.split.sms.receipt.ChargeLine
import ai.schism.split.sms.receipt.ReceiptDraft
import ai.schism.split.sms.receipt.ReceiptLineItem
import org.json.JSONObject
import kotlin.math.roundToLong

/**
 * The one receipt JSON shape this app asks any model for — on-device LLM, Schism's backend, or the
 * user's own Gemini/Groq key. Keeping a single shape means a single parser ([receiptDraftFromJson])
 * and a single set of rules about what counts as a dish.
 */
const val RECEIPT_JSON_SHAPE =
    """{"merchant": string, "date": "YYYY-MM-DD" or null, "items": [{"name": string, "qty": number, "amount": number}], "subtotal": number, "tax": number, "total": number}"""

/**
 * Maps Schism's own `POST /v1/receipts/extract` response onto a [ReceiptDraft]. Unlike a raw model
 * answer this already speaks minor units and carries per-row [ChargeLine]s, so nothing is rounded or
 * re-derived here — the one thing still checked is that it actually describes a bill.
 */
fun backendDraft(payload: String): ReceiptDraft {
    val obj = runCatching { JSONObject(payload) }.getOrNull()
        ?: throw CloudReceiptFailure.Unreadable.asException()

    val itemsArr = obj.optJSONArray("items") ?: throw CloudReceiptFailure.Unreadable.asException()
    val items = (0 until itemsArr.length()).mapNotNull { i ->
        val item = itemsArr.optJSONObject(i) ?: return@mapNotNull null
        val name = item.optString("name").trim().ifBlank { return@mapNotNull null }
        val amount = item.optLong("amountMinor")
        if (amount <= 0) return@mapNotNull null
        ReceiptLineItem(name = name.take(60), amountMinor = amount, qty = item.optInt("qty", 1).coerceAtLeast(1))
    }
    if (items.isEmpty()) throw CloudReceiptFailure.Unreadable.asException()

    val chargesArr = obj.optJSONArray("chargeLines")
    val charges = (0 until (chargesArr?.length() ?: 0)).mapNotNull { i ->
        val line = chargesArr?.optJSONObject(i) ?: return@mapNotNull null
        val kind = runCatching { ChargeKind.valueOf(line.optString("kind")) }.getOrNull() ?: ChargeKind.TAX
        ChargeLine(line.optString("label").trim(), line.optLong("amountMinor"), kind)
    }

    return ReceiptDraft(
        merchant = obj.optString("merchant").trim().ifBlank { "Receipt" }.take(60),
        totalMinor = obj.optLong("totalMinor"),
        currency = obj.optString("currency").trim().ifBlank { "₹" },
        date = obj.optString("date").trim().takeIf { it.length == 10 && it[4] == '-' },
        lineItems = items,
        taxMinor = obj.optLong("taxMinor"),
        subtotalMinor = obj.optLong("subtotalMinor"),
        parsedByAi = true,
        chargeLines = charges,
    )
}

/** Pull the first {...} block out of a model's response (they like to wrap JSON in prose/fences). */
fun extractJsonObject(raw: String): String? {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    return if (start in 0 until end) raw.substring(start, end + 1) else null
}

/**
 * Maps [RECEIPT_JSON_SHAPE] onto a [ReceiptDraft], or null when the payload is junk — no parseable
 * object, no items array, or every item unusable. Deliberately strict about items (a missing dish
 * the user re-adds beats a phone number priced as a dish) and forgiving about the totals, which are
 * derived from the items when absent.
 */
fun receiptDraftFromJson(json: String, currency: String = "₹"): ReceiptDraft? {
    val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null

    fun money(v: Double): Long = (v * 100).roundToLong()

    val itemsArr = obj.optJSONArray("items") ?: return null
    val items = (0 until itemsArr.length()).mapNotNull { i ->
        val it = itemsArr.optJSONObject(i) ?: return@mapNotNull null
        val name = it.optString("name").trim().ifBlank { return@mapNotNull null }
        val amount = it.optDouble("amount", Double.NaN)
        if (amount.isNaN() || amount <= 0) return@mapNotNull null
        ReceiptLineItem(
            name = name.take(60),
            amountMinor = money(amount),
            qty = it.optInt("qty", 1).coerceAtLeast(1),
        )
    }
    if (items.isEmpty()) return null

    val subtotal = obj.optDouble("subtotal", Double.NaN).takeIf { !it.isNaN() }?.let(::money)
        ?: items.sumOf { it.amountMinor }
    val tax = obj.optDouble("tax", Double.NaN).takeIf { !it.isNaN() && it >= 0 }?.let(::money) ?: 0L
    val total = obj.optDouble("total", Double.NaN).takeIf { !it.isNaN() }?.let(::money) ?: (subtotal + tax)
    val date = obj.optString("date").trim().takeIf { it.length == 10 && it[4] == '-' }

    return ReceiptDraft(
        merchant = obj.optString("merchant").trim().ifBlank { "Receipt" }.take(60),
        totalMinor = total,
        currency = obj.optString("currency").trim().ifBlank { currency },
        date = date,
        lineItems = items,
        taxMinor = tax,
        subtotalMinor = subtotal,
        parsedByAi = true,
    )
}
