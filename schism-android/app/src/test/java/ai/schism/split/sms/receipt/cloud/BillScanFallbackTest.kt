package ai.schism.split.sms.receipt.cloud

import ai.schism.split.R
import ai.schism.split.core.ai.LlmExpenseParser
import ai.schism.split.core.ai.ModelManager
import ai.schism.split.core.settings.SettingsRepository
import ai.schism.split.sms.itemized.BillScanViewModel
import ai.schism.split.sms.itemized.PendingReceipt
import ai.schism.split.sms.receipt.ReceiptScanner
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * The rule that matters most: a cloud engine that fails must hand the SAME photo to the on-device
 * engine and tell the user why, never dead-end them mid-bill. "On-device ran" is observed through
 * the second toast — the on-device scanner has no OCR models in a JVM test, so its outcome message
 * is the proof it was reached at all.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BillScanFallbackTest {
    // One scheduler shared by runTest and Dispatchers.Main, so advanceUntilIdle drives the view model.
    private val scheduler = TestCoroutineScheduler()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SettingsRepository(context)
    private val pending = PendingReceipt()
    private val server = MockWebServer()
    private lateinit var viewModel: BillScanViewModel
    private lateinit var imageUri: Uri

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        ShadowToast.reset()
        settings.clear()
        server.start()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val base = server.url("/")
            val url = chain.request().url.newBuilder()
                .scheme(base.scheme).host(base.host).port(base.port).build()
            chain.proceed(chain.request().newBuilder().url(url).build())
        }.build()
        viewModel = BillScanViewModel(
            receiptScanner = ReceiptScanner(),
            pending = pending,
            llmParser = LlmExpenseParser(context, ModelManager(context), settings),
            cloudScanner = CloudReceiptScanner(context, settings, client),
            settings = settings,
            appContext = context,
        )
        imageUri = Uri.fromFile(File(context.cacheDir, "bill.jpg").apply { writeBytes(ByteArray(64) { 3 }) })
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    /**
     * Drives one scan to completion. The scan hops to a real IO dispatcher for the network, which the
     * test scheduler can't fast-forward, so pump the scheduler until the view model reports it's done.
     */
    private fun TestScope.awaitScan(onItemized: () -> Unit = {}) {
        // An unconfined watcher sees the busy flag flip even when the whole scan resolves inside one
        // advanceUntilIdle; "was busy, isn't any more" is then the completion signal.
        var started = false
        val watcher = launch(UnconfinedTestDispatcher(scheduler)) {
            viewModel.scanning.collect { if (it) started = true }
        }
        viewModel.scan(imageUri, onItemized)
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            if (started && !viewModel.scanning.value) break
            Thread.sleep(10)
        }
        advanceUntilIdle()
        watcher.cancel()
    }

    private suspend fun useSchismCloud() {
        settings.grantReceiptCloudConsent(ReceiptEngine.SCHISM_CLOUD)
        settings.setReceiptEngine(ReceiptEngine.SCHISM_CLOUD)
    }

    @Test
    fun `a rate limited scan explains the wait and falls back on device`() = runTest(scheduler) {
        useSchismCloud()
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2700"))
        var itemized = false

        awaitScan { itemized = true }

        assertEquals("the cloud was tried exactly once", 1, server.requestCount)
        // Two messages: why the cloud didn't work, then the on-device attempt's own outcome.
        assertEquals(2, ShadowToast.shownToastCount())
        val expected = context.getString(
            R.string.receiptai_fallback,
            context.getString(R.string.receiptai_fail_rate_limited, "in 45 min"),
        )
        assertTrue("expected the wait to be explained: $expected", ShadowToast.showedToast(expected))
        assertFalse("no draft, so the user is not sent onward with nothing", itemized)
        assertNull(pending.draft)
    }

    @Test
    fun `a cloud success skips the on-device engine entirely`() = runTest(scheduler) {
        useSchismCloud()
        server.enqueue(
            MockResponse().setBody(
                """{"merchant":"Cafe","currency":"₹","items":[{"name":"Filter Coffee","qty":1,"amountMinor":4000}],"subtotalMinor":4000,"taxMinor":0,"totalMinor":4000}""",
            ),
        )
        var itemized = false

        awaitScan { itemized = true }

        assertTrue(itemized)
        assertNotNull(pending.draft)
        assertEquals("Cafe", pending.draft?.merchant)
        assertEquals(0, ShadowToast.shownToastCount())
    }

    @Test
    fun `a cloud engine without consent never uploads anything`() = runTest(scheduler) {
        // Engine selected but the disclosure was never accepted — treat it as on-device.
        settings.setReceiptEngine(ReceiptEngine.SCHISM_CLOUD)

        awaitScan()

        assertEquals("no photo may leave the device without consent", 0, server.requestCount)
        assertNull(pending.draft)
    }

    @Test
    fun `the default engine uploads nothing at all`() = runTest(scheduler) {
        assertEquals(ReceiptEngine.ON_DEVICE, settings.receiptEngine.first())

        awaitScan()

        assertEquals(0, server.requestCount)
    }
}
