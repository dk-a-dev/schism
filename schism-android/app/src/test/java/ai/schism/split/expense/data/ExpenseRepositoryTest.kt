package ai.schism.split.expense.data

import ai.schism.split.core.db.ExpenseDao
import ai.schism.split.core.db.SchismDb
import ai.schism.split.core.net.ApiClient
import ai.schism.split.core.net.ApiService
import ai.schism.split.core.net.ExpenseRequest
import ai.schism.split.core.net.PaidForDto
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExpenseRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var db: SchismDb
    private lateinit var dao: ExpenseDao
    private lateinit var api: ApiService
    private lateinit var repo: ExpenseRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SchismDb::class.java,
        ).allowMainThreadQueries().build()
        dao = db.expenseDao()
        api = ApiClient.create(server.url("/").toString())
        repo = ExpenseRepository(api, dao)
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun expenseJson(
        id: String,
        groupId: String,
        title: String,
        amount: Long,
    ) = """{"id":"$id","groupId":"$groupId","title":"$title","amount":$amount,
            "categoryId":3,"expenseDate":"2026-07-04","paidById":"p1","splitMode":"EVENLY",
            "isReimbursement":false,"notes":"","createdAt":"2026-07-05T00:00:00Z",
            "paidFor":[{"participantId":"p1","shares":1},{"participantId":"p2","shares":1}]}"""

    private val sampleRequest = ExpenseRequest(
        title = "Dinner",
        amount = 4200,
        categoryId = 3,
        expenseDate = "2026-07-04",
        paidById = "p1",
        splitMode = "EVENLY",
        paidFor = listOf(PaidForDto("p1", 1), PaidForDto("p2", 1)),
    )

    @Test
    fun createPostsThenExpenseIsObservableFromCacheWithPaidFor() = runTest {
        server.enqueue(MockResponse().setBody(expenseJson("e1", "g1", "Dinner", 4200)))

        val created = repo.createExpense("g1", sampleRequest).getOrThrow()
        assertEquals("e1", created.id)
        assertEquals(2, created.paidFor.size)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/groups/g1/expenses", request.path)

        val cached = repo.observeExpenses("g1").first()
        assertEquals(1, cached.size)
        assertEquals("Dinner", cached[0].title)
        assertEquals(4200, cached[0].amount)
        assertEquals(2, cached[0].paidFor.size)
        assertEquals("p1", cached[0].paidFor[0].participantId)
    }

    @Test
    fun refreshReplacesCacheAndExpensesAreObservable() = runTest {
        // seed the cache with an expense the server no longer returns
        server.enqueue(MockResponse().setBody(expenseJson("stale", "g1", "Old", 100)))
        repo.createExpense("g1", sampleRequest).getOrThrow()
        server.takeRequest()

        server.enqueue(
            MockResponse().setBody(
                "[" + expenseJson("e2", "g1", "Lunch", 1500) + "," +
                    expenseJson("e3", "g1", "Cab", 900) + "]",
            ),
        )

        repo.refreshExpenses("g1").getOrThrow()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/groups/g1/expenses", request.path)

        val cached = repo.observeExpenses("g1").first()
        assertEquals(2, cached.size)
        assertTrue(cached.none { it.id == "stale" })
        assertTrue(cached.any { it.title == "Lunch" })
        assertTrue(cached.any { it.title == "Cab" })
    }

    @Test
    fun getBalancesParsesBalancesAndReimbursements() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"balances":{
                        "p1":{"paid":4200,"paidFor":2100,"total":2100},
                        "p2":{"paid":0,"paidFor":2100,"total":-2100}},
                    "reimbursements":[{"from":"p2","to":"p1","amount":2100}]}""",
            ),
        )

        val balances = repo.getBalances("g1").getOrThrow()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/groups/g1/balances", request.path)

        assertEquals(2, balances.perParticipant.size)
        assertEquals(4200, balances.perParticipant.getValue("p1").paid)
        assertEquals(2100, balances.perParticipant.getValue("p1").total)
        assertEquals(-2100, balances.perParticipant.getValue("p2").total)
        assertEquals(1, balances.reimbursements.size)
        assertEquals("p2", balances.reimbursements[0].from)
        assertEquals("p1", balances.reimbursements[0].to)
        assertEquals(2100, balances.reimbursements[0].amount)
    }

    @Test
    fun getActivitiesParsesAList() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":"a1","groupId":"g1","time":"2026-07-05T00:00:00Z",
                        "activityType":"EXPENSE_CREATED","participantId":"p1",
                        "expenseId":"e1","data":"Dinner"},
                    {"id":"a2","groupId":"g1","time":"2026-07-05T01:00:00Z",
                        "activityType":"GROUP_UPDATED","participantId":null,
                        "expenseId":null,"data":""}]""",
            ),
        )

        val activities = repo.getActivities("g1").getOrThrow()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/groups/g1/activities", request.path)

        assertEquals(2, activities.size)
        assertEquals("EXPENSE_CREATED", activities[0].activityType)
        assertEquals("e1", activities[0].expenseId)
        assertEquals("p1", activities[0].participantId)
        assertEquals(null, activities[1].participantId)
        assertEquals(null, activities[1].expenseId)
    }
}
