package ai.schism.split.sms.receipt.cloud

import ai.schism.split.core.settings.SettingsRepository
import ai.schism.split.sms.receipt.ReceiptDraft
import android.content.Context
import android.net.Uri
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** The vision instruction sent to any cloud engine. Same output contract as the on-device LLM. */
private val PROMPT = """
    You are a precise receipt parser. Read the attached photo of a bill and extract structured JSON.
    Rules:
    - "items" = ONLY the purchased dishes/products. NEVER include phone numbers, bill/invoice numbers,
      dates, times, covers, table numbers, GST/CGST/SGST/tax rows, subtotal/total/pay rows, or
      customer details as items.
    - "qty" is the quantity column for that item; "amount" is that line's total price in currency units.
    - "tax" = all taxes and charges combined (GST/CGST/SGST/service), "total" = the final payable.
    - The sum of item amounts should be close to the subtotal. Drop anything that isn't a real price.
    Reply with ONLY minified JSON, no prose, exactly:
    $RECEIPT_JSON_SHAPE
""".trimIndent()

private const val GEMINI_URL =
    "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
private const val GROQ_MODEL = "meta-llama/llama-4-scout-17b-16e-instruct"

/** The backend's own ceiling. Checked here too so an oversized photo fails instantly and offline. */
private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

/**
 * Reads a receipt photo with whichever off-device engine the user chose. Every path returns a
 * [Result] whose failure is a [CloudReceiptException] carrying a [CloudReceiptFailure] — the caller
 * ([ai.schism.split.sms.itemized.BillScanViewModel]) turns that into a message and re-runs the
 * on-device engine on the same `uri`, so a cloud failure never dead-ends a user mid-bill.
 *
 * The user's own API key is read straight out of the Keystore-encrypted store at call time, sent on
 * a dedicated OkHttp client, and never logged: [ownKeyClient] deliberately does NOT reuse the app's
 * injected client, whose debug logging interceptor would print the Authorization header, and whose
 * BackendUrlInterceptor would rewrite api.groq.com to Schism's own host.
 */
@Singleton
class CloudReceiptScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    /** The app's authenticated client — used ONLY for the Schism-cloud path. */
    private val backendClient: OkHttpClient,
) {
    private val ownKeyClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    suspend fun scan(engine: ReceiptEngine, uri: Uri, groupId: String?): Result<ReceiptDraft> =
        withContext(Dispatchers.IO) {
            runCatching {
                val image = readImage(uri)
                when (engine) {
                    ReceiptEngine.ON_DEVICE -> throw CloudReceiptFailure.Unreadable.asException()
                    ReceiptEngine.SCHISM_CLOUD -> schismCloud(image, mimeTypeOf(uri), groupId)
                    ReceiptEngine.OWN_KEY -> ownKey(image, mimeTypeOf(uri))
                }
            }.recoverCatching { throw it.asCloudFailure() }
        }

    /**
     * Only jpeg/png/webp are accepted by the providers and by our own backend. Anything else (HEIC
     * from a stock camera, an unknown provider MIME) is labelled jpeg — the common case is a jpeg
     * behind a vague type, and a genuine mismatch just fails over to the on-device engine.
     */
    private fun mimeTypeOf(uri: Uri): String =
        context.contentResolver.getType(uri)?.takeIf { it in ALLOWED_MIME } ?: "image/jpeg"

    private fun readImage(uri: Uri): ByteArray {
        // A photo we can't even open is an "unreadable image", never a network problem.
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            .getOrNull() ?: throw CloudReceiptFailure.Unreadable.asException()
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) throw CloudReceiptFailure.Unreadable.asException()
        return bytes
    }

    /**
     * POST /v1/receipts/extract — `{groupId, mimeType, imageBase64}`, on the app's authenticated
     * client (whose BackendUrlInterceptor rewrites the placeholder host to the configured backend).
     * The backend answers in minor units already, so its draft maps straight across.
     */
    private fun schismCloud(image: ByteArray, mimeType: String, groupId: String?): ReceiptDraft {
        val body = JSONObject()
            .put("groupId", groupId.orEmpty())
            .put("mimeType", mimeType)
            .put("imageBase64", Base64.encodeToString(image, Base64.NO_WRAP))
        val request = Request.Builder()
            .url("http://localhost/v1/receipts/extract")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        backendClient.newCall(request).execute().use { response ->
            return backendDraft(response.readOrThrow())
        }
    }

    private suspend fun ownKey(image: ByteArray, mimeType: String): ReceiptDraft {
        val key = settings.receiptApiKey().ifBlank { throw CloudReceiptFailure.NoKey.asException() }
        val base64 = Base64.encodeToString(image, Base64.NO_WRAP)
        val provider = settings.receiptProvider.first()
        val request = when (provider) {
            ReceiptProvider.GEMINI -> geminiRequest(key, base64, mimeType)
            ReceiptProvider.GROQ -> groqRequest(key, base64, mimeType)
        }
        ownKeyClient.newCall(request).execute().use { response ->
            val payload = response.readOrThrow()
            val text = when (provider) {
                ReceiptProvider.GEMINI -> JSONObject(payload)
                    .optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                    ?.optString("text")
                ReceiptProvider.GROQ -> JSONObject(payload)
                    .optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content")
            }?.takeIf { it.isNotBlank() } ?: throw CloudReceiptFailure.Unreadable.asException()
            return receiptDraftFromJson(extractJsonObject(text) ?: text)
                ?: throw CloudReceiptFailure.Unreadable.asException()
        }
    }

    private fun geminiRequest(key: String, base64: String, mimeType: String): Request {
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray()
                            .put(JSONObject().put("text", PROMPT))
                            .put(
                                JSONObject().put(
                                    "inline_data",
                                    JSONObject().put("mime_type", mimeType).put("data", base64),
                                ),
                            ),
                    ),
                ),
            )
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        return Request.Builder()
            .url(GEMINI_URL)
            // Header, not a query parameter: a key in the URL ends up in proxy and server access logs.
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
    }

    private fun groqRequest(key: String, base64: String, mimeType: String): Request {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", PROMPT))
            .put(
                JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put("url", "data:$mimeType;base64,$base64"),
                ),
            )
        val body = JSONObject()
            .put("model", GROQ_MODEL)
            .put("temperature", 0)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", content)),
            )
        return Request.Builder()
            .url(GROQ_URL)
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
    }

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
        val ALLOWED_MIME = setOf("image/jpeg", "image/png", "image/webp")
    }
}

/** Wraps a [CloudReceiptFailure] so it can travel through [Result]. Carries no key material. */
class CloudReceiptException(val failure: CloudReceiptFailure) :
    IOException("Cloud receipt read failed: ${failure::class.simpleName}")

fun CloudReceiptFailure.asException() = CloudReceiptException(this)

/**
 * Reads a successful body, or converts the HTTP status into the failure the UI explains. 429 keeps
 * the server's Retry-After so the user is told when the next scan is actually available.
 */
private fun Response.readOrThrow(): String {
    if (isSuccessful) {
        return body?.string()?.takeIf { it.isNotBlank() }
            ?: throw CloudReceiptFailure.Unreadable.asException()
    }
    throw when (code) {
        401, 403 -> CloudReceiptFailure.InvalidKey
        408, 504 -> CloudReceiptFailure.Timeout
        429 -> CloudReceiptFailure.RateLimited(header("Retry-After")?.toLongOrNull() ?: 0L)
        // 413 image_too_large, 415 wrong format, 422 receipt_not_readable — all "that photo, not the
        // network", and all pointless to retry against the same engine.
        413, 415, 422 -> CloudReceiptFailure.Unreadable
        else -> CloudReceiptFailure.ServerError
    }.asException()
}

/** Anything thrown below the HTTP layer becomes a failure the user can act on. */
private fun Throwable.asCloudFailure(): CloudReceiptException = when (this) {
    is CloudReceiptException -> this
    is SocketTimeoutException -> CloudReceiptFailure.Timeout.asException()
    is UnknownHostException -> CloudReceiptFailure.Offline.asException()
    is IOException -> CloudReceiptFailure.Offline.asException()
    else -> CloudReceiptFailure.Unreadable.asException()
}
