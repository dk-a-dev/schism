package ai.schism.split.dashboard.group

import ai.schism.split.core.net.ApiClient
import ai.schism.split.core.net.ApiService
import ai.schism.split.core.ui.UiState
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GroupDashboardViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var api: ApiService

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        server = MockWebServer()
        server.start()
        api = ApiClient.create(server.url("/").toString())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
    }

    private fun vm() =
        GroupDashboardViewModel(api, SavedStateHandle(mapOf("groupId" to "g1")))

    @Test
    fun loadMapsTotalsCategoriesAndPersonalWithFormattedMoney() = runTest(dispatcher) {
        server.enqueue(MockResponse().setBody(DASHBOARD_JSON))

        val vm = vm()
        val ui = vm.state.filterIsInstance<UiState.Data<GroupDashboardUi>>().first().value

        assertEquals("Trip to Goa", ui.name)
        assertEquals("₹42.00", ui.totalSpendingFormatted)
        assertEquals(3, ui.expenseCount)
        assertEquals(1, ui.reimbursementCount)
        assertEquals("₹14.00", ui.averageExpenseFormatted)

        assertEquals(2, ui.byCategory.size)
        assertEquals("Food", ui.byCategory[0].name)
        assertEquals("₹30.00", ui.byCategory[0].amountFormatted)
        assertEquals(1f, ui.byCategory[0].fraction, 0.0001f)
        assertEquals(0.4f, ui.byCategory[1].fraction, 0.0001f)

        assertEquals(1, ui.byParticipant.size)
        assertEquals("₹42.00", ui.byParticipant[0].paidFormatted)
        assertEquals("-₹21.00", ui.byParticipant[0].netFormatted)

        assertEquals(1, ui.byMonth.size)
        assertEquals("₹42.00", ui.byMonth[0].amountFormatted)

        assertEquals(1, ui.topExpenses.size)
        assertEquals("Dinner", ui.topExpenses[0].title)
        assertEquals("₹30.00", ui.topExpenses[0].amountFormatted)

        assertNotNull(ui.personal)
        assertEquals("Alice", ui.personal!!.name)
        assertEquals("₹42.00", ui.personal!!.paidFormatted)
        assertEquals("₹21.00", ui.personal!!.shareFormatted)
        assertEquals("₹21.00", ui.personal!!.netFormatted)

        assertEquals("/v1/groups/g1/dashboard", server.takeRequest().path)
    }

    @Test
    fun serverErrorYieldsErrorState() = runTest(dispatcher) {
        server.enqueue(MockResponse().setResponseCode(500))

        val vm = vm()
        val error = vm.state.filterIsInstance<UiState.Error>().first()

        assertTrue("an error message should be surfaced", error.message.isNotEmpty())
    }

    private companion object {
        val DASHBOARD_JSON = """
            {
              "groupId": "g1",
              "name": "Trip to Goa",
              "currency": "₹",
              "currencyCode": "INR",
              "totalSpending": 4200,
              "expenseCount": 3,
              "reimbursementCount": 1,
              "averageExpense": 1400,
              "byCategory": [
                { "categoryId": 1, "name": "Food", "amount": 3000, "count": 2 },
                { "categoryId": 2, "name": "Travel", "amount": 1200, "count": 1 }
              ],
              "byParticipant": [
                { "participantId": "p1", "name": "Alice", "paid": 4200, "share": 2100, "net": -2100 }
              ],
              "byMonth": [
                { "month": "2026-07", "amount": 4200, "count": 3 }
              ],
              "topExpenses": [
                { "id": "e1", "title": "Dinner", "amount": 3000, "date": "2026-07-02", "paidById": "p1", "categoryId": 1 }
              ],
              "personal": {
                "participantId": "p1",
                "name": "Alice",
                "paid": 4200,
                "share": 2100,
                "net": 2100,
                "expenseCount": 3,
                "byCategory": []
              }
            }
        """.trimIndent()
    }
}
