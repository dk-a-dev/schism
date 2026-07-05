package ai.schism.split.dashboard.personal

import ai.schism.split.core.net.ApiClient
import ai.schism.split.core.net.ApiService
import ai.schism.split.core.settings.SettingsRepository
import ai.schism.split.core.ui.UiState
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PersonalDashboardViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var api: ApiService
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        server = MockWebServer()
        server.start()
        api = ApiClient.create(server.url("/").toString())
        settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        runBlocking { settings.clear() } // DataStore is a JVM singleton; isolate from other tests
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
    }

    @Test
    fun groupsTotalsPerCurrencyAndFormatsWithOwnSymbol() = runTest(dispatcher) {
        runBlocking {
            settings.setProfileName("Dev")
            settings.addKnownGroup("g1")
            settings.addKnownGroup("g2")
        }
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "identity": "Dev",
                  "groupCount": 2,
                  "groups": [
                    {"groupId":"g1","groupName":"Goa Trip","currency":"₹","currencyCode":"INR",
                     "paid":10000,"share":12100,"net":-2100,"expenseCount":3},
                    {"groupId":"g2","groupName":"Berlin","currency":"€","currencyCode":"EUR",
                     "paid":5000,"share":3000,"net":2000,"expenseCount":2}
                  ],
                  "totals": [
                    {"currencyCode":"INR","currency":"₹","paid":10000,"share":12100,"net":-2100,"groupCount":1},
                    {"currencyCode":"EUR","currency":"€","paid":5000,"share":3000,"net":2000,"groupCount":1}
                  ]
                }
                """.trimIndent(),
            ),
        )

        val vm = PersonalDashboardViewModel(api, settings)
        val ui = vm.state.filterIsInstance<UiState.Data<PersonalDashboardUi>>().first().value

        // Two currencies stay separate — never summed together.
        assertEquals(2, ui.totalsByCurrency.size)
        val inr = ui.totalsByCurrency.first { it.currencyCode == "INR" }
        val eur = ui.totalsByCurrency.first { it.currencyCode == "EUR" }
        assertEquals("-₹21.00", inr.net)
        assertEquals("₹100.00", inr.paid)
        assertEquals("₹121.00", inr.share)
        assertEquals("€20.00", eur.net)
        assertEquals("€50.00", eur.paid)

        // Each group slice is formatted in its own currency.
        assertEquals(2, ui.groups.size)
        val g1 = ui.groups.first { it.groupId == "g1" }
        val g2 = ui.groups.first { it.groupId == "g2" }
        assertEquals("-₹21.00", g1.net)
        assertEquals(3, g1.expenseCount)
        assertEquals("€20.00", g2.net)

        assertEquals("/v1/dashboard", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun blankProfileEmitsEmptyWithoutCallingApi() = runTest(dispatcher) {
        val vm = PersonalDashboardViewModel(api, settings)

        vm.state.filterIsInstance<UiState.Empty>().first()

        assertEquals(0, server.requestCount)
    }
}
