package ai.schism.split.groups.detail

import ai.schism.split.core.db.GroupEntity
import ai.schism.split.core.db.ParticipantEntity
import ai.schism.split.core.db.SchismDb
import ai.schism.split.core.net.ApiClient
import ai.schism.split.core.settings.SettingsRepository
import ai.schism.split.core.ui.UiState
import ai.schism.split.expense.data.Activity
import ai.schism.split.expense.data.Balances
import ai.schism.split.expense.data.Expense
import ai.schism.split.expense.data.ExpenseRepository
import ai.schism.split.groups.data.GroupRepository
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GroupDetailViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var db: SchismDb
    private lateinit var settings: SettingsRepository
    private lateinit var groupRepo: GroupRepository
    private lateinit var expenseRepo: ExpenseRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        server = MockWebServer()
        // Respond by path so refresh()'s concurrent fan-out (group/expenses/balances/activities) all resolve.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.contains("/balances") -> MockResponse().setBody(
                        """{"balances":{"p1":{"paid":4200,"paidFor":2100,"total":2100},
                            "p2":{"paid":0,"paidFor":2100,"total":-2100}},
                            "reimbursements":[{"from":"p2","to":"p1","amount":2100}]}""",
                    )
                    path.contains("/activities") -> MockResponse().setBody(
                        """[{"id":"a1","groupId":"g1","time":"2026-07-05T00:00:00Z",
                            "activityType":"EXPENSE_CREATED","participantId":"p1","expenseId":"e1","data":"Dinner"}]""",
                    )
                    path.contains("/expenses") -> MockResponse().setBody(
                        """[{"id":"e1","groupId":"g1","title":"Dinner","amount":4200,"paidById":"p1",
                            "splitMode":"EVENLY","paidFor":[{"participantId":"p1","shares":1},
                            {"participantId":"p2","shares":1}]}]""",
                    )
                    path.endsWith("/v1/groups/g1") -> MockResponse().setBody(
                        """{"id":"g1","name":"Trip","currency":"₹","currencyCode":"INR",
                            "participants":[{"id":"p1","groupId":"g1","name":"Dev"},
                            {"id":"p2","groupId":"g1","name":"Sam"}]}""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SchismDb::class.java,
        ).allowMainThreadQueries().build()
        val api = ApiClient.create(server.url("/").toString())
        settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        runBlocking { settings.clear() } // DataStore is a JVM singleton; isolate from other tests
        groupRepo = GroupRepository(api, db.groupDao(), settings)
        expenseRepo = ExpenseRepository(api, db.expenseDao())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
        db.close()
    }

    private fun vm() = GroupDetailViewModel(
        groupRepo, expenseRepo, settings, SavedStateHandle(mapOf("groupId" to "g1")),
    )

    @Test
    fun youResolvesToParticipantMatchingProfile() = runTest(dispatcher) {
        db.groupDao().upsertGroupWithParticipants(
            GroupEntity("g1", "Trip", "", "₹", "INR", "2026-07-05T00:00:00Z"),
            listOf(ParticipantEntity("p1", "g1", "Dev"), ParticipantEntity("p2", "g1", "Sam")),
        )
        settings.setProfileName("dev") // case-insensitive match against "Dev"

        val vm = vm()

        val resolved = vm.group.filterNotNull().first { it.activeParticipantId != null }
        assertEquals("p1", resolved.activeParticipantId)
    }

    @Test
    fun expensesBalancesAndActivityPopulateFromRepos() = runTest(dispatcher) {
        val vm = vm()

        val expenses = vm.expenses.filterIsInstance<UiState.Data<List<Expense>>>().first().value
        assertEquals("Dinner", expenses.single().title)

        val balances = vm.balances.filterIsInstance<UiState.Data<Balances>>().first().value
        assertEquals(1, balances.reimbursements.size)
        assertEquals("p1", balances.reimbursements[0].to)
        assertEquals(2100, balances.perParticipant.getValue("p1").total)

        val activities = vm.activities.filterIsInstance<UiState.Data<List<Activity>>>().first().value
        assertTrue(activities.any { it.activityType == "EXPENSE_CREATED" })
    }
}
