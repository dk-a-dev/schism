package ai.schism.split.sms.receipt.cloud

import ai.schism.split.core.settings.SettingsRepository
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CloudReceiptScannerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SettingsRepository(context)
    private val server = MockWebServer()
    private lateinit var scanner: CloudReceiptScanner
    private lateinit var imageUri: Uri

    @Before
    fun setUp() = runBlocking {
        settings.clear()
        server.start()
        // Mirrors production: the app's client rewrites the placeholder host to the real backend.
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.newBuilder()
                .scheme(server.url("/").scheme)
                .host(server.url("/").host)
                .port(server.url("/").port)
                .build()
            chain.proceed(chain.request().newBuilder().url(url).build())
        }.build()
        scanner = CloudReceiptScanner(context, settings, client)

        val file = File(context.cacheDir, "receipt.jpg").apply { writeBytes(ByteArray(64) { 7 }) }
        imageUri = Uri.fromFile(file)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun failureOf(result: Result<*>): CloudReceiptFailure? =
        (result.exceptionOrNull() as? CloudReceiptException)?.failure

    @Test
    fun `a good response becomes a draft`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"merchant":"Dosa Corner","date":"2026-01-05","currency":"₹","items":[{"name":"Masala Dosa","qty":2,"amountMinor":24000}],"subtotalMinor":24000,"taxMinor":1200,"totalMinor":25200,"chargeLines":[{"label":"GST 5%","amountMinor":1200,"kind":"TAX"}]}""",
            ),
        )

        val draft = scanner.scan(ReceiptEngine.SCHISM_CLOUD, imageUri, "group-1").getOrThrow()

        assertEquals("Dosa Corner", draft.merchant)
        assertEquals(25200L, draft.totalMinor)
        assertEquals(1200L, draft.taxMinor)
        assertEquals(1, draft.lineItems.size)
        assertEquals(2, draft.lineItems.first().qty)
        assertEquals(listOf("GST 5%"), draft.chargeLines.map { it.label })
        assertTrue(draft.parsedByAi)

        val request = server.takeRequest()
        assertEquals("/v1/receipts/extract", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("group-1", body.getString("groupId"))
        assertEquals("image/jpeg", body.getString("mimeType"))
        assertTrue("image must be sent", body.getString("imageBase64").isNotBlank())
    }

    @Test
    fun `429 reports when the next scan is available`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2700"))

        val failure = failureOf(scanner.scan(ReceiptEngine.SCHISM_CLOUD, imageUri, "group-1"))

        assertEquals(CloudReceiptFailure.RateLimited(2700), failure)
        assertEquals("in 45 min", formatRetryAfter(2700))
    }

    @Test
    fun `429 without a Retry-After still fails cleanly`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))

        assertEquals(
            CloudReceiptFailure.RateLimited(0),
            failureOf(scanner.scan(ReceiptEngine.SCHISM_CLOUD, imageUri, null)),
        )
    }

    @Test
    fun `rejected credentials are reported as an invalid key`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(
            CloudReceiptFailure.InvalidKey,
            failureOf(scanner.scan(ReceiptEngine.SCHISM_CLOUD, imageUri, null)),
        )
    }

    @Test
    fun `a server error is reported, not thrown`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(
            CloudReceiptFailure.ServerError,
            failureOf(scanner.scan(ReceiptEngine.SCHISM_CLOUD, imageUri, null)),
        )
    }

    @Test
    fun `junk from the provider is a failure, never a bogus draft`() = runTest {
        server.enqueue(MockResponse().setBody("I am afraid I cannot help with that."))

        val result = scanner.scan(ReceiptEngine.SCHISM_CLOUD, imageUri, null)

        assertEquals(CloudReceiptFailure.Unreadable, failureOf(result))
        assertNull(result.getOrNull())
    }

    @Test
    fun `an item-less draft is junk too`() = runTest {
        server.enqueue(MockResponse().setBody("""{"merchant":"X","items":[],"totalMinor":10000}"""))

        assertEquals(
            CloudReceiptFailure.Unreadable,
            failureOf(scanner.scan(ReceiptEngine.SCHISM_CLOUD, imageUri, null)),
        )
    }

    @Test
    fun `a dropped connection is reported as offline`() = runTest {
        server.shutdown()

        val failure = failureOf(scanner.scan(ReceiptEngine.SCHISM_CLOUD, imageUri, null))

        assertTrue(failure is CloudReceiptFailure.Offline || failure is CloudReceiptFailure.Timeout)
    }

    @Test
    fun `own-key with no key saved never reaches the network`() = runTest {
        val failure = failureOf(scanner.scan(ReceiptEngine.OWN_KEY, imageUri, null))

        assertEquals(CloudReceiptFailure.NoKey, failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an unreadable photo fails before any upload`() = runTest {
        val missing = Uri.fromFile(File(context.cacheDir, "gone.jpg"))

        assertEquals(
            CloudReceiptFailure.Unreadable,
            failureOf(scanner.scan(ReceiptEngine.SCHISM_CLOUD, missing, null)),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `retry-after is spoken in units a human reads`() {
        assertEquals("shortly", formatRetryAfter(0))
        assertEquals("in less than a minute", formatRetryAfter(30))
        assertEquals("in 1 min", formatRetryAfter(60))
        assertEquals("in 2 h", formatRetryAfter(3601))
    }
}
